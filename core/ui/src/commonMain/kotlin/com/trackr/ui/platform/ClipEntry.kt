package com.trackr.ui.platform

import androidx.compose.ui.platform.ClipEntry

/**
 * 纯文本 [ClipEntry]：交给 `Clipboard.setClipEntry` 往系统剪贴板写一段文字。
 *
 * 各平台构造方式不同（Android 要包一层 `ClipData`，iOS 用 `ClipEntry.withPlainText`），
 * 所以放 expect/actual 里，commonMain 的页面直接拿它拼。
 */
expect fun plainTextClipEntry(text: String): ClipEntry
