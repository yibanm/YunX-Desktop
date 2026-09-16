package com.yunx.app.ui.clipboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.yunx.app.data.network.ParsedShare
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * 剪贴板识别到的网盘分享链接。
 * @param text 剪贴板原文（含链接与提取码，交给解析页直接解析）
 * @param parsed 解析结果（平台 / shareId / 提取码）
 */
data class ClipboardDetection(
    val text: String,
    val parsed: ParsedShare
)

/**
 * 剪贴板弹窗的全局状态桥：弹窗是独立顶层窗口，主窗口内只读状态，
 * 「打开」通过 openRequest 交给 MainScreen 切换到解析页。
 */
object ClipboardLinkController {

    /** 当前待展示的弹窗内容；null 表示不显示 */
    var pending by mutableStateOf<ClipboardDetection?>(null)
        private set

    /** 用户点击「打开」后待消费的检测结果 */
    var openRequest by mutableStateOf<ClipboardDetection?>(null)
        private set

    /** 主窗口引用（Main.kt 挂载），用于判断窗口是否失焦、打开时置前 */
    var mainWindow: Frame? = null

    fun show(detection: ClipboardDetection) {
        pending = detection
    }

    /** 忽略 / 关闭 / 超时自动消失 */
    fun dismiss() {
        pending = null
    }

    /** 点击「打开」：关弹窗并通知主窗口解析 */
    fun open() {
        val d = pending
        pending = null
        openRequest = d
    }

    fun consumeOpen(): ClipboardDetection? {
        val d = openRequest
        openRequest = null
        return d
    }

    /** 平台中文名（弹窗标题用） */
    fun platformName(platform: SharePlatform): String = when (platform) {
        SharePlatform.QUARK -> "夸克"
        SharePlatform.UC -> "UC"
        SharePlatform.XUNLEI -> "迅雷"
        SharePlatform.BAIDU -> "百度"
        SharePlatform.C139 -> "139"
        SharePlatform.PAN123 -> "123"
    }
}

/**
 * 剪贴板监听：每 1 秒轮询系统剪贴板。
 * 仅当「剪贴板内容变化 + 是受支持的网盘分享链接 + 主窗口失焦 + 无弹窗在展示」时
 * 触发右下角弹窗——窗口聚焦时用户自己粘贴即可，不打扰。
 */
@Composable
fun ClipboardLinkDetector() {
    LaunchedEffect(Unit) {
        var lastText: String? = null
        while (true) {
            withContext(Dispatchers.IO) {
                val text = readClipboardText()
                if (text != null && text != lastText) {
                    lastText = text
                    val main = ClipboardLinkController.mainWindow
                    val notFocused = main == null || !main.isFocused
                    val parsed = ShareLinkParser.parse(text)
                    if (parsed != null && notFocused && ClipboardLinkController.pending == null) {
                        ClipboardLinkController.show(ClipboardDetection(text, parsed))
                    }
                }
            }
            delay(1000)
        }
    }
}

private fun readClipboardText(): String? = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return@runCatching null
    (clipboard.getData(DataFlavor.stringFlavor) as? String)?.takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * 右下角弹窗（独立无边框置顶窗口）：
 * 云朵图标 + 「发现X网盘分享链接」 + 链接预览，按钮为 忽略 / 打开，右上角 ×。
 * 沿用应用原有 Material3 材质（colorScheme.surface / primaryContainer / shapes），
 * 不抢焦点、10 秒无操作自动消失。
 */
@Composable
fun ClipboardLinkPopup() {
    val detection = ClipboardLinkController.pending ?: return
    Window(
        onCloseRequest = { ClipboardLinkController.dismiss() },
        undecorated = true,
        alwaysOnTop = true,
        visible = true,
        resizable = false,
        focusable = false,
        title = "云析",
        state = rememberWindowState(
            size = DpSize(400.dp, 132.dp),
            position = WindowPosition(Alignment.BottomEnd)
        )
    ) {
        val window = this.window
        // 弹窗不抢焦点（否则一出现主窗口就被激活，破坏「失焦检测」语义）
        LaunchedEffect(Unit) {
            runCatching {
                window.focusableWindowState = false
                window.isFocusable = false
                window.isAutoRequestFocus = false
                window.type = java.awt.Window.Type.POPUP
            }
            // 右下角定位：屏幕右下角留 24px 边距，避开任务栏/系统托盘
            val margin = 24
            while (true) {
                val w = window.width
                val h = window.height
                if (w > 0 && h > 0) {
                    val gc = window.graphicsConfiguration
                    val bounds = gc.bounds
                    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
                    window.setLocation(
                        bounds.x + bounds.width - w - margin - insets.right,
                        bounds.y + bounds.height - h - margin - insets.bottom
                    )
                    break
                }
                delay(50)
            }
        }
        // 10 秒无操作自动消失
        LaunchedEffect(Unit) {
            delay(10_000)
            ClipboardLinkController.dismiss()
        }

        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Cloud,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "发现${ClipboardLinkController.platformName(detection.parsed.platform)}网盘分享链接",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = detection.text.trim().replace("\n", " ").take(48),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { ClipboardLinkController.dismiss() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { ClipboardLinkController.dismiss() }) {
                            Text("忽略")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { ClipboardLinkController.open() }) {
                            Text("打开")
                        }
                    }
                }
            }
        }
    }
}
