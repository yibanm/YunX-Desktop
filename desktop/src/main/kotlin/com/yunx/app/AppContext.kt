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

    /**
     * 用户「文档」目录：Windows 下经 FileSystemView 取真实文档目录（可正确识别 OneDrive 重定向、
     * 中文「文档」），失败时回退 user.home/Documents。
     */
    val documentsDir: File by lazy {
        runCatching {
            val d = javax.swing.filechooser.FileSystemView.getFileSystemView().defaultDirectory
            if (d != null && d.isDirectory) d else null
        }.getOrNull() ?: File(System.getProperty("user.home"), "Documents")
    }

    /** 运行时自定义本地备份目录（由设置页注入；null = 默认 文档/YunX-Desktop） */
    @Volatile
    var customBackupDir: String? = null

    /** 本地备份根目录：自定义路径优先，否则 文档/YunX-Desktop */
    val backupDir: File
        get() = customBackupDir?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(documentsDir, "YunX-Desktop")

    /** 杂项偏好（原 "yunx_prefs" SharedPreferences：onboarding_shown / ignored_version） */
    val miscPrefs: Preferences = Preferences.userRoot().node("yunx/misc")

    /** 迅雷设备指纹偏好（原 "xunlei_device_fp"） */
    val xunleiFpPrefs: Preferences = Preferences.userRoot().node("yunx/xunlei_fp")

    fun init() {
        listOf(dataDir, cacheDir, downloadTmpDir, mergeDir, filesDir, backupDir).forEach { it.mkdirs() }
    }
}
