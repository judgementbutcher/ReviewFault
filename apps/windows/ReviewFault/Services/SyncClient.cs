using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using ReviewFault.Data;

namespace ReviewFault.Services;

public sealed record AuthSession(
    string AccountId, string WorkspaceId, string AccessToken,
    long AccessExpiresAt, string RefreshToken);
public sealed record PullResult(long Cursor, IReadOnlyList<PulledOperation> Operations);

public sealed class SyncClient : IDisposable
{
    private readonly HttpClient http;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public SyncClient(string endpoint)
    {
        var normalized = endpoint.Trim().TrimEnd('/');
        if (!Uri.TryCreate(normalized, UriKind.Absolute, out var uri) ||
            !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) ||
            !string.IsNullOrEmpty(uri.Fragment) || uri.AbsolutePath != "/" ||
            (uri.Scheme != Uri.UriSchemeHttps && !(uri.Scheme == Uri.UriSchemeHttp &&
              (uri.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase) ||
               uri.Host == "127.0.0.1" || uri.Host == "[::1]"))))
            throw new ArgumentException("同步地址必须使用 HTTPS（localhost 除外）");
        http = new HttpClient { BaseAddress = uri, Timeout = TimeSpan.FromSeconds(30) };
    }

    public async Task RegisterAsync(string email, string password, string invitationCode) =>
        await SendAsync(HttpMethod.Post, "/api/v1/auth/register",
            new { email = email.Trim(), password, invitationCode = invitationCode.Trim() }, null,
            [System.Net.HttpStatusCode.Accepted]);

    public async Task<AuthSession> LoginAsync(
        string email, string password, string deviceId, string deviceName) =>
        Session(await SendAsync(HttpMethod.Post, "/api/v1/auth/login",
            new { email = email.Trim(), password, deviceId, deviceName }));

    public async Task<AuthSession> RefreshAsync(string deviceId, string refreshToken) =>
        Session(await SendAsync(HttpMethod.Post, "/api/v1/auth/refresh",
            new { deviceId, refreshToken }));

    public async Task LogoutAsync(string accessToken) =>
        await SendAsync(HttpMethod.Post, "/api/v1/auth/logout", new { }, accessToken,
            [System.Net.HttpStatusCode.NoContent]);

    public async Task<IReadOnlySet<string>> PushAsync(string accessToken, JsonElement operations)
    {
        if (operations.GetArrayLength() == 0) return new HashSet<string>();
        var result = await SendAsync(HttpMethod.Post, "/api/v1/sync/push",
            new { operations }, accessToken);
        return result.GetProperty("acknowledgements").EnumerateArray()
            .Select(value => value.GetProperty("operationId").GetString()!)
            .ToHashSet(StringComparer.Ordinal);
    }

    public async Task<PullResult> PullAsync(string accessToken, long cursor)
    {
        var result = await SendAsync(HttpMethod.Get,
            $"/api/v1/sync/pull?cursor={cursor}&limit=500", null, accessToken);
        var operations = result.GetProperty("operations").EnumerateArray().Select(value =>
            new PulledOperation(
                value.GetProperty("operationId").GetString()!, value.GetProperty("serverSeq").GetInt64(),
                value.GetProperty("deviceId").GetString()!, value.GetProperty("deviceCounter").GetInt64(),
                value.GetProperty("entityType").GetString()!, value.GetProperty("entityId").GetString()!,
                value.GetProperty("action").GetString()!, value.GetProperty("changedFields").Clone(),
                value.GetProperty("occurredAt").GetInt64())).ToArray();
        return new PullResult(result.GetProperty("cursor").GetInt64(), operations);
    }

    public async Task UploadMediaAsync(string accessToken, IReadOnlyList<SyncMediaObject> media)
    {
        foreach (var batch in media.Chunk(100))
        {
            var response = await SendAsync(HttpMethod.Post, "/api/v1/media/prepare", new {
                objects = batch.Select(value => new { sha256 = value.Sha256, byteCount = value.ByteCount,
                    mimeType = value.MimeType }),
            }, accessToken);
            var results = response.GetProperty("objects").EnumerateArray().ToArray();
            for (var index = 0; index < results.Length; index++)
            {
                if (results[index].GetProperty("present").GetBoolean()) continue;
                var uploadId = results[index].GetProperty("uploadId").GetString()!;
                await using var input = File.OpenRead(batch[index].FilePath); var part = 0;
                while (input.Position < input.Length)
                {
                    var bytes = new byte[checked((int)Math.Min(5 * 1024 * 1024L, input.Length - input.Position))];
                    await input.ReadExactlyAsync(bytes);
                    using var request = new HttpRequestMessage(HttpMethod.Put,
                        $"/api/v1/media/{uploadId}/chunk/{part++}");
                    request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
                    request.Content = new ByteArrayContent(bytes);
                    request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
                    using var chunkResponse = await http.SendAsync(request);
                    if (chunkResponse.StatusCode != System.Net.HttpStatusCode.NoContent)
                        throw new InvalidOperationException($"媒体分片上传失败：HTTP {(int)chunkResponse.StatusCode}");
                }
                await SendAsync(HttpMethod.Post, $"/api/v1/media/{uploadId}/complete", new { }, accessToken);
            }
        }
    }

    public async Task<byte[]> DownloadMediaAsync(string accessToken, string sha256)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, $"/api/v1/media/{sha256}");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
        using var response = await http.SendAsync(request);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"媒体下载返回 HTTP {(int)response.StatusCode}");
        return await response.Content.ReadAsByteArrayAsync();
    }

    private static AuthSession Session(JsonElement value)
    {
        var access = value.GetProperty("accessToken").GetString()!;
        var payload = access.Split('.').ElementAtOrDefault(1)
            ?? throw new InvalidDataException("服务端返回了无效 access token");
        payload = payload.Replace('-', '+').Replace('_', '/').PadRight((payload.Length + 3) / 4 * 4, '=');
        using var claims = JsonDocument.Parse(Convert.FromBase64String(payload));
        var accountId = value.TryGetProperty("accountId", out var account)
            ? account.GetString() : claims.RootElement.TryGetProperty("sub", out var subject)
                ? subject.GetString() : null;
        if (string.IsNullOrWhiteSpace(accountId)) throw new InvalidDataException("access token 缺少账号标识");
        return new AuthSession(accountId, value.GetProperty("workspaceId").GetString()!, access,
            DateTimeOffset.UtcNow.ToUnixTimeSeconds() + value.GetProperty("expiresIn").GetInt64(),
            value.GetProperty("refreshToken").GetString()!);
    }

    private async Task<JsonElement> SendAsync(
        HttpMethod method, string path, object? body = null, string? token = null,
        IReadOnlyCollection<System.Net.HttpStatusCode>? expected = null)
    {
        using var request = new HttpRequestMessage(method, path);
        request.Headers.Accept.ParseAdd("application/json, application/problem+json");
        if (token is not null) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        if (body is not null && method != HttpMethod.Get)
            request.Content = new StringContent(JsonSerializer.Serialize(body, JsonOptions), Encoding.UTF8, "application/json");
        using var response = await http.SendAsync(request);
        var text = await response.Content.ReadAsStringAsync();
        var accepted = expected?.Contains(response.StatusCode) ?? response.IsSuccessStatusCode;
        if (!accepted)
        {
            var title = "";
            try { title = JsonDocument.Parse(text).RootElement.GetProperty("title").GetString() ?? ""; }
            catch (JsonException) { }
            throw new InvalidOperationException(string.IsNullOrWhiteSpace(title)
                ? $"同步服务返回 HTTP {(int)response.StatusCode}" : title);
        }
        if (string.IsNullOrWhiteSpace(text)) return JsonSerializer.SerializeToElement(new { });
        using var document = JsonDocument.Parse(text);
        return document.RootElement.Clone();
    }

    public void Dispose() => http.Dispose();
}
