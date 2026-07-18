import { huks } from '@kit.UniversalKeystoreKit';
import { preferences } from '@kit.ArkData';
import { UIAbilityContext } from '@kit.AbilityKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';

const ALIAS = 'reviewfault-account-v1';

export interface AccountTokens {
  accountId: string;
  workspaceId: string;
  accessToken: string;
  accessExpiresAt: number;
  refreshToken: string;
}

export class SecureTokenStore {
  constructor(private readonly context: UIAbilityContext) {}

  async clear(): Promise<void> {
    const store = await preferences.getPreferences(this.context, 'secure_account');
    await store.clear();
    await store.flush();
  }

  async save(tokens: AccountTokens): Promise<void> {
    await this.ensureKey();
    const plain = new TextEncoder().encode(JSON.stringify(tokens));
    const iv = (await cryptoFramework.createRandom().generateRandom(12)).data;
    const options = this.options(huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT, iv);
    const session = await huks.initSession(ALIAS, options);
    const encrypted = (await huks.finishSession(session.handle, options, plain)).outData;
    const base64 = new util.Base64Helper();
    const store = await preferences.getPreferences(this.context, 'secure_account');
    await store.put('iv', base64.encodeToStringSync(iv));
    await store.put('payload', base64.encodeToStringSync(encrypted));
    await store.flush();
  }

  async load(): Promise<AccountTokens | undefined> {
    try {
      await this.ensureKey();
      const store = await preferences.getPreferences(this.context, 'secure_account');
      const ivText = await store.get('iv', '') as string;
      const payloadText = await store.get('payload', '') as string;
      if (ivText.length === 0 || payloadText.length === 0) return undefined;
      const base64 = new util.Base64Helper();
      const iv = base64.decodeSync(ivText); const encrypted = base64.decodeSync(payloadText);
      const options = this.options(huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT, iv);
      const session = await huks.initSession(ALIAS, options);
      const plain = (await huks.finishSession(session.handle, options, encrypted)).outData;
      return JSON.parse(new TextDecoder().decode(plain)) as AccountTokens;
    } catch (_) { await this.clear(); return undefined; }
  }

  // HUKS owns key material; only the encrypted JSON and IV are stored in preferences.
  async ensureKey(): Promise<void> {
    const exists = await huks.hasKeyItem(ALIAS, { properties: [] });
    if (exists) return;
    await huks.generateKeyItem(ALIAS, {
      properties: [
        { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
        { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_AES_KEY_SIZE_256 },
        { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT |
          huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT },
        { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
        { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
      ],
    });
  }

  private options(purpose: huks.HuksKeyPurpose, iv: Uint8Array): huks.HuksOptions {
    return { properties: [
      { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
      { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_AES_KEY_SIZE_256 },
      { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: purpose },
      { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
      { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
      { tag: huks.HuksTag.HUKS_TAG_NONCE, value: iv },
    ] };
  }
}
