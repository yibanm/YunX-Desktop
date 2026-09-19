package com.yunx.app.data.backup

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * WebDAV HTTP 层回归测试。
 *
 * 背景：旧实现用 JDK HttpURLConnection，setRequestMethod("MKCOL"/"PROPFIND") 会直接抛
 * ProtocolException("Invalid HTTP method: MKCOL")，导致 WebDAV 备份 100% 失败。
 * 改用 OkHttp 后，这些 WebDAV 动词必须能正常发出并被服务器接受。
 */
class WebDavHttpVerbTest {

    private lateinit var server: MockWebServer
    private lateinit var manager: WebDavBackupManager
    private lateinit var config: WebDavBackupManager.Config

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        manager = WebDavBackupManager()
        // baseUrl 以 / 结尾，模拟坚果云 https://host/dav/ 形态
        config = WebDavBackupManager.Config(server.url("/dav/").toString(), "user", "pass")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val propfindXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/dav/YunX/</D:href></D:response>
          <D:response>
            <D:href>/dav/YunX/yunx_backup_20260918_100000.json</D:href>
            <D:propstat><D:prop>
              <D:getlastmodified>Fri, 18 Sep 2026 10:00:00 GMT</D:getlastmodified>
              <D:getcontentlength>1234</D:getcontentlength>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/YunX/yunx_backup_20260919_090000.json</D:href>
            <D:propstat><D:prop>
              <D:getlastmodified>Sat, 19 Sep 2026 09:00:00 GMT</D:getlastmodified>
              <D:getcontentlength>999</D:getcontentlength>
            </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    /** PROPFIND 是被 HttpURLConnection 拒绝的动词之一，列备份必须成功且按时间倒序。 */
    @Test
    fun listBackupsUsesPropfindAndSortsNewestFirst() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(207).setBody(propfindXml))
        val backups = manager.listBackups(config)

        val recorded = server.takeRequest()
        assertEquals("PROPFIND", recorded.method)
        assertEquals("1", recorded.getHeader("Depth"))
        assertTrue(recorded.getHeader("Authorization")?.startsWith("Basic ") == true)

        assertEquals(2, backups.size)
        assertEquals("yunx_backup_20260919_090000.json", backups.first().name)
        assertEquals(999L, backups.first().size)
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        val expected = sdf.parse("Sat, 19 Sep 2026 09:00:00 GMT")!!.time
        assertEquals(expected, backups.first().lastModified)
    }

    /** 目录已存在（PROPFIND 207）时不应再 MKCOL。 */
    @Test
    fun ensureAppDirSkipsMkcolWhenExists() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(propfindXml))
        invokeEnsureAppDir()
        assertEquals(1, server.requestCount)
        assertEquals("PROPFIND", server.takeRequest().method)
    }

    /** PROPFIND 404 后应发 MKCOL 创建目录，且不抛异常。 */
    @Test
    fun ensureAppDirCreatesWithMkcolWhenMissing() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(201))
        invokeEnsureAppDir()
        assertEquals("PROPFIND", server.takeRequest().method)
        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
        assertTrue(mkcol.path!!.endsWith("/YunX"))
    }

    /** 直接验证 MKCOL 动词能被 OkHttp 发出（旧实现此处抛 Invalid HTTP method）。 */
    @Test
    fun mkcolVerbIsAccepted() {
        server.enqueue(MockResponse().setResponseCode(201))
        invokePrivate("mkcol", arrayOf(config, "YunX"))
        assertEquals("MKCOL", server.takeRequest().method)
    }

    /** PUT 上传需带 JSON body 且成功。 */
    @Test
    fun putUploadsJsonBody() {
        server.enqueue(MockResponse().setResponseCode(201))
        invokePrivate("put", arrayOf(config, "YunX/test.json", "{}".toByteArray()))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("{}", req.body.readUtf8())
        assertTrue(req.getHeader("Content-Type")?.contains("application/json") == true)
    }

    /** GET 拉取备份内容。 */
    @Test
    fun getDownloadsBytes() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"app\":\"yunx_backup\"}"))
        val bytes = invokePrivate("get", arrayOf(config, "YunX/test.json")) as ByteArray
        assertEquals("{\"app\":\"yunx_backup\"}", String(bytes))
        assertEquals("GET", server.takeRequest().method)
    }

    /** 服务器返回 401 时应抛出带提示的异常，而不是静默成功。 */
    @Test
    fun ensureAppDirReportsAuthError() {
        server.enqueue(MockResponse().setResponseCode(401))
        val error = runCatching { invokeEnsureAppDir() }.exceptionOrNull()
        // 反射调用会把目标异常包成 InvocationTargetException，取真实 cause
        val real = error?.cause ?: error
        assertTrue(real != null && real.message!!.contains("HTTP 401"))
    }

    private fun invokeEnsureAppDir() {
        invokePrivate("ensureAppDir", arrayOf(config))
    }

    /** 反射调用私有阻塞 HTTP 方法（mkcol/put/get/ensureAppDir）。 */
    private fun invokePrivate(name: String, args: Array<Any?>): Any? {
        // 按实际参数个数与类型匹配
        val types = when (name) {
            "ensureAppDir" -> arrayOf(WebDavBackupManager.Config::class.java)
            "mkcol" -> arrayOf(WebDavBackupManager.Config::class.java, String::class.java)
            "put" -> arrayOf(
                WebDavBackupManager.Config::class.java,
                String::class.java,
                ByteArray::class.java
            )
            "get" -> arrayOf(WebDavBackupManager.Config::class.java, String::class.java)
            else -> error("unknown method $name")
        }
        val m = WebDavBackupManager::class.java.getDeclaredMethod(name, *types)
        m.isAccessible = true
        return m.invoke(manager, *args)
    }
}
