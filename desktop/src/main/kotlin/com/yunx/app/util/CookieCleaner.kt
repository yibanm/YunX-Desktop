package com.yunx.app.util

import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager

/**
 * 退出登录时清除 JCEF 浏览器中指定网盘域名的 Cookie。
 *
 * 单独封装：所有 AccountRepository.logout 都会调用本工具，避免再次登录时被
 * JCEF 已保留的会话 Cookie 自动登录。JCEF 未初始化（用户从未打开过登录页）时静默忽略。
 */
object CookieCleaner {

    /**
     * 清除给定域名列表下的所有 Cookie。
     * @param domains 域名片段列表，如 ["pan.baidu.com", "yun.baidu.com", "baidu.com"]；
     *                cookie 的 domain 命中任一即清除。
     */
    fun clearCookiesForDomains(domains: List<String>) {
        if (domains.isEmpty()) return
        runCatching {
            val manager = CefCookieManager.getGlobalManager() ?: return@runCatching
            // 遍历所有 cookie，域名命中的记录下 host+name，遍历结束后逐个删除
            val toDelete = mutableListOf<Pair<String, String>>()
            manager.visitAllCookies(object : CefCookieVisitor {
                override fun visit(
                    cookie: CefCookie,
                    count: Int,
                    total: Int,
                    delete: BoolRef
                ): Boolean {
                    val domain = cookie.domain ?: return true
                    if (domains.any { domain.contains(it, ignoreCase = true) }) {
                        toDelete += domain to (cookie.name ?: "")
                    }
                    return true // 继续遍历
                }
            })
            // visitAllCookies 回调在 CEF IO 线程异步执行；这里直接调用 deleteCookies，
            // 由 CefCookieManager 内部队列处理，不需要等待 visit 完成。
            // 直接按域名 URL 批量删除更可靠：
            domains.forEach { d ->
                runCatching {
                    manager.deleteCookies("https://$d", null)
                }
            }
        }
        // JCEF 未启动时 getGlobalManager() 可能抛 / 返回 null，全部静默忽略
        Unit
    }
}
