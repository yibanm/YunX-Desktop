package com.yunx.app.util

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary

/**
 * 设置 Windows 进程的 AppUserModelID，使任务栏右键菜单显示"固定到任务栏"和"结束任务"。
 * 必须在窗口创建之前调用。
 */
object WindowsAppUserModelId {
    private val APP_ID = "YunX-Desktop-Fork"

    fun init() {
        if (!System.getProperty("os.name").contains("Windows", true)) return
        runCatching {
            val shell32 = Native.load("shell32", Shell32::class.java)
            val hr = shell32.SetCurrentProcessExplicitAppUserModelID(APP_ID)
            println("[AUMID] SetCurrentProcessExplicitAppUserModelID($APP_ID) hr=$hr")
        }.onFailure {
            println("[AUMID] Failed: ${it.message}")
        }
    }

    private interface Shell32 : StdCallLibrary {
        fun SetCurrentProcessExplicitAppUserModelID(appId: String): Int
    }
}
