import { http } from '@kit.NetworkKit';
import { LocalSyncOperation, PulledOperation } from '../data/LocalDatabase';

export interface AuthSession {
  accountId: string; workspaceId: string; accessToken: string;
  accessExpiresAt: number; refreshToken: string;
}
export interface PullResult { cursor: number; operations: PulledOperation[]; }

interface TokenResponse {
  accountId: string; workspaceId: string; accessToken: string;
  expiresIn: number; refreshToken: string;
}
interface PushAcknowledgement { operationId: string; serverSeq: number; }
interface PushResponse { acknowledgements: PushAcknowledgement[]; cursor: number; }

export class SyncClient {
  private readonly baseUrl: string;
  constructor(endpoint: string) {
    this.baseUrl = endpoint.trim().replace(/\/$/, '');
    const secure = /^https:\/\/[^\s\/?#@]+(?::\d+)?$/i.test(this.baseUrl);
    const local = /^http:\/\/(localhost|127\.0\.0\.1)(?::\d+)?$/i.test(this.baseUrl);
    if (!secure && !local)
      throw new Error('同步地址必须使用 HTTPS（localhost 除外）');
  }

  async register(email: string, password: string, invitationCode: string): Promise<void> {
    await this.request('POST', '/api/v1/auth/register', {
      email: email.trim(), password, invitationCode: invitationCode.trim(),
    }, '', [202]);
  }
  async login(email: string, password: string, deviceId: string, deviceName: string): Promise<AuthSession> {
    return this.session(await this.request('POST', '/api/v1/auth/login', {
      email: email.trim(), password, deviceId, deviceName,
    }) as TokenResponse);
  }
  async refresh(deviceId: string, refreshToken: string): Promise<AuthSession> {
    return this.session(await this.request('POST', '/api/v1/auth/refresh', { deviceId, refreshToken }) as TokenResponse);
  }
  async logout(accessToken: string): Promise<void> {
    await this.request('POST', '/api/v1/auth/logout', {}, accessToken, [204]);
  }
  async push(accessToken: string, operations: LocalSyncOperation[]): Promise<string[]> {
    if (operations.length === 0) return [];
    const response = await this.request('POST', '/api/v1/sync/push', { operations }, accessToken) as PushResponse;
    return response.acknowledgements.map(value => value.operationId);
  }
  async pull(accessToken: string, cursor: number): Promise<PullResult> {
    return await this.request('GET', `/api/v1/sync/pull?cursor=${cursor}&limit=500`, undefined,
      accessToken) as PullResult;
  }

  private session(value: TokenResponse): AuthSession {
    if (!value.accountId || !value.workspaceId || !value.accessToken || !value.refreshToken)
      throw new Error('同步服务返回了不完整的登录凭据');
    return { accountId: value.accountId, workspaceId: value.workspaceId,
      accessToken: value.accessToken, accessExpiresAt: Math.floor(Date.now() / 1000) + value.expiresIn,
      refreshToken: value.refreshToken };
  }

  private async request(method: string, path: string, body?: Object, token: string = '',
    expected: number[] = [200]): Promise<Object> {
    const client = http.createHttp();
    try {
      const headers: Record<string, string> = { 'Accept': 'application/json, application/problem+json' };
      if (body !== undefined && method !== 'GET') headers['Content-Type'] = 'application/json';
      if (token.length > 0) headers['Authorization'] = `Bearer ${token}`;
      const response = await client.request(this.baseUrl + path, {
        method: method === 'GET' ? http.RequestMethod.GET : http.RequestMethod.POST,
        header: headers, extraData: body === undefined ? '' : JSON.stringify(body),
        expectDataType: http.HttpDataType.STRING, connectTimeout: 15000, readTimeout: 30000,
      });
      const text = String(response.result ?? '');
      if (!expected.includes(response.responseCode)) {
        let title = ''; try { title = String((JSON.parse(text) as Record<string, Object>)['title'] ?? ''); } catch (_) {}
        throw new Error(title.length > 0 ? title : `同步服务返回 HTTP ${response.responseCode}`);
      }
      return text.length === 0 ? {} : JSON.parse(text) as Object;
    } finally { client.destroy(); }
  }
}
