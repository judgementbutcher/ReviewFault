import { UIAbilityContext } from '@kit.AbilityKit';
import { LocalDatabase } from '../data/LocalDatabase';
import { AccountTokens, SecureTokenStore } from './SecureTokenStore';
import { AuthSession, SyncClient } from './SyncClient';

export interface SyncState {
  endpoint: string; accountId: string; pendingCount: number;
  lastSyncedAt: number; inProgress: boolean;
}

export class SyncCoordinator {
  private readonly tokenStore: SecureTokenStore;
  private tokens?: AccountTokens;
  private timer: number = 0;
  readonly state: SyncState = { endpoint: 'https://sync.reviewfault.app', accountId: '',
    pendingCount: 0, lastSyncedAt: 0, inProgress: false };

  constructor(private readonly context: UIAbilityContext, private readonly database: LocalDatabase) {
    this.tokenStore = new SecureTokenStore(context);
  }

  async initialize(): Promise<void> {
    this.tokens = await this.tokenStore.load(); this.state.accountId = this.tokens?.accountId ?? '';
    this.state.pendingCount = (await this.database.syncIdentity()).pendingCount;
    if (this.tokens) await this.sync();
    this.timer = setInterval(() => { if (this.tokens) this.sync().catch(() => {}); }, 5 * 60 * 1000);
  }
  close(): void { if (this.timer !== 0) clearInterval(this.timer); }
  async register(endpoint: string, email: string, password: string, invitation: string): Promise<void> {
    this.setEndpoint(endpoint); await new SyncClient(this.state.endpoint).register(email, password, invitation);
  }
  async login(endpoint: string, email: string, password: string): Promise<void> {
    this.setEndpoint(endpoint); const identity = await this.database.syncIdentity();
    const session = await new SyncClient(this.state.endpoint).login(email, password, identity.deviceId, 'HarmonyOS');
    await this.database.bindAccount(session.accountId, session.workspaceId); await this.save(session); await this.sync();
  }
  async logout(): Promise<void> {
    if (this.tokens) { try { await new SyncClient(this.state.endpoint).logout(this.tokens.accessToken); } catch (_) {} }
    await this.tokenStore.clear(); this.tokens = undefined; this.state.accountId = '';
  }
  async sync(): Promise<void> {
    if (!this.tokens || this.state.inProgress) return; this.state.inProgress = true;
    try {
      const client = new SyncClient(this.state.endpoint); const identity = await this.database.syncIdentity();
      if (this.tokens.accessExpiresAt <= Math.floor(Date.now() / 1000) + 60) {
        const refreshed = await client.refresh(identity.deviceId, this.tokens.refreshToken);
        if (refreshed.accountId !== this.tokens.accountId || refreshed.workspaceId !== this.tokens.workspaceId)
          throw new Error('刷新令牌返回了不同账号');
        await this.save(refreshed);
      }
      let pending = await this.database.pendingOperations();
      while (pending.length > 0) {
        const acknowledged = await client.push(this.tokens.accessToken, pending);
        if (acknowledged.length === 0) throw new Error('服务端未确认任何本地操作');
        await this.database.acknowledge(acknowledged); pending = await this.database.pendingOperations();
      }
      let cursor = (await this.database.syncIdentity()).cursor; let count = 0;
      do { const pulled = await client.pull(this.tokens.accessToken, cursor);
        await this.database.applyPulled(this.tokens.workspaceId, pulled.operations, pulled.cursor);
        cursor = pulled.cursor; count = pulled.operations.length;
      } while (count === 500);
      this.state.pendingCount = (await this.database.syncIdentity()).pendingCount;
      this.state.lastSyncedAt = Math.floor(Date.now() / 1000);
    } finally { this.state.inProgress = false; }
  }
  private setEndpoint(value: string): void {
    const normalized = value.trim().replace(/\/$/, ''); new SyncClient(normalized); this.state.endpoint = normalized;
  }
  private async save(value: AuthSession): Promise<void> {
    this.tokens = value; await this.tokenStore.save(value); this.state.accountId = value.accountId;
  }
}
