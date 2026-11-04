@file:OptIn(ExperimentalMaterial3Api::class)

package com.trackr.ui.screens.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.action_close
import com.trackr.core.designsystem.resources.action_retry
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_bus
import com.trackr.core.designsystem.resources.ic_check
import com.trackr.core.designsystem.resources.ic_warning
import com.trackr.core.model.AppUpdateInstallState
import com.trackr.feature.me.resources.Res as MeRes
import com.trackr.feature.me.resources.me_about_changelog
import com.trackr.feature.me.resources.me_about_check_failed
import com.trackr.feature.me.resources.me_about_check_failed_retry
import com.trackr.feature.me.resources.me_about_check_update
import com.trackr.feature.me.resources.me_about_checking
import com.trackr.feature.me.resources.me_about_data_body
import com.trackr.feature.me.resources.me_about_data_title
import com.trackr.feature.me.resources.me_about_cancel_download
import com.trackr.feature.me.resources.me_about_download
import com.trackr.feature.me.resources.me_about_downloading
import com.trackr.feature.me.resources.me_about_downloading_bytes
import com.trackr.feature.me.resources.me_about_got_it
import com.trackr.feature.me.resources.me_about_install_again
import com.trackr.feature.me.resources.me_about_install_launched
import com.trackr.feature.me.resources.me_about_install_permission
import com.trackr.feature.me.resources.me_about_install_retry
import com.trackr.feature.me.resources.me_about_latest
import com.trackr.feature.me.resources.me_about_latest_body
import com.trackr.feature.me.resources.me_about_license
import com.trackr.feature.me.resources.me_about_live_body_1
import com.trackr.feature.me.resources.me_about_live_body_2
import com.trackr.feature.me.resources.me_about_live_title
import com.trackr.feature.me.resources.me_about_new_version
import com.trackr.feature.me.resources.me_about_new_version_name
import com.trackr.feature.me.resources.me_about_open_download_page
import com.trackr.feature.me.resources.me_about_open_install_settings
import com.trackr.feature.me.resources.me_about_publish_time
import com.trackr.feature.me.resources.me_about_remind_later
import com.trackr.feature.me.resources.me_about_tap_to_check
import com.trackr.feature.me.resources.me_about_title
import com.trackr.feature.me.resources.me_about_up_to_date
import com.trackr.feature.me.resources.me_about_version
import com.trackr.feature.me.resources.me_about_version_unknown
import com.trackr.feature.me.resources.me_app_name
import com.trackr.feature.me.resources.me_edition_label
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 关于页（第 25 步从 `androidMain` 的旧页改成 commonMain）。
 *
 * 「检查更新」只有 Android 有（`AboutUiState.canCheckUpdates`，§2.9）；iOS 走 App Store，这一行不显示。
 * 应用图标是设计系统里的公交图标，不读平台 Manifest，保证 iOS 也画得出来。
 */
@Composable
fun AboutScreen(
    state: AboutUiState,
    onEvent: (AboutEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(MeRes.string.me_about_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(AboutEvent.Back) }) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_back),
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = vectorResource(Res.drawable.ic_bus),
                                contentDescription = stringResource(MeRes.string.me_app_name),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = stringResource(MeRes.string.me_app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(MeRes.string.me_edition_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (state.versionName.isBlank()) stringResource(MeRes.string.me_about_version_unknown) else stringResource(MeRes.string.me_about_version, state.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (state.canCheckUpdates) {
                        UpdateRow(state = state, onClick = { onEvent(AboutEvent.CheckForUpdates) })
                        DottedDivider()
                    }

                    LinkRow(
                        title = "GitHub",
                        value = "5151561/Trackr",
                        onClick = { uriHandler.openUri(PROJECT_URL) }
                    )

                    DottedDivider()

                    TextRow(title = stringResource(MeRes.string.me_about_license), value = "MIT License")

                    DottedDivider()

                    TextBlock(
                        title = stringResource(MeRes.string.me_about_data_title),
                        body = stringResource(MeRes.string.me_about_data_body)
                    )

                    if (state.showLiveUpdateNote) {
                        DottedDivider()
                        TextBlock(
                            title = stringResource(MeRes.string.me_about_live_title),
                            body = stringResource(MeRes.string.me_about_live_body_1) +
                                stringResource(MeRes.string.me_about_live_body_2)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Copyright (c) 2026 busonroad contributors",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }

    UpdateDialogs(
        update = state.update,
        install = state.install,
        onStartInstall = { onEvent(AboutEvent.StartInstall) },
        onCancelInstall = { onEvent(AboutEvent.CancelInstall) },
        onOpenInstallSettings = { onEvent(AboutEvent.OpenInstallSettings) },
        onRetryInstall = { onEvent(AboutEvent.RetryInstall) },
        onOpenDownloadPage = { uriHandler.openUri(it) },
        onRetry = { onEvent(AboutEvent.CheckForUpdates) },
        onDismiss = { onEvent(AboutEvent.DismissUpdateDialog) }
    )
}

/** 「检查新版本」那一行：检查中转圈，发现新版本给个标签 */
@Composable
private fun UpdateRow(state: AboutUiState, onClick: () -> Unit) {
    val checking = state.update is AboutUpdateUiState.Checking
    // 下载安装包时别再点出新的一次检查，否则正在下载的弹窗会被换成「正在检查」
    val busy = checking || state.install is AppUpdateInstallState.Downloading
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(MeRes.string.me_about_check_update),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        when (val update = state.update) {
            is AboutUpdateUiState.Checking -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(MeRes.string.me_about_checking),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is AboutUpdateUiState.HasUpdate -> {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = stringResource(MeRes.string.me_about_new_version),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            is AboutUpdateUiState.UpToDate -> Text(
                text = stringResource(MeRes.string.me_about_up_to_date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            is AboutUpdateUiState.Error -> Text(
                text = stringResource(MeRes.string.me_about_check_failed_retry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            AboutUpdateUiState.Idle -> Text(
                text = stringResource(MeRes.string.me_about_tap_to_check),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun TextRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TextBlock(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DottedDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

/** 更新检查的三种结果弹窗；Idle / Checking 不弹 */
@Composable
private fun UpdateDialogs(
    update: AboutUpdateUiState,
    install: AppUpdateInstallState,
    onStartInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onRetryInstall: () -> Unit,
    onOpenDownloadPage: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    when (update) {
        is AboutUpdateUiState.HasUpdate -> {
            val downloading = install is AppUpdateInstallState.Downloading
            AlertDialog(
                onDismissRequest = { if (!update.isForce && !downloading) onDismiss() },
                icon = {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_bus),
                        contentDescription = stringResource(MeRes.string.me_about_new_version),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = stringResource(MeRes.string.me_about_new_version_name, update.updateInfo.versionName),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!update.updateInfo.publishTime.isNullOrBlank()) {
                            Text(
                                text = stringResource(MeRes.string.me_about_publish_time, update.updateInfo.publishTime.toString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = stringResource(MeRes.string.me_about_changelog),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = update.updateInfo.changelog,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        InstallStatus(install)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            when (install) {
                                is AppUpdateInstallState.Downloading -> onCancelInstall()
                                AppUpdateInstallState.AwaitingPermission -> onOpenInstallSettings()
                                AppUpdateInstallState.Launched -> onRetryInstall()
                                is AppUpdateInstallState.Failed -> onStartInstall()
                                AppUpdateInstallState.Idle -> onStartInstall()
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                when (install) {
                                    is AppUpdateInstallState.Downloading -> MeRes.string.me_about_cancel_download
                                    AppUpdateInstallState.AwaitingPermission -> MeRes.string.me_about_open_install_settings
                                    AppUpdateInstallState.Launched -> MeRes.string.me_about_install_again
                                    is AppUpdateInstallState.Failed -> MeRes.string.me_about_install_retry
                                    AppUpdateInstallState.Idle -> MeRes.string.me_about_download
                                }
                            )
                        )
                    }
                },
                dismissButton = when {
                    // 应用内装不了时留个出口：用浏览器打开发布页
                    install is AppUpdateInstallState.Failed -> {
                        {
                            TextButton(onClick = { onOpenDownloadPage(update.updateInfo.downloadUrl) }) {
                                Text(stringResource(MeRes.string.me_about_open_download_page))
                            }
                        }
                    }
                    !update.isForce && !downloading -> {
                        {
                            TextButton(onClick = onDismiss) { Text(stringResource(MeRes.string.me_about_remind_later)) }
                        }
                    }
                    else -> null
                }
            )
        }

        is AboutUpdateUiState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_check),
                    contentDescription = stringResource(MeRes.string.me_about_latest),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(MeRes.string.me_about_latest),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(MeRes.string.me_about_latest_body, update.currentVersionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(MeRes.string.me_about_got_it)) }
            }
        )

        is AboutUpdateUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_warning),
                    contentDescription = stringResource(MeRes.string.me_about_check_failed),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(MeRes.string.me_about_check_failed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = update.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) }
            }
        )

        AboutUpdateUiState.Idle, AboutUpdateUiState.Checking -> Unit
    }
}

/** 更新弹窗里的下载 / 授权 / 安装状态 */
@Composable
private fun InstallStatus(install: AppUpdateInstallState) {
    when (install) {
        is AppUpdateInstallState.Downloading -> {
            if (install.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = {
                        (install.downloadedBytes.toFloat() / install.totalBytes.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        MeRes.string.me_about_downloading,
                        ((install.downloadedBytes * 100) / install.totalBytes).toInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 服务器没给总长度：转圈 + 已下载多少兆
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(
                        MeRes.string.me_about_downloading_bytes,
                        (install.downloadedBytes / (1024 * 1024)).toInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        AppUpdateInstallState.AwaitingPermission -> Text(
            text = stringResource(MeRes.string.me_about_install_permission),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        AppUpdateInstallState.Launched -> Text(
            text = stringResource(MeRes.string.me_about_install_launched),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )

        is AppUpdateInstallState.Failed -> Text(
            text = install.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )

        AppUpdateInstallState.Idle -> Unit
    }
}
