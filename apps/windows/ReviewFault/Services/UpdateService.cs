using System.Net.Http;
using System.Text.Json;

namespace ReviewFault.Services;

public sealed record UpdateCheckResult(bool IsAvailable, string CurrentVersion, string LatestVersion,
    Uri ReleasePage);

public sealed class UpdateService
{
    private const string Repository = "judgementbutcher/ReviewFault";
    private const string ReleasesPage = "https://github.com/judgementbutcher/ReviewFault/releases";

    public async Task<UpdateCheckResult> CheckAsync(string currentVersion, CancellationToken cancellationToken = default)
    {
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(12) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("ReviewFault/" + currentVersion);
        using var response = await client.GetAsync(
            "https://api.github.com/repos/" + Repository + "/releases/latest", cancellationToken);
        response.EnsureSuccessStatusCode();
        using var document = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(cancellationToken));
        var root = document.RootElement;
        var latest = root.GetProperty("tag_name").GetString() ?? throw new InvalidDataException("发布版本缺失");
        var releasePage = root.TryGetProperty("html_url", out var page)
            ? page.GetString() : null;
        var uri = SafeReleaseUri(releasePage) ?? new Uri(ReleasesPage);
        return new UpdateCheckResult(
            IsNewer(latest, currentVersion), currentVersion, latest.TrimStart('v', 'V'), uri);
    }

    private static Uri? SafeReleaseUri(string? value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps ||
            uri.Host != "github.com" || !uri.AbsolutePath.StartsWith("/judgementbutcher/ReviewFault/releases/", StringComparison.Ordinal))
            return null;
        return uri;
    }

    private static bool IsNewer(string candidate, string current) =>
        Version.TryParse(candidate.TrimStart('v', 'V'), out var available) &&
        Version.TryParse(current.TrimStart('v', 'V'), out var installed) && available.CompareTo(installed) > 0;
}
