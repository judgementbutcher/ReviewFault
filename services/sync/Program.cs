using System.IdentityModel.Tokens.Jwt;
using System.Data;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Net;
using System.Net.Mail;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.IdentityModel.Tokens;
using Amazon.S3;
using Amazon.S3.Model;
using System.Threading.RateLimiting;

var builder = WebApplication.CreateBuilder(args);
var connectionString = builder.Configuration.GetConnectionString("Sync")
    ?? "Host=localhost;Database=reviewfault;Username=reviewfault;Password=reviewfault";
var jwtKey = builder.Configuration["Auth:JwtKey"]
    ?? throw new InvalidOperationException("Auth:JwtKey must be configured");
var masterKey = Convert.FromBase64String(builder.Configuration["Crypto:MasterKeyBase64"]
    ?? throw new InvalidOperationException("Crypto:MasterKeyBase64 must be configured"));
var s3Endpoint = builder.Configuration["S3:Endpoint"]
    ?? throw new InvalidOperationException("S3:Endpoint must be configured");
var s3AccessKey = builder.Configuration["S3:AccessKey"]
    ?? throw new InvalidOperationException("S3:AccessKey must be configured");
var s3SecretKey = builder.Configuration["S3:SecretKey"]
    ?? throw new InvalidOperationException("S3:SecretKey must be configured");
var smtpActionBaseUrl = builder.Configuration["Smtp:ActionBaseUrl"]
    ?? throw new InvalidOperationException("Smtp:ActionBaseUrl must be configured");
var metricsToken = builder.Configuration["Metrics:Token"]
    ?? throw new InvalidOperationException("Metrics:Token must be configured");
if (jwtKey.Length < 32 || jwtKey.StartsWith("replace-", StringComparison.Ordinal) ||
    masterKey.All(value => value == 0) ||
    !Uri.TryCreate(s3Endpoint, UriKind.Absolute, out var s3EndpointUri) ||
    s3EndpointUri.Scheme is not ("http" or "https") ||
    !Uri.TryCreate(smtpActionBaseUrl, UriKind.Absolute, out var actionBaseUri) ||
    actionBaseUri.Scheme != "https" || metricsToken.Length < 32)
    throw new InvalidOperationException("Service security configuration is invalid");
foreach (var required in new[] { "Smtp:Host", "Smtp:Username", "Smtp:Password", "Smtp:From" })
    if (string.IsNullOrWhiteSpace(builder.Configuration[required]))
        throw new InvalidOperationException($"{required} must be configured");

builder.Services.AddDbContext<SyncDb>(options => options.UseNpgsql(connectionString));
builder.Services.AddIdentityCore<SyncUser>(options => {
    options.User.RequireUniqueEmail = true;
    options.Password.RequiredLength = 12;
    options.SignIn.RequireConfirmedEmail = true;
}).AddEntityFrameworkStores<SyncDb>().AddDefaultTokenProviders();
builder.Services.AddSingleton(new WorkspaceCrypto(masterKey));
var s3Config = new AmazonS3Config { ServiceURL = s3Endpoint, ForcePathStyle = true, AuthenticationRegion = "us-east-1" };
builder.Services.AddSingleton<IAmazonS3>(new AmazonS3Client(s3AccessKey, s3SecretKey, s3Config));
builder.Services.AddHostedService<SmtpOutboxWorker>();
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme).AddJwtBearer(options => {
    options.MapInboundClaims = false;
    options.TokenValidationParameters = new TokenValidationParameters {
        ValidateIssuerSigningKey = true, IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtKey)),
        ValidateIssuer = false, ValidateAudience = false, ClockSkew = TimeSpan.FromSeconds(30),
    };
});
builder.Services.AddAuthorization();
builder.Services.AddProblemDetails();
builder.Services.AddHealthChecks();
builder.Services.AddRateLimiter(options => {
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(context =>
        RateLimitPartition.GetFixedWindowLimiter(
            context.User.FindFirst("device_id")?.Value ?? context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
            _ => new FixedWindowRateLimiterOptions { PermitLimit = 300, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 }));
    options.AddPolicy("auth", context => RateLimitPartition.GetFixedWindowLimiter(
        context.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 10, Window = TimeSpan.FromMinutes(1), QueueLimit = 0 }));
});

var app = builder.Build();
using (var scope = app.Services.CreateScope()) {
    var database = scope.ServiceProvider.GetRequiredService<SyncDb>();
    await database.Database.EnsureCreatedAsync();
    if (args is ["--create-invite", var daysText] && int.TryParse(daysText, out var days)) {
        var code = Convert.ToHexString(RandomNumberGenerator.GetBytes(12)).ToLowerInvariant();
        database.Invitations.Add(new Invitation { Code = TokenHash(code), ExpiresAt = DateTimeOffset.UtcNow.AddDays(Math.Clamp(days, 1, 365)) });
        await database.SaveChangesAsync(); Console.WriteLine(code); return;
    }
    var s3 = scope.ServiceProvider.GetRequiredService<IAmazonS3>(); var bucket = builder.Configuration["S3:Bucket"] ?? "reviewfault-media";
    var buckets = await s3.ListBucketsAsync();
    if (!buckets.Buckets.Any(x => x.BucketName == bucket)) await s3.PutBucketAsync(new PutBucketRequest { BucketName = bucket });
}
app.UseExceptionHandler();
app.UseAuthentication();
app.UseRateLimiter();
app.Use(async (context, next) => {
    if (context.User.Identity?.IsAuthenticated == true) {
        var deviceClaim = context.User.FindFirst("device_id")?.Value;
        var workspaceClaim = context.User.FindFirst("workspace_id")?.Value;
        if (!Guid.TryParse(deviceClaim, out var deviceId) || !Guid.TryParse(workspaceClaim, out var workspaceId))
            { context.Response.StatusCode = StatusCodes.Status401Unauthorized; return; }
        var db = context.RequestServices.GetRequiredService<SyncDb>();
        if (!await db.Devices.AnyAsync(x => x.Id == deviceId && x.WorkspaceId == workspaceId && x.RevokedAt == null))
            { context.Response.StatusCode = StatusCodes.Status401Unauthorized; return; }
    }
    await next();
});
app.UseAuthorization();
app.MapHealthChecks("/healthz");
app.MapGet("/readyz", async (SyncDb db, IAmazonS3 s3) =>
    await db.Database.CanConnectAsync() && (await s3.ListBucketsAsync()).HttpStatusCode == HttpStatusCode.OK
        ? Results.Ok(new { status = "ready" }) : Results.StatusCode(503));
app.MapGet("/metrics", async (HttpRequest request, SyncDb db) => {
    if (!FixedTokenEquals(request.Headers.Authorization.ToString(), "Bearer " + metricsToken))
        return Results.Unauthorized();
    var pendingMail = await db.EmailOutbox.CountAsync(x => x.SentAt == null);
    var failedMail = await db.EmailOutbox.CountAsync(x => x.SentAt == null && x.Attempts > 0);
    var operations = await db.Operations.LongCountAsync(); var conflicts = await db.Conflicts.CountAsync(x => x.ResolvedAt == null);
    var storageBytes = await db.MediaObjects.SumAsync(x => (long?)x.ByteCount) ?? 0;
    var body = $"reviewfault_sync_operations_total {operations}\nreviewfault_email_queue_depth {pendingMail}\nreviewfault_email_failed_total {failedMail}\nreviewfault_sync_conflicts_unresolved {conflicts}\nreviewfault_media_bytes {storageBytes}\n";
    return Results.Text(body, "text/plain; version=0.0.4");
});
app.MapGet("/account/{kind}", (string kind, HttpContext context) => {
    if (kind is not ("verify_email" or "reset_password")) return Results.NotFound();
    context.Response.Headers["Content-Security-Policy"] =
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
        "connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";
    context.Response.Headers["X-Content-Type-Options"] = "nosniff";
    context.Response.Headers["Referrer-Policy"] = "no-referrer";
    return Results.Content(AccountActionPage(kind == "reset_password"), "text/html; charset=utf-8");
}).AllowAnonymous();

app.MapPost("/api/v1/auth/register", async (RegisterRequest request, SyncDb db, UserManager<SyncUser> users, WorkspaceCrypto crypto) => {
    await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
    var invitationCodeHash = TokenHash(request.InvitationCode);
    var now = DateTimeOffset.UtcNow;
    var invitation = await db.Invitations.SingleOrDefaultAsync(x =>
        x.Code == invitationCodeHash && x.UsedAt == null && x.ExpiresAt > now);
    if (invitation is null || invitation.ExpiresAt < DateTimeOffset.UtcNow) return Results.BadRequest(Problem("invalid_invitation"));
    var user = new SyncUser { UserName = request.Email, Email = request.Email, EmailConfirmed = false };
    var created = await users.CreateAsync(user, request.Password);
    if (!created.Succeeded) return Results.BadRequest(Problem("invalid_password", created.Errors.Select(x => x.Description)));
    var workspace = new Workspace { OwnerId = user.Id, DataKey = crypto.WrapKey(RandomNumberGenerator.GetBytes(32)) };
    db.Workspaces.Add(workspace);
    db.Members.Add(new WorkspaceMember { WorkspaceId = workspace.Id, UserId = user.Id });
    invitation.UsedAt = DateTimeOffset.UtcNow; invitation.UsedBy = user.Id;
    await db.SaveChangesAsync();
    // Deployments connect this audit event to the SMTP queue; tokens are never logged.
    db.Audits.Add(new AuditEvent { UserId = user.Id, Kind = "verification_requested", CreatedAt = DateTimeOffset.UtcNow });
    var verificationToken = await users.GenerateEmailConfirmationTokenAsync(user);
    db.EmailOutbox.Add(new EmailOutbox { Recipient = request.Email, Kind = "verify_email",
        EncryptedPayload = crypto.ProtectSystem(JsonSerializer.SerializeToUtf8Bytes(new MailToken(user.Id, verificationToken))),
        CreatedAt = DateTimeOffset.UtcNow, NextAttemptAt = DateTimeOffset.UtcNow });
    await db.SaveChangesAsync();
    await transaction.CommitAsync(); return Results.Accepted();
}).RequireRateLimiting("auth").AllowAnonymous();

app.MapPost("/api/v1/auth/verify-email", async (TokenRequest request, UserManager<SyncUser> users) => {
    var user = await users.FindByIdAsync(request.UserId.ToString());
    return user is not null && (await users.ConfirmEmailAsync(user, request.Token)).Succeeded
        ? Results.NoContent() : Results.BadRequest(Problem("invalid_verification"));
}).RequireRateLimiting("auth").AllowAnonymous();

app.MapPost("/api/v1/auth/login", async (LoginRequest request, UserManager<SyncUser> users, SyncDb db, IConfiguration config) => {
    if (string.IsNullOrWhiteSpace(request.DeviceName) || request.DeviceName.Length > 100)
        return Results.BadRequest(Problem("invalid_device_name"));
    var user = await users.FindByEmailAsync(request.Email);
    if (user is null || !user.EmailConfirmed || !await users.CheckPasswordAsync(user, request.Password))
        return Results.Unauthorized();
    var workspace = await db.Members.Where(x => x.UserId == user.Id).Select(x => x.Workspace).FirstAsync();
    var device = await db.Devices.SingleOrDefaultAsync(x => x.Id == request.DeviceId);
    if (device is not null && device.UserId != user.Id) return Results.Unauthorized();
    if (device is null) { device = new Device { Id = request.DeviceId, UserId = user.Id, WorkspaceId = workspace.Id, Name = request.DeviceName }; db.Devices.Add(device); }
    device.RevokedAt = null; device.LastSeenAt = DateTimeOffset.UtcNow;
    var tokens = IssueTokens(user, device, workspace.Id, config);
    db.RefreshTokens.Add(tokens.Stored); await db.SaveChangesAsync();
    return Results.Ok(tokens.Response);
}).RequireRateLimiting("auth").AllowAnonymous();

app.MapPost("/api/v1/auth/refresh", async (RefreshRequest request, SyncDb db, IConfiguration config) => {
    await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
    var hash = TokenHash(request.RefreshToken);
    var stored = await db.RefreshTokens.Include(x => x.Device).ThenInclude(x => x.User)
        .SingleOrDefaultAsync(x => x.DeviceId == request.DeviceId && x.TokenHash == hash);
    if (stored is null || stored.ExpiresAt <= DateTimeOffset.UtcNow || stored.Device.RevokedAt is not null)
        return Results.Unauthorized();
    if (stored.RevokedAt is not null) {
        await db.RefreshTokens.Where(x => x.Device.UserId == stored.Device.UserId).ExecuteUpdateAsync(x => x.SetProperty(t => t.RevokedAt, DateTimeOffset.UtcNow));
        await transaction.CommitAsync(); return Results.Unauthorized();
    }
    stored.RevokedAt = DateTimeOffset.UtcNow; stored.ReplacedBy = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
    var workspaceId = await db.Devices.Where(x => x.Id == request.DeviceId).Select(x => x.WorkspaceId).SingleAsync();
    var tokens = IssueTokens(stored.Device.User, stored.Device, workspaceId, config);
    db.RefreshTokens.Add(tokens.Stored); await db.SaveChangesAsync(); await transaction.CommitAsync();
    return Results.Ok(tokens.Response);
}).RequireRateLimiting("auth").AllowAnonymous();

app.MapPost("/api/v1/auth/logout", async (ClaimsPrincipal principal, SyncDb db) => {
    var device = principal.DeviceId();
    await db.RefreshTokens.Where(x => x.DeviceId == device && x.RevokedAt == null)
        .ExecuteUpdateAsync(x => x.SetProperty(t => t.RevokedAt, DateTimeOffset.UtcNow));
    return Results.NoContent();
}).RequireAuthorization();

app.MapPost("/api/v1/auth/request-password-reset", async (PasswordResetRequest request, UserManager<SyncUser> users, SyncDb db, WorkspaceCrypto crypto) => {
    var user = await users.FindByEmailAsync(request.Email);
    if (user is not null) { var token = await users.GeneratePasswordResetTokenAsync(user); db.Audits.Add(new AuditEvent { UserId = user.Id, Kind = "password_reset_requested", CreatedAt = DateTimeOffset.UtcNow });
        db.EmailOutbox.Add(new EmailOutbox { Recipient = request.Email, Kind = "reset_password",
            EncryptedPayload = crypto.ProtectSystem(JsonSerializer.SerializeToUtf8Bytes(new MailToken(user.Id, token))),
            CreatedAt = DateTimeOffset.UtcNow, NextAttemptAt = DateTimeOffset.UtcNow }); await db.SaveChangesAsync(); }
    return Results.Accepted();
}).RequireRateLimiting("auth").AllowAnonymous();

app.MapPost("/api/v1/auth/reset-password", async (ResetPasswordRequest request, UserManager<SyncUser> users, SyncDb db) => {
    var user = await users.FindByIdAsync(request.UserId.ToString());
    if (user is null || !(await users.ResetPasswordAsync(user, request.Token, request.NewPassword)).Succeeded)
        return Results.BadRequest(Problem("invalid_reset"));
    await db.RefreshTokens.Where(x => x.Device.UserId == user.Id && x.RevokedAt == null)
        .ExecuteUpdateAsync(x => x.SetProperty(token => token.RevokedAt, DateTimeOffset.UtcNow));
    return Results.NoContent();
}).RequireRateLimiting("auth").AllowAnonymous();

app.MapPost("/api/v1/sync/push", async (PushRequest request, ClaimsPrincipal principal, SyncDb db, WorkspaceCrypto crypto) => {
    var workspaceId = principal.WorkspaceId();
    var deviceId = principal.DeviceId();
    if (request.Operations is null || request.Operations.Count > 500 ||
        request.Operations.Any(x => x.DeviceId != deviceId || !ValidOperation(x)) ||
        request.Operations.Select(x => x.OperationId).Distinct().Count() != request.Operations.Count ||
        request.Operations.Select(x => x.DeviceCounter).Distinct().Count() != request.Operations.Count)
        return Results.BadRequest(Problem("invalid_operations"));
    if (!await db.Devices.AnyAsync(x => x.Id == deviceId && x.WorkspaceId == workspaceId && x.RevokedAt == null)) return Results.Unauthorized();
    await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
    var workspace = await db.Workspaces.SingleAsync(x => x.Id == workspaceId);
    var lastDeviceCounter = await db.Operations
        .Where(x => x.WorkspaceId == workspaceId && x.DeviceId == deviceId)
        .MaxAsync(x => (long?)x.DeviceCounter) ?? 0;
    var acknowledgements = new List<object>();
    foreach (var operation in request.Operations.OrderBy(x => x.DeviceCounter)) {
        var existing = await db.Operations.SingleOrDefaultAsync(x => x.WorkspaceId == workspaceId && x.OperationId == operation.OperationId);
        if (existing is not null) {
            if (existing.DeviceId != deviceId || existing.DeviceCounter != operation.DeviceCounter)
                return Results.Conflict(Problem("operation_id_reuse"));
            acknowledgements.Add(new { operationId = operation.OperationId, serverSeq = existing.ServerSeq }); continue;
        }
        var counterUsed = await db.Operations.AnyAsync(x => x.WorkspaceId == workspaceId && x.DeviceId == deviceId && x.DeviceCounter == operation.DeviceCounter);
        if (counterUsed) return Results.Conflict(Problem("device_counter_reuse"));
        if (operation.DeviceCounter <= lastDeviceCounter)
            return Results.Conflict(Problem("device_counter_regression"));
        var seq = ++workspace.LastServerSeq;
        var projectedPayload = operation.ChangedFields;
        if (operation.EntityType is not ("reviewAction" or "attempt" or "attemptArtifact" or "relation" or "learningEvidence" or "learningRelation")) {
            var state = db.EntityStates.Local.FirstOrDefault(x => x.WorkspaceId == workspaceId && x.EntityType == operation.EntityType && x.EntityId == operation.EntityId)
                ?? await db.EntityStates.SingleOrDefaultAsync(x => x.WorkspaceId == workspaceId && x.EntityType == operation.EntityType && x.EntityId == operation.EntityId);
            if (state is null) {
                state = new EntityState { WorkspaceId = workspaceId, EntityType = operation.EntityType, EntityId = operation.EntityId, Fields = crypto.ProtectForWorkspace(workspace.DataKey, "{}"u8.ToArray()) };
                db.EntityStates.Add(state);
            }
            var nextRevision = state.Revision + 1;
            var projectedFields = new Dictionary<string, JsonElement>();
            if (operation.Action == "delete") state.Deleted = true;
            if (operation.Action == "restore") state.Deleted = false;
            if ((!state.Deleted || operation.Action == "restore") && operation.Action is "create" or "update" or "restore") {
                var fields = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(crypto.UnprotectForWorkspace(workspace.DataKey, state.Fields)) ?? [];
                var versions = JsonSerializer.Deserialize<Dictionary<string, long>>(state.FieldVersionsJson) ?? [];
                var acceptedOperations = JsonSerializer.Deserialize<Dictionary<string, Guid>>(state.FieldOperationsJson) ?? [];
                foreach (var field in operation.ChangedFields.EnumerateObject()) {
                    var acceptedOperationId = acceptedOperations.GetValueOrDefault(field.Name);
                    var sameDevicePredecessor = acceptedOperationId != Guid.Empty &&
                        (db.Operations.Local.Any(x => x.WorkspaceId == workspaceId &&
                          x.OperationId == acceptedOperationId && x.DeviceId == deviceId &&
                          x.DeviceCounter < operation.DeviceCounter) ||
                         await db.Operations.AnyAsync(x => x.WorkspaceId == workspaceId &&
                          x.OperationId == acceptedOperationId && x.DeviceId == deviceId &&
                          x.DeviceCounter < operation.DeviceCounter));
                    if (versions.GetValueOrDefault(field.Name) > operation.BaseRevision &&
                        !sameDevicePredecessor) {
                        var accepted = fields.TryGetValue(field.Name, out var acceptedValue) ? acceptedValue : JsonSerializer.SerializeToElement<object?>(null);
                        db.Conflicts.Add(new SyncConflictEntity {
                            WorkspaceId = workspaceId, EntityType = operation.EntityType, EntityId = operation.EntityId,
                            FieldName = field.Name, AcceptedValue = crypto.ProtectForWorkspace(workspace.DataKey, JsonSerializer.SerializeToUtf8Bytes(accepted)),
                            CandidateValue = crypto.ProtectForWorkspace(workspace.DataKey, JsonSerializer.SerializeToUtf8Bytes(field.Value)),
                            AcceptedOperationId = acceptedOperationId, CandidateOperationId = operation.OperationId,
                            ServerSeq = seq, CreatedAt = DateTimeOffset.UtcNow,
                        });
                        projectedFields[field.Name] = accepted.Clone();
                        continue;
                    }
                    fields[field.Name] = field.Value.Clone();
                    projectedFields[field.Name] = field.Value.Clone(); versions[field.Name] = nextRevision;
                    acceptedOperations[field.Name] = operation.OperationId;
                }
                state.Fields = crypto.ProtectForWorkspace(workspace.DataKey, JsonSerializer.SerializeToUtf8Bytes(fields));
                state.FieldVersionsJson = JsonSerializer.Serialize(versions);
                state.FieldOperationsJson = JsonSerializer.Serialize(acceptedOperations);
            }
            state.Revision = nextRevision;
            projectedPayload = JsonSerializer.SerializeToElement(projectedFields);
        }
        db.Operations.Add(new SyncOperationEntity { OperationId = operation.OperationId, WorkspaceId = workspaceId, DeviceId = deviceId, DeviceCounter = operation.DeviceCounter, BaseCursor = operation.BaseCursor, BaseRevision = operation.BaseRevision, EntityType = operation.EntityType, EntityId = operation.EntityId, Action = operation.Action, Payload = crypto.ProtectForWorkspace(workspace.DataKey, JsonSerializer.SerializeToUtf8Bytes(projectedPayload)), OccurredAt = DateTimeOffset.FromUnixTimeSeconds(operation.OccurredAt), ServerSeq = seq });
        lastDeviceCounter = operation.DeviceCounter;
        acknowledgements.Add(new { operationId = operation.OperationId, serverSeq = seq });
    }
    await db.SaveChangesAsync(); await transaction.CommitAsync();
    return Results.Ok(new { acknowledgements, cursor = workspace.LastServerSeq });
}).RequireAuthorization();

app.MapGet("/api/v1/sync/pull", async (long cursor, int? limit, ClaimsPrincipal principal, SyncDb db, WorkspaceCrypto crypto) => {
    var take = Math.Clamp(limit ?? 200, 1, 500); var workspaceId = principal.WorkspaceId();
    var workspaceKey = await db.Workspaces.Where(x => x.Id == workspaceId).Select(x => x.DataKey).SingleAsync();
    var rows = await db.Operations.Where(x => x.WorkspaceId == workspaceId && x.ServerSeq > cursor).OrderBy(x => x.ServerSeq).Take(take).ToListAsync();
    var operations = rows.Select(x => new { operationId = x.OperationId, serverSeq = x.ServerSeq, deviceId = x.DeviceId, deviceCounter = x.DeviceCounter, entityType = x.EntityType, entityId = x.EntityId, action = x.Action, changedFields = JsonSerializer.Deserialize<JsonElement>(crypto.UnprotectForWorkspace(workspaceKey, x.Payload)), occurredAt = x.OccurredAt.ToUnixTimeSeconds() });
    return Results.Ok(new { cursor = rows.LastOrDefault()?.ServerSeq ?? cursor, operations });
}).RequireAuthorization();

app.MapPost("/api/v1/media/prepare", async (MediaPrepareRequest request, ClaimsPrincipal principal, SyncDb db) => {
    var workspaceId = principal.WorkspaceId(); var results = new List<object>();
    if (request.Objects.Count > 100) return Results.BadRequest(Problem("too_many_media_objects"));
    foreach (var item in request.Objects) {
        if (!ValidSha(item.Sha256) || item.ByteCount <= 0 || item.ByteCount > 512L * 1024 * 1024 || string.IsNullOrWhiteSpace(item.MimeType) || item.MimeType.Length > 100) return Results.BadRequest(Problem("invalid_media"));
        if (await db.MediaObjects.AnyAsync(x => x.WorkspaceId == workspaceId && x.Sha256 == item.Sha256)) { results.Add(new { sha256 = item.Sha256, present = true, uploadId = (Guid?)null }); continue; }
        var upload = await db.MediaUploads.FirstOrDefaultAsync(x => x.WorkspaceId == workspaceId && x.Sha256 == item.Sha256 && x.CompletedAt == null);
        if (upload is null) { upload = new MediaUpload { WorkspaceId = workspaceId, Sha256 = item.Sha256, ByteCount = item.ByteCount, MimeType = item.MimeType, CreatedAt = DateTimeOffset.UtcNow }; db.MediaUploads.Add(upload); }
        else if (upload.ByteCount != item.ByteCount || upload.MimeType != item.MimeType)
            return Results.Conflict(Problem("media_metadata_changed"));
        results.Add(new { sha256 = item.Sha256, present = false, uploadId = upload.Id });
    }
    await db.SaveChangesAsync(); return Results.Ok(new { objects = results, chunkBytes = 5 * 1024 * 1024 });
}).RequireAuthorization();

app.MapPut("/api/v1/media/{uploadId:guid}/chunk/{index:int}", async (Guid uploadId, int index, HttpRequest request, ClaimsPrincipal principal, SyncDb db, WorkspaceCrypto crypto, IAmazonS3 s3, IConfiguration config) => {
    if (index < 0) return Results.BadRequest(Problem("invalid_chunk")); var workspaceId = principal.WorkspaceId();
    var upload = await db.MediaUploads.SingleOrDefaultAsync(x => x.Id == uploadId && x.WorkspaceId == workspaceId && x.CompletedAt == null); if (upload is null) return Results.NotFound();
    const int chunkBytes = 5 * 1024 * 1024;
    var partCount = checked((int)((upload.ByteCount + chunkBytes - 1) / chunkBytes));
    if (index >= partCount) return Results.BadRequest(Problem("invalid_chunk"));
    var expectedBytes = checked((int)Math.Min(chunkBytes, upload.ByteCount - (long)index * chunkBytes));
    if (request.ContentLength != expectedBytes) return Results.BadRequest(Problem("invalid_chunk_size"));
    await using var input = new MemoryStream(); await request.Body.CopyToAsync(input);
    if (input.Length != expectedBytes) return Results.BadRequest(Problem("invalid_chunk_size"));
    var plain = input.ToArray(); var hash = Convert.ToHexString(SHA256.HashData(plain)).ToLowerInvariant();
    var existing = await db.MediaUploadParts.SingleOrDefaultAsync(x => x.UploadId == uploadId && x.Index == index);
    if (existing is not null) return existing.Sha256 == hash ? Results.NoContent() : Results.Conflict(Problem("chunk_changed"));
    var workspaceKey = await db.Workspaces.Where(x => x.Id == workspaceId).Select(x => x.DataKey).SingleAsync(); var encrypted = crypto.ProtectForWorkspace(workspaceKey, plain);
    var objectKey = $"uploads/{workspaceId:N}/{uploadId:N}/{index:D8}"; await using var body = new MemoryStream(encrypted);
    await s3.PutObjectAsync(new PutObjectRequest { BucketName = config["S3:Bucket"], Key = objectKey, InputStream = body, AutoCloseStream = false });
    db.MediaUploadParts.Add(new MediaUploadPart { UploadId = uploadId, Index = index, Sha256 = hash, ByteCount = plain.Length, ObjectKey = objectKey }); await db.SaveChangesAsync(); return Results.NoContent();
}).RequireAuthorization();

app.MapPost("/api/v1/media/{uploadId:guid}/complete", async (Guid uploadId, ClaimsPrincipal principal, SyncDb db, WorkspaceCrypto crypto, IAmazonS3 s3, IConfiguration config) => {
    var workspaceId = principal.WorkspaceId(); var upload = await db.MediaUploads.SingleOrDefaultAsync(x => x.Id == uploadId && x.WorkspaceId == workspaceId && x.CompletedAt == null); if (upload is null) return Results.NotFound();
    var parts = await db.MediaUploadParts.Where(x => x.UploadId == uploadId).OrderBy(x => x.Index).ToListAsync();
    if (parts.Count == 0 || parts.Select((part, index) => part.Index == index).Any(valid => !valid)) return Results.BadRequest(Problem("missing_chunks"));
    var workspaceKey = await db.Workspaces.Where(x => x.Id == workspaceId).Select(x => x.DataKey).SingleAsync(); await using var assembled = new MemoryStream();
    foreach (var part in parts) { using var response = await s3.GetObjectAsync(config["S3:Bucket"], part.ObjectKey); await using var encrypted = new MemoryStream(); await response.ResponseStream.CopyToAsync(encrypted); var plain = crypto.UnprotectForWorkspace(workspaceKey, encrypted.ToArray()); await assembled.WriteAsync(plain); }
    var bytes = assembled.ToArray(); var hash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    if (bytes.LongLength != upload.ByteCount || hash != upload.Sha256) return Results.BadRequest(Problem("media_hash_mismatch"));
    var finalKey = $"media/{workspaceId:N}/{hash}"; var finalEncrypted = crypto.ProtectForWorkspace(workspaceKey, bytes); await using var finalBody = new MemoryStream(finalEncrypted);
    await s3.PutObjectAsync(new PutObjectRequest { BucketName = config["S3:Bucket"], Key = finalKey, InputStream = finalBody, AutoCloseStream = false });
    db.MediaObjects.Add(new MediaObject { WorkspaceId = workspaceId, Sha256 = hash, ByteCount = bytes.LongLength, MimeType = upload.MimeType, ObjectKey = finalKey, CreatedAt = DateTimeOffset.UtcNow }); upload.CompletedAt = DateTimeOffset.UtcNow; await db.SaveChangesAsync();
    foreach (var part in parts) await s3.DeleteObjectAsync(config["S3:Bucket"], part.ObjectKey);
    return Results.Ok(new { sha256 = hash, bytes = bytes.LongLength });
}).RequireAuthorization();

app.MapGet("/api/v1/media/{sha256}", async (string sha256, ClaimsPrincipal principal, SyncDb db, WorkspaceCrypto crypto, IAmazonS3 s3, IConfiguration config) => {
    if (!ValidSha(sha256)) return Results.NotFound(); var workspaceId = principal.WorkspaceId(); var media = await db.MediaObjects.SingleOrDefaultAsync(x => x.WorkspaceId == workspaceId && x.Sha256 == sha256); if (media is null) return Results.NotFound();
    using var response = await s3.GetObjectAsync(config["S3:Bucket"], media.ObjectKey); await using var encrypted = new MemoryStream(); await response.ResponseStream.CopyToAsync(encrypted); var key = await db.Workspaces.Where(x => x.Id == workspaceId).Select(x => x.DataKey).SingleAsync(); var bytes = crypto.UnprotectForWorkspace(key, encrypted.ToArray());
    if (bytes.LongLength != media.ByteCount || Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant() != sha256) return Results.Problem("Stored media failed integrity verification", statusCode: 500);
    return Results.File(bytes, media.MimeType, enableRangeProcessing: false);
}).RequireAuthorization();

app.MapGet("/api/v1/devices", async (ClaimsPrincipal principal, SyncDb db) => Results.Ok(await db.Devices.Where(x => x.UserId == principal.UserId()).Select(x => new { x.Id, x.Name, x.LastSeenAt, x.RevokedAt }).ToListAsync())).RequireAuthorization();
app.MapDelete("/api/v1/devices/{id:guid}", async (Guid id, ClaimsPrincipal principal, SyncDb db) => {
    var device = await db.Devices.SingleOrDefaultAsync(x => x.Id == id && x.UserId == principal.UserId());
    if (device is null) return Results.NotFound(); device.RevokedAt = DateTimeOffset.UtcNow;
    await db.RefreshTokens.Where(x => x.DeviceId == id && x.RevokedAt == null).ExecuteUpdateAsync(x => x.SetProperty(t => t.RevokedAt, DateTimeOffset.UtcNow));
    await db.SaveChangesAsync(); return Results.NoContent();
}).RequireAuthorization();
app.MapDelete("/api/v1/account", async (ClaimsPrincipal principal, SyncDb db, UserManager<SyncUser> users, IAmazonS3 s3, IConfiguration config) => {
    var user = await users.FindByIdAsync(principal.UserId().ToString()); if (user is null) return Results.NotFound();
    var workspaceIds = await db.Workspaces.Where(x => x.OwnerId == user.Id).Select(x => x.Id).ToListAsync();
    var objectKeys = await db.MediaObjects.Where(x => workspaceIds.Contains(x.WorkspaceId)).Select(x => x.ObjectKey).ToListAsync();
    var uploadIds = await db.MediaUploads.Where(x => workspaceIds.Contains(x.WorkspaceId)).Select(x => x.Id).ToListAsync();
    objectKeys.AddRange(await db.MediaUploadParts.Where(x => uploadIds.Contains(x.UploadId)).Select(x => x.ObjectKey).ToListAsync());
    foreach (var key in objectKeys) await s3.DeleteObjectAsync(config["S3:Bucket"], key);
    db.Audits.RemoveRange(await db.Audits.Where(x => x.UserId == user.Id).ToListAsync());
    if (!string.IsNullOrWhiteSpace(user.Email))
        db.EmailOutbox.RemoveRange(await db.EmailOutbox.Where(x => x.Recipient == user.Email).ToListAsync());
    await db.Invitations.Where(x => x.UsedBy == user.Id)
        .ExecuteUpdateAsync(x => x.SetProperty(invitation => invitation.UsedBy, (Guid?)null));
    db.Workspaces.RemoveRange(await db.Workspaces.Where(x => workspaceIds.Contains(x.Id)).ToListAsync());
    await users.DeleteAsync(user); await db.SaveChangesAsync(); return Results.Accepted();
}).RequireAuthorization();

app.MapGet("/api/v1/conflicts", async (ClaimsPrincipal principal, SyncDb db, WorkspaceCrypto crypto) => {
    var workspaceId = principal.WorkspaceId(); var key = await db.Workspaces.Where(x => x.Id == workspaceId).Select(x => x.DataKey).SingleAsync();
    var rows = await db.Conflicts.Where(x => x.WorkspaceId == workspaceId && x.ResolvedAt == null).OrderBy(x => x.ServerSeq).ToListAsync();
    return Results.Ok(rows.Select(x => new { x.Id, x.EntityType, x.EntityId, x.FieldName,
        acceptedValue = JsonSerializer.Deserialize<JsonElement>(crypto.UnprotectForWorkspace(key, x.AcceptedValue)),
        candidateValue = JsonSerializer.Deserialize<JsonElement>(crypto.UnprotectForWorkspace(key, x.CandidateValue)),
        x.AcceptedOperationId, x.CandidateOperationId, x.ServerSeq, x.CreatedAt }));
}).RequireAuthorization();
app.MapPost("/api/v1/conflicts/{id:guid}/resolve", async (Guid id, ResolveConflictRequest request, ClaimsPrincipal principal, SyncDb db, WorkspaceCrypto crypto) => {
    var workspaceId = principal.WorkspaceId(); var deviceId = principal.DeviceId();
    if (request.OperationId == Guid.Empty || request.DeviceCounter <= 0 || request.BaseCursor < 0 ||
        request.Value.ValueKind == JsonValueKind.Undefined || request.Value.GetRawText().Length > 1_048_576)
        return Results.BadRequest(Problem("invalid_resolution"));
    await using var transaction = await db.Database.BeginTransactionAsync(IsolationLevel.Serializable);
    var conflict = await db.Conflicts.SingleOrDefaultAsync(x => x.Id == id && x.WorkspaceId == workspaceId && x.ResolvedAt == null);
    if (conflict is null) return Results.NotFound();
    if (await db.Operations.AnyAsync(x => x.WorkspaceId == workspaceId && x.OperationId == request.OperationId))
        return Results.Conflict(Problem("operation_id_reuse"));
    if (await db.Operations.AnyAsync(x => x.WorkspaceId == workspaceId && x.DeviceId == deviceId && x.DeviceCounter == request.DeviceCounter)) return Results.Conflict(Problem("device_counter_reuse"));
    var lastCounter = await db.Operations.Where(x => x.WorkspaceId == workspaceId && x.DeviceId == deviceId)
        .MaxAsync(x => (long?)x.DeviceCounter) ?? 0;
    if (request.DeviceCounter <= lastCounter) return Results.Conflict(Problem("device_counter_regression"));
    var workspace = await db.Workspaces.SingleAsync(x => x.Id == workspaceId);
    var state = await db.EntityStates.SingleAsync(x => x.WorkspaceId == workspaceId && x.EntityType == conflict.EntityType && x.EntityId == conflict.EntityId);
    var fields = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(crypto.UnprotectForWorkspace(workspace.DataKey, state.Fields)) ?? [];
    var versions = JsonSerializer.Deserialize<Dictionary<string, long>>(state.FieldVersionsJson) ?? [];
    var acceptedOperations = JsonSerializer.Deserialize<Dictionary<string, Guid>>(state.FieldOperationsJson) ?? [];
    var revision = ++state.Revision; fields[conflict.FieldName] = request.Value.Clone(); versions[conflict.FieldName] = revision; acceptedOperations[conflict.FieldName] = request.OperationId;
    state.Fields = crypto.ProtectForWorkspace(workspace.DataKey, JsonSerializer.SerializeToUtf8Bytes(fields)); state.FieldVersionsJson = JsonSerializer.Serialize(versions); state.FieldOperationsJson = JsonSerializer.Serialize(acceptedOperations);
    var changedFields = JsonSerializer.SerializeToElement(new Dictionary<string, JsonElement> { [conflict.FieldName] = request.Value });
    var seq = ++workspace.LastServerSeq; db.Operations.Add(new SyncOperationEntity { OperationId = request.OperationId, WorkspaceId = workspaceId, DeviceId = deviceId, DeviceCounter = request.DeviceCounter, BaseCursor = request.BaseCursor, BaseRevision = revision - 1, EntityType = conflict.EntityType, EntityId = conflict.EntityId, Action = "update", Payload = crypto.ProtectForWorkspace(workspace.DataKey, JsonSerializer.SerializeToUtf8Bytes(changedFields)), OccurredAt = DateTimeOffset.UtcNow, ServerSeq = seq });
    conflict.ResolvedAt = DateTimeOffset.UtcNow; conflict.ResolutionOperationId = request.OperationId;
    await db.SaveChangesAsync(); await transaction.CommitAsync(); return Results.NoContent();
}).RequireAuthorization();

app.Run();

static object Problem(string code, IEnumerable<string>? details = null) => new { type = "https://reviewfault.app/problems/" + code, title = code, errors = details };
static bool FixedTokenEquals(string left, string right) => CryptographicOperations.FixedTimeEquals(
    SHA256.HashData(Encoding.UTF8.GetBytes(left)), SHA256.HashData(Encoding.UTF8.GetBytes(right)));
static bool ValidOperation(SyncOperation operation) {
    ReadOnlySpan<string> entityTypes = ["chapter", "studyItem", "mathProblem", "memoryCard", "cardProfile", "tag",
        "relation", "reviewAction", "attempt", "attemptArtifact", "learningPreferences",
        "learningUnit", "learningTask", "learningRelation", "learningEvidence", "learningProfile"];
    ReadOnlySpan<string> actions = ["create", "update", "delete", "restore", "add", "remove"];
    return operation.DeviceCounter > 0 && operation.BaseCursor >= 0 && operation.BaseRevision >= 0 &&
        operation.EntityId.Length is > 0 and <= 200 && entityTypes.Contains(operation.EntityType) &&
        actions.Contains(operation.Action) && operation.ChangedFields.ValueKind == JsonValueKind.Object &&
        operation.ChangedFields.GetRawText().Length <= 1_048_576 &&
        operation.OccurredAt is > 0 and <= 253_402_300_799;
}
static string AccountActionPage(bool resetPassword) => resetPassword ? """
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>重置 ReviewFault 密码</title><style>body{font:16px system-ui;max-width:32rem;margin:10vh auto;padding:1.5rem}input,button{box-sizing:border-box;width:100%;padding:.8rem;margin:.5rem 0}#status{white-space:pre-wrap}</style>
<h1>重置密码</h1><form id="form"><label>新密码（至少 12 位）<input id="password" type="password" minlength="12" required autocomplete="new-password"></label><button>确认重置</button></form><p id="status"></p>
<script>const q=new URLSearchParams(location.hash.slice(1)),u=q.get('userId'),t=q.get('token'),s=document.querySelector('#status');history.replaceState(null,'',location.pathname);if(!u||!t){document.querySelector('#form').hidden=true;s.textContent='链接无效或不完整。'}document.querySelector('#form').addEventListener('submit',async e=>{e.preventDefault();const r=await fetch('/api/v1/auth/reset-password',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({userId:u,token:t,newPassword:document.querySelector('#password').value})});s.textContent=r.ok?'密码已重置，可以返回 ReviewFault 登录。':'重置失败；链接可能已失效。';});</script></html>
""" : """
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>验证 ReviewFault 邮箱</title><style>body{font:16px system-ui;max-width:32rem;margin:10vh auto;padding:1.5rem}button{box-sizing:border-box;width:100%;padding:.8rem;margin:.5rem 0}#status{white-space:pre-wrap}</style>
<h1>验证邮箱</h1><button id="verify">确认验证</button><p id="status"></p>
<script>const q=new URLSearchParams(location.hash.slice(1)),u=q.get('userId'),t=q.get('token'),b=document.querySelector('#verify'),s=document.querySelector('#status');history.replaceState(null,'',location.pathname);if(!u||!t){b.hidden=true;s.textContent='链接无效或不完整。'}b.addEventListener('click',async()=>{b.disabled=true;const r=await fetch('/api/v1/auth/verify-email',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({userId:u,token:t})});s.textContent=r.ok?'邮箱已验证，可以返回 ReviewFault 登录。':'验证失败；链接可能已失效。';});</script></html>
""";
static string TokenHash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
static bool ValidSha(string value) => value.Length == 64 && value.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f');
static (TokenResponse Response, RefreshToken Stored) IssueTokens(SyncUser user, Device device, Guid workspaceId, IConfiguration config) {
    var refresh = Convert.ToBase64String(RandomNumberGenerator.GetBytes(48));
    var expires = DateTimeOffset.UtcNow.AddDays(30);
    var claims = new[] { new Claim(JwtRegisteredClaimNames.Sub, user.Id.ToString()), new Claim("device_id", device.Id.ToString()), new Claim("workspace_id", workspaceId.ToString()) };
    var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(config["Auth:JwtKey"]!));
    var token = new JwtSecurityToken(claims: claims, expires: DateTime.UtcNow.AddMinutes(15), signingCredentials: new SigningCredentials(key, SecurityAlgorithms.HmacSha256));
    return (new TokenResponse(new JwtSecurityTokenHandler().WriteToken(token), 900, refresh, 2592000, workspaceId, user.Id), new RefreshToken { DeviceId = device.Id, TokenHash = TokenHash(refresh), ExpiresAt = expires });
}

public sealed record RegisterRequest(string Email, string Password, string InvitationCode);
public sealed record LoginRequest(string Email, string Password, Guid DeviceId, string DeviceName);
public sealed record TokenRequest(Guid UserId, string Token);
public sealed record RefreshRequest(Guid DeviceId, string RefreshToken);
public sealed record PasswordResetRequest(string Email);
public sealed record ResetPasswordRequest(Guid UserId, string Token, string NewPassword);
public sealed record TokenResponse(string AccessToken, int ExpiresIn, string RefreshToken, int RefreshExpiresIn, Guid WorkspaceId, Guid AccountId);
public sealed record SyncOperation(Guid OperationId, Guid DeviceId, long DeviceCounter, long BaseCursor, long BaseRevision, string EntityType, string EntityId, string Action, JsonElement ChangedFields, long OccurredAt);
public sealed record PushRequest(List<SyncOperation> Operations);
public sealed record ResolveConflictRequest(Guid OperationId, long DeviceCounter, long BaseCursor, JsonElement Value);
public sealed record MediaPrepareItem(string Sha256, long ByteCount, string MimeType);
public sealed record MediaPrepareRequest(List<MediaPrepareItem> Objects);
public sealed record MailToken(Guid UserId, string Token);

public sealed class WorkspaceCrypto {
    private readonly byte[] masterKey;
    public WorkspaceCrypto(byte[] masterKey) { if (masterKey.Length != 32) throw new InvalidOperationException("Crypto master key must be 32 bytes"); this.masterKey = masterKey; }
    public string WrapKey(byte[] key) => Convert.ToBase64String(Protect(masterKey, key));
    public byte[] ProtectSystem(byte[] plain) => Protect(masterKey, plain);
    public byte[] UnprotectSystem(byte[] packed) => Unprotect(masterKey, packed);
    public byte[] ProtectForWorkspace(string wrappedKey, byte[] plain) => Protect(Unprotect(masterKey, Convert.FromBase64String(wrappedKey)), plain);
    public byte[] UnprotectForWorkspace(string wrappedKey, byte[] packed) => Unprotect(Unprotect(masterKey, Convert.FromBase64String(wrappedKey)), packed);
    private static byte[] Protect(byte[] key, byte[] plain) { using var aes = new AesGcm(key, 16); var nonce = RandomNumberGenerator.GetBytes(12); var tag = new byte[16]; var cipher = new byte[plain.Length]; aes.Encrypt(nonce, plain, cipher, tag); return [.. nonce, .. tag, .. cipher]; }
    private static byte[] Unprotect(byte[] key, byte[] packed) { if (packed.Length < 28) throw new CryptographicException("Invalid encrypted payload"); using var aes = new AesGcm(key, 16); var nonce = packed[..12]; var tag = packed[12..28]; var cipher = packed[28..]; var plain = new byte[cipher.Length]; aes.Decrypt(nonce, cipher, tag, plain); return plain; }
}

public sealed class SmtpOutboxWorker(IServiceScopeFactory scopes, IConfiguration config,
    WorkspaceCrypto crypto, ILogger<SmtpOutboxWorker> logger) : BackgroundService {
    protected override async Task ExecuteAsync(CancellationToken stoppingToken) {
        while (!stoppingToken.IsCancellationRequested) {
            try { await DeliverBatch(stoppingToken); }
            catch (Exception error) { logger.LogError(error, "SMTP outbox batch failed"); }
            await Task.Delay(TimeSpan.FromSeconds(15), stoppingToken);
        }
    }
    private async Task DeliverBatch(CancellationToken cancellationToken) {
        var host = config["Smtp:Host"]; if (string.IsNullOrWhiteSpace(host)) return;
        using var scope = scopes.CreateScope(); var db = scope.ServiceProvider.GetRequiredService<SyncDb>();
        var now = DateTimeOffset.UtcNow; var messages = await db.EmailOutbox.Where(x => x.SentAt == null && x.NextAttemptAt <= now).OrderBy(x => x.Id).Take(20).ToListAsync(cancellationToken);
        foreach (var queued in messages) {
            try {
                var token = JsonSerializer.Deserialize<MailToken>(crypto.UnprotectSystem(queued.EncryptedPayload))!;
                var baseUrl = config["Smtp:ActionBaseUrl"] ?? "https://sync.reviewfault.app/account";
                // URL fragments stay in the browser and therefore cannot leak through
                // Caddy/application access logs. The account page turns them into the
                // explicit POST body for verify-email/reset-password.
                var link = $"{baseUrl}/{queued.Kind}#userId={token.UserId}&token={Uri.EscapeDataString(token.Token)}";
                using var message = new MailMessage(config["Smtp:From"]!, queued.Recipient,
                    queued.Kind == "verify_email" ? "Verify your ReviewFault email" : "Reset your ReviewFault password", link);
                using var smtp = new SmtpClient(host, config.GetValue("Smtp:Port", 587)) {
                    EnableSsl = true,
                    Credentials = new NetworkCredential(config["Smtp:Username"], config["Smtp:Password"]),
                };
                await smtp.SendMailAsync(message, cancellationToken); queued.SentAt = DateTimeOffset.UtcNow;
            } catch (Exception error) {
                queued.Attempts++; queued.NextAttemptAt = DateTimeOffset.UtcNow.AddSeconds(Math.Min(3600, 30 * Math.Pow(2, Math.Min(queued.Attempts, 7))));
                logger.LogWarning(error, "SMTP delivery failed for outbox id {OutboxId}", queued.Id);
            }
        }
        await db.SaveChangesAsync(cancellationToken);
    }
}
