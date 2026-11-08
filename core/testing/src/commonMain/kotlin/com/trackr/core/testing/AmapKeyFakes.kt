package com.trackr.core.testing

import com.trackr.core.data.repository.AmapKeyDefaults
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.AmapKeyRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内存里的高德 Key 两槽，派生值按真实规则 [AmapKeyRules] 算（第 11 步）。
 * 默认没有构建时 Key（[AmapKeyDefaults.NONE]），所以结果不受本机 `local.properties` 影响。
 */
class FakeAmapKeyRepository(
    androidKey: String? = null,
    webKey: String? = null,
    private val defaults: AmapKeyDefaults = AmapKeyDefaults.NONE,
    iosKey: String? = null
) : AmapKeyRepository {

    private val _iosKey = MutableStateFlow(iosKey)
    override val iosKey = _iosKey.asStateFlow()
    private val _effectiveIosKey = MutableStateFlow(AmapKeyRules.status(androidKey, webKey, defaults, iosKey).effectiveIosKey)
    override val effectiveIosKey = _effectiveIosKey.asStateFlow()
    val iosKeyWrites = mutableListOf<String?>()
    override fun setIosKey(key: String?) {
        iosKeyWrites += key
        _iosKey.value = key?.trim()?.ifBlank { null }
        refresh()
    }
    private val _androidKey = MutableStateFlow(androidKey?.trim()?.ifBlank { null })
    override val androidKey: StateFlow<String?> = _androidKey.asStateFlow()

    private val _webKey = MutableStateFlow(webKey?.trim()?.ifBlank { null })
    override val webKey: StateFlow<String?> = _webKey.asStateFlow()

    private val status get() = AmapKeyRules.status(_androidKey.value, _webKey.value, defaults, _iosKey.value)

    private val _effectiveAndroidKey = MutableStateFlow(status.effectiveAndroidKey)
    override val effectiveAndroidKey: StateFlow<String> = _effectiveAndroidKey.asStateFlow()
    private val _effectiveWebKey = MutableStateFlow(status.effectiveWebKey)
    override val effectiveWebKey: StateFlow<String> = _effectiveWebKey.asStateFlow()
    private val _hasMapKey = MutableStateFlow(status.hasMapKey)
    override val hasMapKey: StateFlow<Boolean> = _hasMapKey.asStateFlow()
    private val _canShowMap = MutableStateFlow(status.canShowMap)
    override val canShowMap: StateFlow<Boolean> = _canShowMap.asStateFlow()

    private val _privacyConsent = MutableStateFlow<Boolean?>(null)
    override val privacyConsent: StateFlow<Boolean?> = _privacyConsent.asStateFlow()

    /** 每次 setter 收到的值（原样，未 trim） */
    val androidKeyWrites: MutableList<String?> = mutableListOf()
    val webKeyWrites: MutableList<String?> = mutableListOf()

    override fun setAndroidKey(key: String?) {
        androidKeyWrites += key
        _androidKey.value = key?.trim()?.ifBlank { null }
        refresh()
    }

    override fun setWebKey(key: String?) {
        webKeyWrites += key
        _webKey.value = key?.trim()?.ifBlank { null }
        refresh()
    }

    override fun setPrivacyConsent(consented: Boolean) {
        _privacyConsent.value = consented
    }

    private fun refresh() {
        val s = status
        _effectiveIosKey.value = s.effectiveIosKey
        _effectiveAndroidKey.value = s.effectiveAndroidKey
        _effectiveWebKey.value = s.effectiveWebKey
        _hasMapKey.value = s.hasMapKey
        _canShowMap.value = s.canShowMap
    }
}
