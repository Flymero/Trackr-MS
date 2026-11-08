package com.trackr.core.data.repository

import com.trackr.core.data.local.AmapKeyStore
import com.trackr.core.data.local.SerialWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户自己填的高德 Key，两槽严格分开、互不回落（0a4fdab / 8a3e570）：
 * - [androidKey]：地图 / 搜索 / 定位 SDK 用，和包名 + 签名 SHA-1 绑定；
 * - [webKey]：restapi.amap.com 用，不绑定包名。
 *
 * 计划 §2.5 把 Key 两槽和由它算出的 [hasMapKey] / [canShowMap] 归在 SettingsRepository 下。
 * 第 9 步把 Key 单独成了这个接口（单独的文件、备份排除），第 11 步就把 [hasMapKey] / [canShowMap] 放在这里，
 * 不并进 SettingsRepository：规则只依赖两个 Key 槽，和别的设置无关。规则见 [AmapKeyRules]。
 */
interface AmapKeyRepository {
    val iosKey: StateFlow<String?>
    val effectiveIosKey: StateFlow<String>
    fun setIosKey(key: String?)

    /** 用户填的 Android 槽（没填为 null）；「更多」页的输入框编辑的就是它 */
    val androidKey: StateFlow<String?>

    /** 用户填的 Web 槽（没填为 null） */
    val webKey: StateFlow<String?>

    /** 生效的 Android Key：用户填的 > 构建时注入的；都没有为空串。不读 Web 槽 */
    val effectiveAndroidKey: StateFlow<String>

    /** 生效的 Web Key：用户填的 > 构建时注入的；都没有为空串。不读 Android 槽 */
    val effectiveWebKey: StateFlow<String>

    /** 底栏是否显示「路线」页签（D2）：Android Key 或 Web Key 有一个（Q18：只有 Web Key 也显示） */
    val hasMapKey: StateFlow<Boolean>

    /** 路线页是否画地图：要 Android Key（地图 SDK 只认它）；没有 Android 地图 SDK 的平台恒为 false */
    val canShowMap: StateFlow<Boolean>

    /** 高德隐私说明同意状态；null 表示尚未询问。 */
    val privacyConsent: StateFlow<Boolean?>

    /** 立刻生效，后台按顺序写盘；空串等于清空 */
    fun setAndroidKey(key: String?)
    fun setWebKey(key: String?)
    fun setPrivacyConsent(consented: Boolean)
}

/**
 * 构建时注入的 Key 和平台能力。
 * @param androidKey Android 是 `BuildConfig.AMAP_ANDROID_KEY`
 * @param webKey Android 是 `BuildConfig.AMAP_WEB_KEY`；iOS 构建时注入（第 35 步）
 * @param hasAndroidMapSdk 平台有没有高德 Android 地图 SDK。iOS 为 false：Android 槽不用，[AmapKeyRepository.canShowMap] 恒为 false（第 38 步之前）
 */
data class AmapKeyDefaults(
    val androidKey: String = "",
    val webKey: String = "",
    val hasAndroidMapSdk: Boolean = true,
    val iosKey: String = "",
    val hasIosMapSdk: Boolean = false
) {
    companion object {
        /** 没有内置 Key 的 Android（测试默认用它） */
        val NONE = AmapKeyDefaults()
    }
}

/** 由两槽算出的状态（计划 §2.5 的表） */
data class MapKeyStatus(
    val effectiveAndroidKey: String,
    val effectiveWebKey: String,
    val hasMapKey: Boolean,
    val canShowMap: Boolean,
    val effectiveIosKey: String = ""
)

/** §2.5 两槽规则的纯函数：各槽只看自己（用户填的 > 构建时的），互不回落 */
object AmapKeyRules {

    fun effective(userKey: String?, buildKey: String): String =
        userKey?.trim().orEmpty().ifEmpty { buildKey.trim() }

    fun status(userAndroidKey: String?, userWebKey: String?, defaults: AmapKeyDefaults, userIosKey: String? = null): MapKeyStatus {
        val ios = if (defaults.hasIosMapSdk) effective(userIosKey, defaults.iosKey) else ""
        val android = if (defaults.hasAndroidMapSdk) effective(userAndroidKey, defaults.androidKey) else ""
        val web = effective(userWebKey, defaults.webKey)
        return MapKeyStatus(
            effectiveAndroidKey = android,
            effectiveWebKey = web,
            hasMapKey = android.isNotEmpty() || ios.isNotEmpty() || web.isNotEmpty(),
            canShowMap = android.isNotEmpty() || ios.isNotEmpty(),
            effectiveIosKey = ios
        )
    }
}

/** 读写都经 [AmapKeyStore]；用 [create] 建，建好时两个值已经读出来 */
class DefaultAmapKeyRepository private constructor(
    private val store: AmapKeyStore,
    private val writer: SerialWriter,
    private val defaults: AmapKeyDefaults,
    initialAndroidKey: String?,
    initialWebKey: String?,
    initialPrivacyConsent: Boolean?,
    initialIosKey: String?
) : AmapKeyRepository {

    private val _iosKey = MutableStateFlow(initialIosKey)
    override val iosKey = _iosKey.asStateFlow()
    private val _effectiveIosKey = MutableStateFlow(AmapKeyRules.status(initialAndroidKey, initialWebKey, defaults, initialIosKey).effectiveIosKey)
    override val effectiveIosKey = _effectiveIosKey.asStateFlow()
    override fun setIosKey(key: String?) {
        val value = key?.trim()?.ifBlank { null }
        _iosKey.value = value
        refreshStatus()
        writer.enqueue { store.saveIosKey(value) }
    }

    private val _androidKey = MutableStateFlow(initialAndroidKey)
    override val androidKey: StateFlow<String?> = _androidKey.asStateFlow()

    private val _webKey = MutableStateFlow(initialWebKey)
    override val webKey: StateFlow<String?> = _webKey.asStateFlow()

    private val initialStatus = AmapKeyRules.status(initialAndroidKey, initialWebKey, defaults, initialIosKey)
    private val _effectiveAndroidKey = MutableStateFlow(initialStatus.effectiveAndroidKey)
    override val effectiveAndroidKey: StateFlow<String> = _effectiveAndroidKey.asStateFlow()
    private val _effectiveWebKey = MutableStateFlow(initialStatus.effectiveWebKey)
    override val effectiveWebKey: StateFlow<String> = _effectiveWebKey.asStateFlow()
    private val _hasMapKey = MutableStateFlow(initialStatus.hasMapKey)
    override val hasMapKey: StateFlow<Boolean> = _hasMapKey.asStateFlow()
    private val _canShowMap = MutableStateFlow(initialStatus.canShowMap)
    override val canShowMap: StateFlow<Boolean> = _canShowMap.asStateFlow()
    private val _privacyConsent = MutableStateFlow(initialPrivacyConsent)
    override val privacyConsent: StateFlow<Boolean?> = _privacyConsent.asStateFlow()

    override fun setAndroidKey(key: String?) {
        val value = key?.trim()?.ifBlank { null }
        _androidKey.value = value
        refreshStatus()
        writer.enqueue { store.saveAndroidKey(value) }
    }

    override fun setWebKey(key: String?) {
        val value = key?.trim()?.ifBlank { null }
        _webKey.value = value
        refreshStatus()
        writer.enqueue { store.saveWebKey(value) }
    }

    override fun setPrivacyConsent(consented: Boolean) {
        _privacyConsent.value = consented
        writer.enqueue { store.savePrivacyConsent(consented) }
    }

    /** 同步算，调用 setter 后马上读到新值（不用另开协程收集） */
    private fun refreshStatus() {
        val status = AmapKeyRules.status(_androidKey.value, _webKey.value, defaults, _iosKey.value)
        _effectiveIosKey.value = status.effectiveIosKey
        _effectiveAndroidKey.value = status.effectiveAndroidKey
        _effectiveWebKey.value = status.effectiveWebKey
        _hasMapKey.value = status.hasMapKey
        _canShowMap.value = status.canShowMap
    }

    /** 等排队的写入做完（测试用） */
    suspend fun flush() = writer.flush()

    companion object {
        suspend fun create(
            store: AmapKeyStore,
            writer: SerialWriter,
            defaults: AmapKeyDefaults = AmapKeyDefaults.NONE
        ): DefaultAmapKeyRepository =
            DefaultAmapKeyRepository(
                store,
                writer,
                defaults,
                store.loadAndroidKey(),
                store.loadWebKey(),
                store.loadPrivacyConsent(),
                store.loadIosKey()
            )
    }
}
