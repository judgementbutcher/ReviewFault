using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;

public sealed class SyncUser : IdentityUser<Guid> { }
public sealed class Workspace { public Guid Id { get; set; } = Guid.NewGuid(); public Guid OwnerId { get; set; } public string DataKey { get; set; } = ""; public long LastServerSeq { get; set; } }
public sealed class WorkspaceMember { public Guid WorkspaceId { get; set; } public Guid UserId { get; set; } public Workspace Workspace { get; set; } = null!; }
public sealed class Device { public Guid Id { get; set; } public Guid UserId { get; set; } public Guid WorkspaceId { get; set; } public string Name { get; set; } = ""; public DateTimeOffset? LastSeenAt { get; set; } public DateTimeOffset? RevokedAt { get; set; } public SyncUser User { get; set; } = null!; }
public sealed class RefreshToken { public long Id { get; set; } public Guid DeviceId { get; set; } public string TokenHash { get; set; } = ""; public DateTimeOffset ExpiresAt { get; set; } public DateTimeOffset? RevokedAt { get; set; } public string? ReplacedBy { get; set; } public Device Device { get; set; } = null!; }
public sealed class Invitation { public long Id { get; set; } public string Code { get; set; } = ""; public DateTimeOffset ExpiresAt { get; set; } public DateTimeOffset? UsedAt { get; set; } public Guid? UsedBy { get; set; } }
public sealed class AuditEvent { public long Id { get; set; } public Guid UserId { get; set; } public string Kind { get; set; } = ""; public DateTimeOffset CreatedAt { get; set; } }
public sealed class EmailOutbox { public long Id { get; set; } public string Recipient { get; set; } = ""; public string Kind { get; set; } = ""; public byte[] EncryptedPayload { get; set; } = []; public DateTimeOffset CreatedAt { get; set; } public DateTimeOffset NextAttemptAt { get; set; } public int Attempts { get; set; } public DateTimeOffset? SentAt { get; set; } }
public sealed class MediaObject { public Guid WorkspaceId { get; set; } public string Sha256 { get; set; } = ""; public long ByteCount { get; set; } public string MimeType { get; set; } = "application/octet-stream"; public string ObjectKey { get; set; } = ""; public DateTimeOffset CreatedAt { get; set; } }
public sealed class MediaUpload { public Guid Id { get; set; } = Guid.NewGuid(); public Guid WorkspaceId { get; set; } public string Sha256 { get; set; } = ""; public long ByteCount { get; set; } public string MimeType { get; set; } = "application/octet-stream"; public DateTimeOffset CreatedAt { get; set; } public DateTimeOffset? CompletedAt { get; set; } }
public sealed class MediaUploadPart { public Guid UploadId { get; set; } public int Index { get; set; } public string Sha256 { get; set; } = ""; public long ByteCount { get; set; } public string ObjectKey { get; set; } = ""; }
public sealed class SyncOperationEntity { public Guid OperationId { get; set; } public Guid WorkspaceId { get; set; } public Guid DeviceId { get; set; } public long DeviceCounter { get; set; } public long BaseCursor { get; set; } public long BaseRevision { get; set; } public string EntityType { get; set; } = ""; public string EntityId { get; set; } = ""; public string Action { get; set; } = ""; public byte[] Payload { get; set; } = []; public DateTimeOffset OccurredAt { get; set; } public long ServerSeq { get; set; } }
public sealed class EntityState { public Guid WorkspaceId { get; set; } public string EntityType { get; set; } = ""; public string EntityId { get; set; } = ""; public long Revision { get; set; } public bool Deleted { get; set; } public byte[] Fields { get; set; } = []; public string FieldVersionsJson { get; set; } = "{}"; public string FieldOperationsJson { get; set; } = "{}"; }
public sealed class SyncConflictEntity { public Guid Id { get; set; } = Guid.NewGuid(); public Guid WorkspaceId { get; set; } public string EntityType { get; set; } = ""; public string EntityId { get; set; } = ""; public string FieldName { get; set; } = ""; public byte[] AcceptedValue { get; set; } = []; public byte[] CandidateValue { get; set; } = []; public Guid AcceptedOperationId { get; set; } public Guid CandidateOperationId { get; set; } public long ServerSeq { get; set; } public DateTimeOffset CreatedAt { get; set; } public DateTimeOffset? ResolvedAt { get; set; } public Guid? ResolutionOperationId { get; set; } }

public sealed class SyncDb(DbContextOptions<SyncDb> options) : IdentityDbContext<SyncUser, IdentityRole<Guid>, Guid>(options) {
    public DbSet<Workspace> Workspaces => Set<Workspace>();
    public DbSet<WorkspaceMember> Members => Set<WorkspaceMember>();
    public DbSet<Device> Devices => Set<Device>();
    public DbSet<RefreshToken> RefreshTokens => Set<RefreshToken>();
    public DbSet<Invitation> Invitations => Set<Invitation>();
    public DbSet<AuditEvent> Audits => Set<AuditEvent>();
    public DbSet<EmailOutbox> EmailOutbox => Set<EmailOutbox>();
    public DbSet<MediaObject> MediaObjects => Set<MediaObject>();
    public DbSet<MediaUpload> MediaUploads => Set<MediaUpload>();
    public DbSet<MediaUploadPart> MediaUploadParts => Set<MediaUploadPart>();
    public DbSet<SyncOperationEntity> Operations => Set<SyncOperationEntity>();
    public DbSet<EntityState> EntityStates => Set<EntityState>();
    public DbSet<SyncConflictEntity> Conflicts => Set<SyncConflictEntity>();
    protected override void OnModelCreating(ModelBuilder model) {
        base.OnModelCreating(model);
        model.Entity<WorkspaceMember>().HasKey(x => new { x.WorkspaceId, x.UserId });
        model.Entity<WorkspaceMember>().HasOne(x => x.Workspace).WithMany().HasForeignKey(x => x.WorkspaceId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<WorkspaceMember>().HasOne<SyncUser>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<Workspace>().HasOne<SyncUser>().WithMany().HasForeignKey(x => x.OwnerId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<Device>().HasOne(x => x.User).WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<Device>().HasOne<Workspace>().WithMany().HasForeignKey(x => x.WorkspaceId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<RefreshToken>().HasOne(x => x.Device).WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<SyncOperationEntity>().HasKey(x => new { x.WorkspaceId, x.OperationId });
        model.Entity<SyncOperationEntity>().HasIndex(x => new { x.WorkspaceId, x.ServerSeq }).IsUnique();
        model.Entity<SyncOperationEntity>().HasIndex(x => new { x.WorkspaceId, x.DeviceId, x.DeviceCounter }).IsUnique();
        model.Entity<SyncOperationEntity>().HasOne<Workspace>().WithMany().HasForeignKey(x => x.WorkspaceId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<EntityState>().HasKey(x => new { x.WorkspaceId, x.EntityType, x.EntityId });
        model.Entity<EntityState>().HasOne<Workspace>().WithMany().HasForeignKey(x => x.WorkspaceId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<SyncConflictEntity>().HasOne<Workspace>().WithMany().HasForeignKey(x => x.WorkspaceId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<MediaObject>().HasKey(x => new { x.WorkspaceId, x.Sha256 });
        model.Entity<MediaObject>().HasOne<Workspace>().WithMany().HasForeignKey(x => x.WorkspaceId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<MediaUpload>().HasOne<Workspace>().WithMany().HasForeignKey(x => x.WorkspaceId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<MediaUploadPart>().HasKey(x => new { x.UploadId, x.Index });
        model.Entity<MediaUploadPart>().HasOne<MediaUpload>().WithMany().HasForeignKey(x => x.UploadId).OnDelete(DeleteBehavior.Cascade);
        model.Entity<Invitation>().HasIndex(x => x.Code).IsUnique();
        model.Entity<RefreshToken>().HasIndex(x => new { x.DeviceId, x.TokenHash }).IsUnique();
    }
}

public static class ClaimsPrincipalExtensions {
    public static Guid WorkspaceId(this System.Security.Claims.ClaimsPrincipal principal) => Guid.Parse(principal.FindFirst("workspace_id")!.Value);
    public static Guid DeviceId(this System.Security.Claims.ClaimsPrincipal principal) => Guid.Parse(principal.FindFirst("device_id")!.Value);
    public static Guid UserId(this System.Security.Claims.ClaimsPrincipal principal) => Guid.Parse(principal.FindFirst(System.IdentityModel.Tokens.Jwt.JwtRegisteredClaimNames.Sub)!.Value);
}
