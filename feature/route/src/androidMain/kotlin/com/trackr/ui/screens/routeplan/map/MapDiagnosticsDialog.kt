package com.trackr.ui.screens.routeplan.map

import android.os.Build
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_close
import com.trackr.core.designsystem.resources.action_copy
import com.trackr.core.domain.platform.AppSigningInfo
import com.trackr.data.remote.amap.AmapSdkManager
import com.trackr.feature.route.resources.Res as RouteRes
import com.trackr.feature.route.resources.route_map_diag_title
import com.trackr.ui.platform.plainTextClipEntry
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * 地图诊断信息：把包名 / SHA-1 / 实际生效的 Key 摆出来，方便对照高德控制台排查。
 * 第 24 步从旧 `RoutePlanScreen.kt` 原样搬来（只在 Android 有）。
 */
@Composable
internal fun MapDiagnosticsDialog(
    androidKey: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()

    val packageName = context.packageName
    // 签名 SHA-1 经 AppSigningInfo 取（Android 实现转调 SignatureUtils，取不到时为空串）
    val signingInfo: AppSigningInfo = koinInject()
    val sha1 = remember { signingInfo.certificateSha1().orEmpty() }
    val effectiveKey = remember(androidKey) { AmapSdkManager.getEffectiveApiKey(androidKey) }
    val keySource = when {
        !androidKey.isNullOrBlank() -> "应用内配置"
        effectiveKey.isNotEmpty() -> "编译期注入（local.properties / 环境变量）"
        else -> "未配置"
    }
    val maskedKey = if (effectiveKey.isEmpty()) {
        "未配置"
    } else {
        "${effectiveKey.take(4)}…${effectiveKey.takeLast(4)}（${effectiveKey.length} 位）"
    }
    val sdkVersion = remember { runCatching { com.amap.api.maps.MapsInitializer.getVersion() }.getOrDefault("未知") }

    val report = buildString {
        appendLine("包名：$packageName")
        appendLine("SHA-1：${sha1.ifBlank { "读取失败" }}")
        appendLine("生效 Key：$maskedKey")
        appendLine("Key 来源：$keySource")
        appendLine("设备 ABI：${Build.SUPPORTED_ABIS.joinToString("/")}")
        appendLine("地图 SDK：$sdkVersion")
        appendLine("鉴权失败常见原因：Key 未绑定当前包名或 SHA-1；把 Web 服务 Key 当地图 Key 用；Key 未通过审核/未勾选协议。")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(RouteRes.string.route_map_diag_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
        text = {
            Text(
                text = report,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    clipboardScope.launch { clipboard.setClipEntry(plainTextClipEntry(report)) }
                    Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.testTag("btn_copy_diagnostics")
            ) {
                Text(stringResource(Res.string.action_copy))
            }
        }
    )
}
