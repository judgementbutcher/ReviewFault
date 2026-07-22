package cn.reviewfault.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

data class AvailableUpdate(
    val version: String,
    val apkUrl: String,
    val fileName: String,
)

class UpdateService(private val context: Context) {
    fun check(currentVersion: String): AvailableUpdate? {
        val connection = open("https://api.github.com/repos/judgementbutcher/ReviewFault/releases/latest")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "ReviewFault/$currentVersion")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val release = JSONObject(body)
        val version = release.getString("tag_name").trim().trimStart('v', 'V')
        if (!isNewer(version, currentVersion)) return null
        val expectedName = "ReviewFault-android-v$version.apk"
        val assets = release.getJSONArray("assets")
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            if (asset.optString("name") != expectedName) continue
            val url = asset.getString("browser_download_url")
            requireTrustedDownload(url, version)
            return AvailableUpdate(version, url, expectedName)
        }
        error("v$version 尚未提供 Android 安装包")
    }

    fun download(update: AvailableUpdate): File {
        requireTrustedDownload(update.apkUrl, update.version)
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { if (it.name != update.fileName) it.delete() }
        val target = File(directory, update.fileName)
        val partial = File(directory, "${update.fileName}.part")
        open(update.apkUrl).inputStream.use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        require(partial.length() > 0) { "下载的安装包为空" }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "无法保存安装包" }
        return target
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }

    private fun requireTrustedDownload(url: String, version: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "github.com" &&
            uri.path == "/judgementbutcher/ReviewFault/releases/download/v$version/ReviewFault-android-v$version.apk") {
            "更新下载地址不受信任"
        }
    }
}

internal fun isNewer(candidate: String, current: String): Boolean {
    fun parts(value: String) = value.substringBefore('-').split('.').mapNotNull(String::toIntOrNull)
    val available = parts(candidate)
    val installed = parts(current)
    if (available.size != 3 || installed.size != 3) return false
    return available.zip(installed).firstOrNull { it.first != it.second }
        ?.let { it.first > it.second } ?: false
}
