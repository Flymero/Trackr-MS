@file:OptIn(ExperimentalMaterial3Api::class)

package com.trackr.ui.screens.me

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.component.TrackrBottomActionBar
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.action_clear
import com.trackr.core.designsystem.resources.action_copy
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.feature.me.resources.Res as MeRes
import com.trackr.feature.me.resources.me_amap_android_key
import com.trackr.feature.me.resources.me_amap_android_key_hint
import com.trackr.feature.me.resources.me_amap_bind_title
import com.trackr.feature.me.resources.me_amap_copy_both
import com.trackr.feature.me.resources.me_amap_ios_intro
import com.trackr.feature.me.resources.me_amap_intro_1
import com.trackr.feature.me.resources.me_amap_intro_2
import com.trackr.feature.me.resources.me_amap_intro_3
import com.trackr.feature.me.resources.me_amap_open_console
import com.trackr.feature.me.resources.me_amap_package
import com.trackr.feature.me.resources.me_amap_save
import com.trackr.feature.me.resources.me_amap_sha1
import com.trackr.feature.me.resources.me_amap_title
import com.trackr.feature.me.resources.me_amap_unreadable
import com.trackr.feature.me.resources.me_amap_web_key
import com.trackr.feature.me.resources.me_amap_web_key_hint
import com.trackr.feature.me.resources.me_amap_web_key_optional
import com.trackr.ui.platform.plainTextClipEntry
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 高德 Key 子页（第 25 步把原来的对话框改成「我的」页签里的子页）。
 *
 * 两个槽各写各的、互不回落：Android 平台 Key 决定地图出不出图，Web 服务 Key 只给云端算路用。
 * 页面只显示平台确实有的槽（`showAndroidSlot`）；包名和签名 SHA-1 由 ViewModel 从平台能力读出来，方便复制去控制台绑定。
 */
@Composable
fun AmapKeyScreen(
    state: AmapKeyUiState,
    onEvent: (AmapKeyEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    fun copy(text: String, what: String) {
        clipboardScope.launch { clipboard.setClipEntry(plainTextClipEntry(text)) }
        onEvent(AmapKeyEvent.Copied(what))
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { MeMessageHost(state.userMessages) { onEvent(AmapKeyEvent.MessageShown(it)) } },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(MeRes.string.me_amap_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(AmapKeyEvent.Back) }) {
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
        },
        bottomBar = {
            TrackrBottomActionBar {
                Button(
                    onClick = { onEvent(AmapKeyEvent.Save) },
                    enabled = state.canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("btn_amap_key_save")
                ) {
                    Text(stringResource(MeRes.string.me_amap_save))
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "intro") {
                Text(
                    text = if (state.showAndroidSlot) stringResource(MeRes.string.me_amap_intro_1) +
                        stringResource(MeRes.string.me_amap_intro_2) +
                        stringResource(MeRes.string.me_amap_intro_3)
                    else stringResource(MeRes.string.me_amap_ios_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item(key = "identity") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(MeRes.string.me_amap_bind_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        IdentityRow(
                            label = if (state.showAndroidSlot) stringResource(MeRes.string.me_amap_package) else "Bundle ID",
                            value = state.appId,
                            onCopy = { copy(state.appId, if (state.showAndroidSlot) "包名" else "Bundle ID") }
                        )
                        if (state.showAndroidSlot) {
                            Spacer(modifier = Modifier.height(8.dp))
                            IdentityRow(
                                label = stringResource(MeRes.string.me_amap_sha1),
                                value = state.sha1?.takeIf { it.isNotBlank() } ?: stringResource(MeRes.string.me_amap_unreadable),
                                onCopy = state.sha1?.takeIf { it.isNotBlank() }?.let { sha1 ->
                                    { copy(sha1, "SHA-1") }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (state.showAndroidSlot) {
                                TextButton(
                                    onClick = {
                                        val text = buildString {
                                            append("包名：${state.appId}")
                                            state.sha1?.takeIf { it.isNotBlank() }?.let { append("\nSHA-1：$it") }
                                        }
                                        copy(text, "包名和 SHA-1")
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.testTag("btn_copy_pkg_sha1")
                                ) {
                                    Text(stringResource(MeRes.string.me_amap_copy_both), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            TextButton(
                                onClick = {
                                    runCatching { uriHandler.openUri(AMAP_CONSOLE_URL) }
                                        .onFailure { onEvent(AmapKeyEvent.OpenConsoleFailed) }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.testTag("btn_open_amap_console")
                            ) {
                                Text(stringResource(MeRes.string.me_amap_open_console), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            if (state.showAndroidSlot) {
                item(key = "android_key") {
                    OutlinedTextField(
                        value = state.androidInput,
                        onValueChange = { onEvent(AmapKeyEvent.EditAndroidKey(it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_amap_android_key"),
                        label = { Text(stringResource(MeRes.string.me_amap_android_key)) },
                        placeholder = { Text(stringResource(MeRes.string.me_amap_android_key_hint)) },
                        isError = !state.androidKeyValid,
                        supportingText = {
                            Text(text = state.androidHint, style = MaterialTheme.typography.labelSmall)
                        },
                        trailingIcon = {
                            if (state.androidInput.isNotEmpty()) {
                                TextButton(onClick = { onEvent(AmapKeyEvent.EditAndroidKey("")) }) {
                                    Text(stringResource(Res.string.action_clear), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                        singleLine = true
                    )
                }
            }

            if (state.showIosSlot) item(key = "ios_key") {
                OutlinedTextField(
                    value = state.iosInput,
                    onValueChange = { onEvent(AmapKeyEvent.EditIosKey(it)) },
                    modifier = Modifier.fillMaxWidth().testTag("input_amap_ios_key"),
                    label = { Text("iOS 地图 Key（可选）") },
                    isError = !state.iosKeyValid,
                    supportingText = { Text(state.iosHint, style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = { if (state.iosInput.isNotEmpty()) TextButton(onClick = { onEvent(AmapKeyEvent.EditIosKey("")) }) {
                        Text(stringResource(Res.string.action_clear), style = MaterialTheme.typography.labelSmall)
                    } },
                    singleLine = true
                )
            }

            item(key = "web_key") {
                OutlinedTextField(
                    value = state.webInput,
                    onValueChange = { onEvent(AmapKeyEvent.EditWebKey(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_amap_web_key"),
                    label = { Text(if (state.showAndroidSlot) stringResource(MeRes.string.me_amap_web_key_optional) else stringResource(MeRes.string.me_amap_web_key)) },
                    placeholder = { Text(stringResource(MeRes.string.me_amap_web_key_hint)) },
                    isError = !state.webKeyValid,
                    supportingText = {
                        Text(text = state.webHint, style = MaterialTheme.typography.labelSmall)
                    },
                    trailingIcon = {
                        if (state.webInput.isNotEmpty()) {
                            TextButton(onClick = { onEvent(AmapKeyEvent.EditWebKey("")) }) {
                                Text(stringResource(Res.string.action_clear), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    singleLine = true
                )
            }

            if (state.showIosSlot && state.privacyConsented) item(key = "revoke_privacy") {
                TextButton(onClick = { onEvent(AmapKeyEvent.RevokePrivacy) }) { Text("撤回高德隐私授权") }
            }
            item(key = "status") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = state.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 一行「标签 + 等宽值 + 复制按钮」 */
@Composable
private fun IdentityRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (onCopy != null) {
            TextButton(onClick = onCopy) {
                Text(stringResource(Res.string.action_copy), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
