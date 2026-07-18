package cn.reviewfault.app.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class AccountTokens(
    val accountId: String,
    val workspaceId: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
)

class SecureTokenStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("secure_account", Context.MODE_PRIVATE)

    fun save(tokens: AccountTokens) {
        val plain = JSONObject().apply {
            put("accountId", tokens.accountId); put("workspaceId", tokens.workspaceId)
            put("accessToken", tokens.accessToken); put("accessExpiresAt", tokens.accessExpiresAt)
            put("refreshToken", tokens.refreshToken)
        }.toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val encrypted = cipher.doFinal(plain)
        preferences.edit()
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun load(): AccountTokens? = try {
        val iv = Base64.decode(preferences.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val encrypted = Base64.decode(
            preferences.getString("payload", null) ?: return null, Base64.NO_WRAP,
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        val json = JSONObject(String(cipher.doFinal(encrypted)))
        AccountTokens(
            json.getString("accountId"), json.getString("workspaceId"),
            json.getString("accessToken"), json.getLong("accessExpiresAt"),
            json.getString("refreshToken"),
        )
    } catch (_: Exception) { clear(); null }

    fun clear() { preferences.edit().clear().apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private companion object { const val KEY_ALIAS = "reviewfault-account-v1" }
}
