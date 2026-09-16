package com.yunx.app.data.backup

import com.yunx.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * WebDAV 通用备份：把收藏 / 解析历史 / 下载记录 / 网盘认证 打包为 JSON，
 * 上传到任意 WebDAV 服务器，或从服务器拉取恢复。
 *
 * 所有方法均为 suspend，应在 IO 调度上调用；失败抛异常由调用方 catch 后提示。
 */
class WebDavBackupManager {

    data class Config(
        val serverUrl: String,
        val username: String,
        val password: String
    )

    data class BackupOptions(
        val includeFavorites: Boolean = true,
        val includeLinkHistory: Boolean = true,
        val includeDownloadRecords: Boolean = true,
        val includeAuth: Boolean = false
    )

    private companion object {
        const val APP_TAG = "yunx_backup"
        const val VERSION = 1
        const val BACKUP_PATH = "yunx-backup.json"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }

    /** 按选项构建备份 JSON 字符串（不进行网络请求）。 */
    suspend fun buildBackupJson(options: BackupOptions): String = withContext(Dispatchers.IO) {
        val db = AppDatabase.get()
        val root = JSONObject()
            .put("app", APP_TAG)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())

        if (options.includeFavorites) {
            val arr = JSONArray()
            db.bookmarkDao().observeAll().first().forEach { bm ->
                arr.put(
                    JSONObject()
                        .put("link", bm.link)
                        .put("title", bm.title)
                        .put("platform", bm.platform)
                        .put("pwd", bm.pwd)
                        .put("category", bm.category)
                        .put("createTime", bm.createTime)
                )
            }
            root.put("favorites", arr)
        }

        if (options.includeLinkHistory) {
            val arr = JSONArray()
            db.linkHistoryDao().observeAll().first().forEach { h ->
                arr.put(
                    JSONObject()
                        .put("url", h.url)
                        .put("title", h.title)
                        .put("platform", h.platform)
                        .put("pwd", h.pwd)
                        .put("createTime", h.createTime)
                )
            }
            root.put("linkHistory", arr)
        }

        if (options.includeDownloadRecords) {
            val arr = JSONArray()
            db.downloadTaskDao().observeAll().first().forEach { t ->
                arr.put(
                    JSONObject()
                        .put("url", t.url)
                        .put("fileName", t.fileName)
                        .put("totalSize", t.totalSize)
                        .put("status", t.status)
                        .put("platform", t.platform)
                        .put("savePath", t.savePath)
                        .put("createTime", t.createTime)
                )
            }
            root.put("downloadRecords", arr)
        }

        if (options.includeAuth) {
            root.put("auth", buildAuthManager().exportJson(onlyLoggedIn = true))
        }

        root.toString(2)
    }

    /** 构建（恢复用）认证管理器，复用与网盘认证页相同的 DAO 装配。 */
    private fun buildAuthManager(): AuthBackupManager {
        val db = AppDatabase.get()
        return AuthBackupManager(
            quarkDao = db.quarkAccountDao(),
            ucDao = db.ucAccountDao(),
            xunleiDao = db.xunleiAccountDao(),
            baiduDao = db.baiduAccountDao(),
            c139Dao = db.c139AccountDao(),
            pan123Dao = db.pan123AccountDao()
        )
    }

    /** 备份到 WebDAV：把 buildBackupJson 的结果 PUT 到服务器。 */
    suspend fun backupToWebDav(config: Config, options: BackupOptions): Unit =
        withContext(Dispatchers.IO) {
            val json = buildBackupJson(options)
            put(config, BACKUP_PATH, json.toByteArray(StandardCharsets.UTF_8))
        }

    /** 从 WebDAV 拉取备份并恢复。 */
    suspend fun restoreFromWebDav(config: Config): Unit = withContext(Dispatchers.IO) {
        val bytes = get(config, BACKUP_PATH)
        val text = String(bytes, StandardCharsets.UTF_8)
        restoreFromJson(text)
    }

    /** 从备份 JSON 字符串恢复（收藏 / 历史 / 下载记录 / 认证）。 */
    suspend fun restoreFromJson(json: String): Unit = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        if (root.optString("app") != APP_TAG) {
            throw IllegalArgumentException("不是有效的云析备份文件")
        }
        val db = AppDatabase.get()

        root.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    db.bookmarkDao().insert(
                        com.yunx.app.data.db.BookmarkEntity(
                            link = o.optString("link"),
                            title = o.optString("title"),
                            platform = o.optString("platform"),
                            pwd = o.optString("pwd"),
                            category = o.optString("category", com.yunx.app.data.db.BookmarkEntity.DEFAULT_CATEGORY),
                            createTime = o.optLong("createTime", System.currentTimeMillis())
                        )
                    )
                }
            }
        }

        root.optJSONArray("linkHistory")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching {
                    db.linkHistoryDao().insert(
                        com.yunx.app.data.db.LinkHistoryEntity(
                            url = o.optString("url"),
                            title = o.optString("title"),
                            platform = o.optString("platform"),
                            pwd = o.optString("pwd"),
                            createTime = o.optLong("createTime", System.currentTimeMillis())
                        )
                    )
                }
            }
        }

        root.optString("auth", "").takeIf { it.isNotBlank() }?.let { authJson ->
            runCatching { buildAuthManager().importJson(authJson) }
        }
    }

    // ---------- 极简 WebDAV HTTP 客户端（Basic 认证） ----------

    private fun buildUrl(config: Config, path: String): URL {
        val base = config.serverUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return URL("$base$p")
    }

    private fun authHeader(config: Config): String {
        val raw = "${config.username}:${config.password}"
        return "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    private fun put(config: Config, path: String, body: ByteArray) {
        val conn = buildUrl(config, path).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Authorization", authHeader(config))
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) throw java.io.IOException("WebDAV 上传失败：HTTP $code")
    }

    private fun get(config: Config, path: String): ByteArray {
        val conn = buildUrl(config, path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Authorization", authHeader(config))
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val out = ByteArrayOutputStream()
        stream?.use { it.copyTo(out) }
        conn.disconnect()
        if (code !in 200..299) throw java.io.IOException("WebDAV 下载失败：HTTP $code")
        return out.toByteArray()
    }
}
