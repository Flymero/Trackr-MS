package com.trackr.ui.screens.me

import androidx.lifecycle.ViewModel
import com.trackr.core.data.repository.AmapKeyDefaults
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.AmapKeyRules
import com.trackr.core.domain.platform.AppSigningInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 「我的」里「高德 Key」那一行的状态文字（第 11 步起；第 25 步从「更多」页带过来） */
internal fun amapKeyStatusText(hasMapKey: Boolean, canShowMap: Boolean): String = when {
    canShowMap -> "已填，路线页显示地图"
    hasMapKey -> "只有 Web 服务 Key：能算路线，不显示地图"
    else -> "没填，不显示「路线」页"
}

/** 高德 Key 是 32 位字母数字；空着也行（等于清空） */
internal fun isAmapKeyFormatAcceptable(key: String): Boolean =
    key.isBlank() || key.length == 32 && key.all { it.isLetterOrDigit() }

/**
 * 高德 Key 页状态（第 11 步的对话框改成「我的」页签的子页）。
 *
 * 高德的「Android 平台 Key」和「Web 服务 Key」不通用：两个输入框各写各的槽，互不回落。
 *
 * @param showAndroidSlot 平台有高德 Android 地图 SDK 才显示 Android 槽（iOS 第 38 步之前没有）
 * @param appId 包名（iOS 是 Bundle ID），高德控制台绑定 Key 用
 * @param sha1 签名 SHA-1；平台没有（iOS）为 null，读不到为空串
 */
data class AmapKeyUiState(
    val showAndroidSlot: Boolean = true,
    val showIosSlot: Boolean = false,
    val privacyConsented: Boolean = false,
    val iosInput: String = "",
    val iosKeyValid: Boolean = true,
    val iosHint: String = "",
    val androidInput: String = "",
    val webInput: String = "",
    val androidKeyValid: Boolean = true,
    val webKeyValid: Boolean = true,
    val androidHint: String = "",
    val webHint: String = "",
    val statusText: String = "",
    val appId: String = "",
    val sha1: String? = null,
    val changed: Boolean = false,
    val closed: Boolean = false,
    val userMessages: List<UserMessage> = emptyList()
) {
    val canSave: Boolean get() = changed && androidKeyValid && iosKeyValid && webKeyValid
}

sealed interface AmapKeyEvent {
    data class EditIosKey(val text: String) : AmapKeyEvent
    data class EditAndroidKey(val text: String) : AmapKeyEvent
    data class EditWebKey(val text: String) : AmapKeyEvent
    data object RevokePrivacy : AmapKeyEvent
    data object Save : AmapKeyEvent
    /** 页面把文字放进剪贴板后发，[what] 是「包名」「SHA-1」「包名和 SHA-1」 */
    data class Copied(val what: String) : AmapKeyEvent
    /** 打不开浏览器 */
    data object OpenConsoleFailed : AmapKeyEvent
    data class MessageShown(val id: Long) : AmapKeyEvent
    data object Back : AmapKeyEvent
}

/** 高德控制台「我的应用」 */
const val AMAP_CONSOLE_URL: String = "https://console.amap.com/dev/key/app"

class AmapKeyViewModel(
    private val amapKeys: AmapKeyRepository,
    signingInfo: AppSigningInfo,
    private val defaults: AmapKeyDefaults,
    private val notices: MeNotices
) : ViewModel() {

    private val savedIosKey = amapKeys.iosKey.value.orEmpty()
    private val savedAndroidKey: String = amapKeys.androidKey.value.orEmpty()
    private val savedWebKey: String = amapKeys.webKey.value.orEmpty()

    private val _uiState = MutableStateFlow(
        derive(
            AmapKeyUiState(
                showAndroidSlot = defaults.hasAndroidMapSdk,
                showIosSlot = !defaults.hasAndroidMapSdk,
                privacyConsented = amapKeys.privacyConsent.value == true,
                iosInput = savedIosKey,
                androidInput = savedAndroidKey,
                webInput = savedWebKey,
                statusText = amapKeyStatusText(amapKeys.hasMapKey.value, amapKeys.canShowMap.value),
                appId = signingInfo.appId,
                sha1 = signingInfo.certificateSha1()
            )
        )
    )
    val uiState: StateFlow<AmapKeyUiState> = _uiState.asStateFlow()

    fun onEvent(event: AmapKeyEvent) {
        when (event) {
            // 粘贴时常带空格换行，去掉（和原来的对话框一样）
            is AmapKeyEvent.EditIosKey -> _uiState.update { derive(it.copy(iosInput = event.text.trim())) }
            is AmapKeyEvent.EditAndroidKey -> _uiState.update { derive(it.copy(androidInput = event.text.trim())) }
            is AmapKeyEvent.EditWebKey -> _uiState.update { derive(it.copy(webInput = event.text.trim())) }
            AmapKeyEvent.Save -> save()
            AmapKeyEvent.RevokePrivacy -> {
                amapKeys.setPrivacyConsent(false)
                _uiState.update { it.copy(privacyConsented = false) }
                addMessage("已撤回高德隐私授权，地图和路线规划已停用")
            }
            is AmapKeyEvent.Copied -> addMessage("${event.what}已复制")
            AmapKeyEvent.OpenConsoleFailed -> addMessage("没找到浏览器，请手动打开 console.amap.com")
            is AmapKeyEvent.MessageShown -> _uiState.update { state ->
                state.copy(userMessages = state.userMessages.filterNot { it.id == event.id })
            }
            AmapKeyEvent.Back -> Unit
        }
    }

    private fun save() {
        val state = _uiState.value
        if (!state.canSave || state.closed) return
        if (state.showIosSlot && state.iosInput != savedIosKey) amapKeys.setIosKey(state.iosInput.ifBlank { null })
        // 各写各的：只写改过的那一槽
        if (state.showAndroidSlot && state.androidInput != savedAndroidKey) {
            amapKeys.setAndroidKey(state.androidInput.ifBlank { null })
        }
        if (state.webInput != savedWebKey) {
            amapKeys.setWebKey(state.webInput.ifBlank { null })
        }
        notices.post("高德 Key 已保存：" + amapKeyStatusText(amapKeys.hasMapKey.value, amapKeys.canShowMap.value))
        _uiState.update { it.copy(closed = true) }
    }

    private fun derive(state: AmapKeyUiState): AmapKeyUiState {
        val androidValid = isAmapKeyFormatAcceptable(state.androidInput)
        val webValid = isAmapKeyFormatAcceptable(state.webInput)
        return state.copy(
            androidKeyValid = androidValid,
            iosKeyValid = isAmapKeyFormatAcceptable(state.iosInput),
            iosHint = if (isAmapKeyFormatAcceptable(state.iosInput)) "绑定上方 Bundle ID；仅用于 iOS 地图，规划仍需 Web Key" else "Key 是 32 位字母和数字",
            webKeyValid = webValid,
            androidHint = when {
                !androidValid -> "Key 是 32 位字母和数字，检查一下有没有粘全"
                AmapKeyRules.effective(state.androidInput, defaults.androidKey).isNotEmpty() -> "填了，路线页显示地图"
                else -> "不填就不显示地图"
            },
            webHint = when {
                !webValid -> "Key 是 32 位字母和数字，检查一下有没有粘全"
                AmapKeyRules.effective(state.webInput, defaults.webKey).isNotEmpty() -> "填了，用它搜地点、算路线"
                defaults.hasAndroidMapSdk -> "不填时只用地图 SDK 算路线"
                else -> "填写 Web Key 后可搜地点、算路线"
            },
            changed = (state.showIosSlot && state.iosInput != savedIosKey) || (state.showAndroidSlot && state.androidInput != savedAndroidKey) || state.webInput != savedWebKey
        )
    }

    private fun addMessage(text: String) {
        _uiState.update { it.copy(userMessages = it.userMessages.plusMessage(text)) }
    }
}
