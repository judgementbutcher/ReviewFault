using ReviewFault.Data;
using ReviewFault.Services;
using Windows.Storage;

namespace ReviewFault.ViewModels;

public enum AppDestination { Today, Insights, Library, Add, Settings, Trash, Review }

public sealed class AppViewModel
{
    public AppRepository Repository { get; } = new();
    public AppDestination Destination { get; private set; } = AppDestination.Today;
    public event Action<AppDestination>? DestinationChanged;
    private readonly SecureTokenStore tokenStore = new();
    private readonly SemaphoreSlim syncLock = new(1, 1);
    private AccountTokens? tokens;
    private Timer? syncTimer;
    public string SyncEndpoint { get; private set; } = "https://sync.reviewfault.app";
    public string? AccountId => tokens?.AccountId;
    public long? LastSyncedAt { get; private set; }
    public int PendingSyncCount { get; private set; }
    public bool SyncInProgress => syncLock.CurrentCount == 0;

    public async Task InitializeAsync()
    {
        await Repository.InitializeAsync();
        var settings = ApplicationData.Current.LocalSettings.Values;
        SyncEndpoint = settings["sync.endpoint"] as string ?? SyncEndpoint;
        LastSyncedAt = settings["sync.last"] as long?;
        tokens = tokenStore.Load();
        PendingSyncCount = (await Repository.SyncIdentityAsync()).PendingCount;
        if (tokens is not null) _ = TryBackgroundSyncAsync();
        syncTimer = new Timer(_ => {
            if (tokens is not null) _ = TryBackgroundSyncAsync();
        }, null, TimeSpan.FromMinutes(5), TimeSpan.FromMinutes(5));
    }

    public async Task RegisterAsync(string endpoint, string email, string password, string invitationCode)
    {
        SaveEndpoint(endpoint);
        using var client = new SyncClient(SyncEndpoint);
        await client.RegisterAsync(email, password, invitationCode);
    }

    public async Task LoginAsync(string endpoint, string email, string password)
    {
        SaveEndpoint(endpoint);
        var identity = await Repository.SyncIdentityAsync();
        using var client = new SyncClient(SyncEndpoint);
        var session = await client.LoginAsync(
            email, password, identity.DeviceId, "Windows device");
        await Repository.BindAccountAsync(session.AccountId, session.WorkspaceId);
        SaveSession(session);
        await SyncNowAsync();
    }

    public async Task SyncNowAsync()
    {
        if (tokens is null || !await syncLock.WaitAsync(0)) return;
        try
        {
            using var client = new SyncClient(SyncEndpoint); var identity = await Repository.SyncIdentityAsync();
            if (tokens.AccessExpiresAt <= DateTimeOffset.UtcNow.ToUnixTimeSeconds() + 60)
            {
                var refreshed = await client.RefreshAsync(identity.DeviceId, tokens.RefreshToken);
                if (refreshed.AccountId != tokens.AccountId || refreshed.WorkspaceId != tokens.WorkspaceId)
                    throw new InvalidDataException("刷新令牌返回了不同账号");
                SaveSession(refreshed);
            }
            await client.UploadMediaAsync(tokens.AccessToken, await Repository.MediaForSyncAsync());
            while (true)
            {
                var pending = await Repository.PendingSyncOperationsAsync();
                if (pending.GetArrayLength() == 0) break;
                var acknowledged = await client.PushAsync(tokens.AccessToken, pending);
                if (acknowledged.Count == 0) throw new InvalidOperationException("服务端未确认任何本地操作");
                await Repository.AcknowledgeSyncOperationsAsync(acknowledged);
            }
            long cursor = (await Repository.SyncIdentityAsync()).Cursor;
            PullResult pulled;
            do
            {
                pulled = await client.PullAsync(tokens.AccessToken, cursor);
                await Repository.ApplyPulledOperationsAsync(tokens.WorkspaceId, pulled.Operations, pulled.Cursor);
                cursor = pulled.Cursor;
            } while (pulled.Operations.Count == 500);
            foreach (var media in await Repository.MissingMediaAsync())
                await AppRepository.SaveDownloadedMediaAsync(media,
                    await client.DownloadMediaAsync(tokens.AccessToken, media.Sha256));
            LastSyncedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            ApplicationData.Current.LocalSettings.Values["sync.last"] = LastSyncedAt.Value;
            PendingSyncCount = (await Repository.SyncIdentityAsync()).PendingCount;
        }
        finally { syncLock.Release(); }
    }

    public async Task LogoutAsync()
    {
        if (tokens is not null)
        {
            try { using var client = new SyncClient(SyncEndpoint); await client.LogoutAsync(tokens.AccessToken); }
            catch (Exception error) when (error is HttpRequestException or InvalidOperationException) { }
        }
        tokenStore.Clear(); tokens = null;
    }

    private async Task TryBackgroundSyncAsync()
    {
        try { await SyncNowAsync(); }
        catch (Exception error) when (error is HttpRequestException or InvalidOperationException or IOException) { }
    }

    private void SaveEndpoint(string endpoint)
    {
        using var client = new SyncClient(endpoint);
        SyncEndpoint = endpoint.Trim().TrimEnd('/');
        ApplicationData.Current.LocalSettings.Values["sync.endpoint"] = SyncEndpoint;
    }

    private void SaveSession(AuthSession session)
    {
        tokens = new AccountTokens(session.AccountId, session.WorkspaceId, session.AccessToken,
            session.AccessExpiresAt, session.RefreshToken);
        tokenStore.Save(tokens);
    }

    public void Navigate(AppDestination destination)
    {
        Destination = destination;
        DestinationChanged?.Invoke(destination);
    }
}
