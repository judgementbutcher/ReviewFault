package cn.reviewfault.app.sync

import android.util.Base64
import cn.reviewfault.app.data.SyncMediaObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class AuthSession(
    val accountId: String,
    val workspaceId: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
)

data class PulledOperation(
    val operationId: String,
    val serverSeq: Long,
    val deviceId: String,
    val deviceCounter: Long,
    val entityType: String,
    val entityId: String,
    val action: String,
    val changedFields: JSONObject,
    val occurredAt: Long,
)

data class PullResult(val cursor: Long, val operations: List<PulledOperation>)

class SyncClient(endpoint: String) {
    private val baseUrl = endpoint.trim().trimEnd('/').also { value ->
        val uri = runCatching { URI(value) }.getOrNull()
        require(uri != null && uri.isAbsolute && uri.userInfo == null && uri.query == null &&
            uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/") &&
            (uri.scheme.equals("https", ignoreCase = true) ||
              (uri.scheme.equals("http", ignoreCase = true) &&
               uri.host in setOf("10.0.2.2", "localhost", "127.0.0.1")))) {
            "同步地址必须使用 HTTPS（Android 模拟器本机地址除外）"
        }
    }

    fun register(email: String, password: String, invitationCode: String) {
        request("POST", "/api/v1/auth/register", JSONObject().apply {
            put("email", email.trim()); put("password", password)
            put("invitationCode", invitationCode.trim())
        }, expected = setOf(202))
    }

    fun login(email: String, password: String, deviceId: String, deviceName: String): AuthSession {
        val response = request("POST", "/api/v1/auth/login", JSONObject().apply {
            put("email", email.trim()); put("password", password)
            put("deviceId", deviceId); put("deviceName", deviceName)
        })
        return response.toSession()
    }

    fun refresh(deviceId: String, refreshToken: String): AuthSession {
        val response = request("POST", "/api/v1/auth/refresh", JSONObject().apply {
            put("deviceId", deviceId); put("refreshToken", refreshToken)
        })
        return response.toSession()
    }

    fun logout(accessToken: String) {
        request("POST", "/api/v1/auth/logout", JSONObject(), accessToken, setOf(204))
    }

    fun push(accessToken: String, operations: JSONArray): Set<String> {
        if (operations.length() == 0) return emptySet()
        val response = request("POST", "/api/v1/sync/push", JSONObject().put("operations", operations), accessToken)
        val acknowledgements = response.getJSONArray("acknowledgements")
        return buildSet {
            for (index in 0 until acknowledgements.length()) {
                add(acknowledgements.getJSONObject(index).getString("operationId"))
            }
        }
    }

    fun pull(accessToken: String, cursor: Long): PullResult {
        val response = request("GET", "/api/v1/sync/pull?cursor=$cursor&limit=500", token = accessToken)
        val values = response.getJSONArray("operations")
        val operations = buildList {
            for (index in 0 until values.length()) values.getJSONObject(index).let { value ->
                add(PulledOperation(
                    value.getString("operationId"), value.getLong("serverSeq"),
                    value.getString("deviceId"), value.getLong("deviceCounter"),
                    value.getString("entityType"), value.getString("entityId"),
                    value.getString("action"), value.getJSONObject("changedFields"),
                    value.getLong("occurredAt"),
                ))
            }
        }
        return PullResult(response.getLong("cursor"), operations)
    }

    fun uploadMedia(accessToken: String, media: List<SyncMediaObject>) {
        media.chunked(100).forEach { batch ->
            val response = request("POST", "/api/v1/media/prepare", JSONObject().put("objects",
                JSONArray(batch.map { value -> JSONObject().apply {
                    put("sha256", value.sha256); put("byteCount", value.byteCount); put("mimeType", value.mimeType)
                } })), accessToken)
            val results = response.getJSONArray("objects")
            for (index in 0 until results.length()) {
                val result = results.getJSONObject(index)
                if (result.getBoolean("present")) continue
                val uploadId = result.getString("uploadId"); val value = batch[index]
                value.file.inputStream().use { input ->
                    var part = 0
                    while (true) {
                        val buffer = ByteArray(5 * 1024 * 1024)
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        val bytes = if (count == buffer.size) buffer else buffer.copyOf(count)
                        if (bytes.isEmpty()) break
                        uploadChunk(accessToken, uploadId, part++, bytes)
                    }
                }
                request("POST", "/api/v1/media/$uploadId/complete", JSONObject(), accessToken)
            }
        }
    }

    fun downloadMedia(accessToken: String, sha256: String): ByteArray {
        val connection = (URL("$baseUrl/api/v1/media/$sha256").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            val code = connection.responseCode
            if (code != 200) error("媒体下载返回 HTTP $code")
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    private fun uploadChunk(accessToken: String, uploadId: String, index: Int, bytes: ByteArray) {
        val connection = (URL("$baseUrl/api/v1/media/$uploadId/chunk/$index").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; connectTimeout = 15_000; readTimeout = 60_000; doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(bytes.size); outputStream.use { it.write(bytes) }
        }
        try { if (connection.responseCode != 204) error("媒体分片上传失败：HTTP ${connection.responseCode}") }
        finally { connection.disconnect() }
    }

    private fun JSONObject.toSession(): AuthSession {
        val access = getString("accessToken")
        val payload = access.split('.').getOrNull(1) ?: error("服务端返回了无效 access token")
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val claims = JSONObject(String(decoded))
        val accountId = optString("accountId").ifBlank { claims.optString("sub") }.ifBlank {
            claims.optString("http://schemas.xmlsoap.org/ws/2005/05/identity/claims/nameidentifier")
        }
        require(accountId.isNotBlank()) { "access token 缺少账号标识" }
        return AuthSession(
            accountId, getString("workspaceId"), access,
            Instant.now().epochSecond + getLong("expiresIn"), getString("refreshToken"),
        )
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        expected: Set<Int> = setOf(200),
    ): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Accept", "application/json, application/problem+json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null && method != "GET") {
                doOutput = true; setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in expected) {
                val problem = runCatching { JSONObject(text).optString("title") }.getOrNull()
                error(problem?.takeIf(String::isNotBlank) ?: "同步服务返回 HTTP $code")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
}
