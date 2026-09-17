package com.yunx.app.data.backup

import com.yunx.app.AppContext
import com.yunx.app.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * WebDAV 通用备份：把收藏 / 解析历史 / 下载记录 / 网盘认证 打包为 JSON，
 * 上传到任意 WebDAV 服务器的 YunX/ 目录下（自动创建），或从服务器拉取恢复。
 *
 * 备份文件名格式：yunx_backup_yyyyMMdd_HHmmss.json。
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

    /** 备份文件条目（供还原列表选择） */
    data class BackupFile(
        val name: String,
        val lastModified: Long,
        val size: Long
    )

    companion object {
        const val APP_TAG = "yunx_backup"
        const val VERSION = 1
        const val APP_DIR = "YunX"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000

        /** 服务器预设（一键填充地址，用户名密码仍需自行填写） */
        val PRESETS: LinkedHashMap<String, String> = linkedMapOf(
            "坚果云" to "https://dav.jianguoyun.com/dav/",
            "infini-cloud" to "https://wajima.infini-cloud.net/dav/",
            "Terabox" to "https://dav.terabox.com/dav/",
            "Koofr" to "https://app.koofr.net/dav/Koofr",
            "4shared" to "https://webdav.4shared.com/"
        )
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

    /** 生成带时间戳的备份文件名：yunx_backup_yyyyMMdd_HHmmss.json */
    private fun timestampedName(now: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "yunx_backup_${sdf.format(Date(now))}.json"
    }

    /**
     * 备份到 WebDAV：确保 YunX/ 目录存在，上传带时间戳的备份文件。
     * @return 上传后的文件名
     */
    suspend fun backup(config: Config, options: BackupOptions): String = withContext(Dispatchers.IO) {
        ensureAppDir(config)
        val json = buildBackupJson(options)
        val fileName = timestampedName()
        put(config, "$APP_DIR/$fileName", json.toByteArray(StandardCharsets.UTF_8))
        fileName
    }

    /** 兼容旧调用名：备份到 WebDAV（固定文件名 yunx-backup.json，根目录） */
    suspend fun backupToWebDav(config: Config, options: BackupOptions): Unit =
        withContext(Dispatchers.IO) {
            val json = buildBackupJson(options)
            put(config, "yunx-backup.json", json.toByteArray(StandardCharsets.UTF_8))
        }

    /**
     * 列出 YunX/ 目录下所有备份文件（.json），按最后修改时间降序（最新在最上面）。
     */
    suspend fun listBackups(config: Config): List<BackupFile> = withContext(Dispatchers.IO) {
        val xml = propfind(config, APP_DIR)
        parsePropfind(xml)
            .filter { it.name.endsWith(".json", ignoreCase = true) }
            .sortedByDescending { it.lastModified }
    }

    /**
     * 从 WebDAV 还原指定备份文件。
     * @return 还原的记录条数（收藏+历史+下载记录粗略计数）
     */
    suspend fun restore(config: Config, fileName: String): Int = withContext(Dispatchers.IO) {
        val bytes = get(config, "$APP_DIR/$fileName")
        val text = String(bytes, StandardCharsets.UTF_8)
        restoreFromJson(text)
    }

    /** 兼容旧调用名：从默认固定备份还原 */
    suspend fun restoreFromWebDav(config: Config): Unit = withContext(Dispatchers.IO) {
        val bytes = get(config, "yunx-backup.json")
        val text = String(bytes, StandardCharsets.UTF_8)
        restoreFromJson(text)
    }

    /** 从备份 JSON 字符串恢复（收藏 / 历史 / 下载记录 / 认证），返回还原记录条数。 */
    suspend fun restoreFromJson(json: String): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        if (root.optString("app") != APP_TAG) {
            throw IllegalArgumentException("不是有效的云析备份文件")
        }
        val db = AppDatabase.get()
        var restored = 0

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
                    restored++
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
                    restored++
                }
            }
        }

        root.optString("auth", "").takeIf { it.isNotBlank() }?.let { authJson ->
            runCatching { buildAuthManager().importJson(authJson) }
        }

        restored
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

    /** 确保 YunX/ 目录存在：PROPFIND 检查，404 则 MKCOL 创建 */
    private fun ensureAppDir(config: Config) {
        val exists = runCatching {
            propfind(config, APP_DIR, depth = "0")
            true
        }.getOrDefault(false)
        if (!exists) {
            mkcol(config, APP_DIR)
        }
    }

    private fun mkcol(config: Config, path: String) {
        val conn = buildUrl(config, path).openConnection() as HttpURLConnection
        conn.requestMethod = "MKCOL"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Authorization", authHeader(config))
        val code = conn.responseCode
        conn.disconnect()
        // 201 Created / 405 (already exists) 都视为成功
        if (code !in 200..299 && code != 405 && code != 409) {
            throw java.io.IOException("WebDAV 创建目录失败：HTTP $code")
        }
    }

    /** PROPFIND 列目录，返回响应 XML 字符串 */
    private fun propfind(config: Config, path: String, depth: String = "1"): String {
        val conn = buildUrl(config, path).openConnection() as HttpURLConnection
        conn.requestMethod = "PROPFIND"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Authorization", authHeader(config))
        conn.setRequestProperty("Depth", depth)
        conn.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val out = ByteArrayOutputStream()
        stream?.use { it.copyTo(out) }
        conn.disconnect()
        if (code !in 200..299) {
            throw java.io.IOException("WebDAV PROPFIND 失败：HTTP $code")
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    /** 用正则解析 PROPFIND multistatus XML，提取 href / getlastmodified / getcontentlength */
    internal fun parsePropfind(xml: String): List<BackupFile> {
        val result = mutableListOf<BackupFile>()
        // 每个 <D:response>...</D:response> 块
        val responseRegex = Regex("<[^:>]*:?response[^>]*>([\\s\\S]*?)</[^:>]*:?response>")
        for (m in responseRegex.findAll(xml)) {
            val block = m.groupValues[1]
            val href = Regex("<[^:>]*:?href[^>]*>([^<]+)</[^:>]*:?href>")
                .find(block)?.groupValues?.get(1)?.trim() ?: continue
            // 跳过当前目录自身（href 以目录名结尾且无文件名）
            val decoded = URLDecoder.decode(href, "UTF-8")
            val name = decoded.trimEnd('/').substringAfterLast('/')
            if (name.isBlank()) continue
            val lastMod = Regex("<[^:>]*:?getlastmodified[^>]*>([^<]+)</[^:>]*:?getlastmodified>")
                .find(block)?.groupValues?.get(1)?.trim()
            val sizeStr = Regex("<[^:>]*:?getcontentlength[^>]*>([^<]+)</[^:>]*:?getcontentlength>")
                .find(block)?.groupValues?.get(1)?.trim()
            val size = sizeStr?.toLongOrNull() ?: 0L
            val modTs = parseHttpDate(lastMod)
            result.add(BackupFile(name = name, lastModified = modTs, size = size))
        }
        return result
    }

    /** 解析 HTTP 日期（RFC 1123，如 "Wed, 16 Sep 2026 10:20:30 GMT"） */
    private fun parseHttpDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return runCatching {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            sdf.parse(s)?.time ?: 0L
        }.getOrDefault(0L)
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

    /** 本地备份目录：AppContext.dataDir/YunX/ */
    internal fun localDir(): File = File(AppContext.dataDir, "YunX").apply { mkdirs() }

    // ---------- 本地备份（与 WebDAV 对齐，存放在 AppContext.dataDir/YunX/） ----------

    /** 备份到本地：在 dataDir/YunX/ 下保存带时间戳的 json 文件，返回文件 */
    suspend fun backupLocal(options: BackupOptions): File = withContext(Dispatchers.IO) {
        val dir = localDir()
        val json = buildBackupJson(options)
        val name = timestampedName()
        val f = File(dir, name)
        f.writeText(json, StandardCharsets.UTF_8)
        f
    }

    /** 列出本地 YunX/ 目录下所有 .json 备份，按最后修改时间降序（最新在最上面） */
    suspend fun listLocalBackups(): List<BackupFile> = withContext(Dispatchers.IO) {
        val dir = localDir()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
            ?.map { BackupFile(name = it.name, lastModified = it.lastModified(), size = it.length()) }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    /** 还原指定本地备份文件 */
    suspend fun restoreLocal(fileName: String): Int = withContext(Dispatchers.IO) {
        val f = File(localDir(), fileName)
        if (!f.exists()) throw java.io.FileNotFoundException("本地备份不存在：$fileName")
        restoreFromJson(f.readText(StandardCharsets.UTF_8))
    }
}
