package com.yunx.app

import java.io.File
import java.util.prefs.Preferences

/**
 * 桌面应用上下文门面：替代 Android Context。
 * 数据/缓存/临时目录、以及非设置类的杂项偏好（onboarding、忽略版本、迅雷设备指纹）。
 */
object AppContext {

    /** 应用数据根目录：默认 <用户目录>/.yunx-pc；可用环境变量 YUNX_PC_DATA_DIR 覆盖（测试/沙箱环境用） */
    val dataDir: File = System.getenv("YUNX_PC_DATA_DIR")?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".yunx-pc")

    /** 运行时自定义缓存目录（由设置页注入；null = 使用默认 cacheDir，修改后重启生效） */
    @Volatile
    var customCacheDir: String? = null

    /** 缓存目录（下载分片等可丢弃数据）；可被 [customCacheDir] 覆盖 */
    val cacheDir: File get() = customCacheDir?.let { File(it) } ?: File(dataDir, "cache")

    /** 下载分片目录（原 externalCacheDir/download_tmp） */
    val downloadTmpDir: File get() = File(cacheDir, "download_tmp")

    /** 合并暂存目录（原 cacheDir/merged_*） */
    val mergeDir: File get() = File(cacheDir, "merge")

    /** 杂项文件目录（日志等） */
    val filesDir: File = File(dataDir, "files")

    /** 杂项偏好（原 "yunx_prefs" SharedPreferences：onboarding_shown / ignored_version） */
    val miscPrefs: Preferences = Preferences.userRoot().node("yunx/misc")

    /** 迅雷设备指纹偏好（原 "xunlei_device_fp"） */
    val xunleiFpPrefs: Preferences = Preferences.userRoot().node("yunx/xunlei_fp")

    fun init() {
        listOf(dataDir, cacheDir, downloadTmpDir, mergeDir, filesDir).forEach { it.mkdirs() }
    }
}
