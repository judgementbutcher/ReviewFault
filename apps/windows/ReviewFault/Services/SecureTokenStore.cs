using System.Text.Json;
using Windows.Security.Credentials;

namespace ReviewFault.Services;

public sealed record AccountTokens(
    string AccountId, string WorkspaceId, string AccessToken,
    long AccessExpiresAt, string RefreshToken);

public sealed class SecureTokenStore
{
    private const string Resource = "ReviewFault.Sync.v1";
    private readonly PasswordVault vault = new();

    public void Save(AccountTokens tokens)
    {
        Clear();
        vault.Add(new PasswordCredential(Resource, tokens.AccountId,
            JsonSerializer.Serialize(tokens)));
    }

    public AccountTokens? Load()
    {
        try
        {
            var credential = vault.FindAllByResource(Resource).SingleOrDefault();
            if (credential is null) return null;
            credential.RetrievePassword();
            return JsonSerializer.Deserialize<AccountTokens>(credential.Password);
        }
        catch { return null; }
    }

    public void Clear()
    {
        try
        {
            foreach (var credential in vault.FindAllByResource(Resource)) vault.Remove(credential);
        }
        catch { }
    }
}
