package com.trackr.deeplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.trackr.MainActivity
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.core.navigation.deeplink.DeepLinkParser
import com.trackr.core.navigation.deeplink.DeepLinkUris

/**
 * Intent 和 `trackr://` 链接之间的转换（重构计划 §3.6）。`MainActivity` 只调这里和 [DeepLinkParser]。
 *
 * - 新：小组件、快捷方式、提醒通知、通知「乘车码」按钮发 `ACTION_VIEW` + `trackr://` data，显式指向 [MainActivity]。
 *   Manifest 不注册 `trackr://` 的 intent-filter，别的 App 不能隐式打开（§3.6、[IS]）。
 * - 旧（兼容一个版本，之后删）：6 个 `extra_*`（已固定到桌面的旧快捷方式、还没刷新的小组件、已发出的提醒通知）
 *   和 `OPEN_TRANSIT_CODE` action（旧版静态快捷方式、已发出的追踪通知）。先转成链接，再走同一个解析器和校验。
 */
object DeepLinkIntents {

    /** 旧版乘车码 action（兼容一个版本） */
    internal const val LEGACY_ACTION_OPEN_TRANSIT_CODE = "com.github.u5151561.trackr.ms.action.OPEN_TRANSIT_CODE"
    internal const val LEGACY_ACTION_OPEN_TRANSIT_CODE_SHORT = "com.trackr.action.OPEN_TRANSIT_CODE"
    internal const val LEGACY_EXTRA_PROVIDER = "extra_provider"

    /** 旧版跳线路详情的 6 个 extra（兼容一个版本） */
    internal const val LEGACY_EXTRA_CITY_ID = "extra_city_id"
    internal const val LEGACY_EXTRA_LINE_ID = "extra_line_id"
    internal const val LEGACY_EXTRA_TARGET_ORDER = "extra_target_order"
    internal const val LEGACY_EXTRA_STOP_ID = "extra_stop_id"
    internal const val LEGACY_EXTRA_SOURCE = "extra_source"
    internal const val LEGACY_EXTRA_LINE_NAME = "extra_line_name"

    private val LEGACY_LINE_EXTRAS = listOf(
        LEGACY_EXTRA_CITY_ID, LEGACY_EXTRA_LINE_ID, LEGACY_EXTRA_TARGET_ORDER,
        LEGACY_EXTRA_STOP_ID, LEGACY_EXTRA_SOURCE, LEGACY_EXTRA_LINE_NAME,
    )

    /** 打开 [MainActivity] 并带上 [link] 的显式 Intent（PendingIntent 用；flags 由调用方加）。 */
    fun activityIntent(context: Context, link: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(context, MainActivity::class.java)

    /** 解析 [intent] 里的链接；没有或不合法返回 null。 */
    fun parse(intent: Intent?): DeepLink? = DeepLinkParser.parse(linkOf(intent))

    /**
     * [intent] 带的 `trackr://` 链接：data 是 `trackr://` 就用它；否则把旧 action / 旧 extra 转成等价链接。
     * 读 extra 失败（别的 App 塞了坏 Bundle）当没有。
     */
    internal fun linkOf(intent: Intent?): String? {
        if (intent == null) return null
        intent.data?.takeIf { it.scheme.equals(DeepLinkUris.SCHEME, ignoreCase = true) }?.let { return it.toString() }
        return runCatching { legacyLinkOf(intent) }.getOrNull()
    }

    private fun legacyLinkOf(intent: Intent): String? {
        if (intent.action == LEGACY_ACTION_OPEN_TRANSIT_CODE || intent.action == LEGACY_ACTION_OPEN_TRANSIT_CODE_SHORT) {
            // 和旧代码一样：只有 alipay 是支付宝，其余都是云闪付
            val provider = if (intent.getStringExtra(LEGACY_EXTRA_PROVIDER) == TransitCodeProvider.ALIPAY.id) {
                TransitCodeProvider.ALIPAY
            } else {
                TransitCodeProvider.UNIONPAY
            }
            return DeepLinkUris.transitCode(provider)
        }
        val cityId = intent.getStringExtra(LEGACY_EXTRA_CITY_ID)
        val lineId = intent.getStringExtra(LEGACY_EXTRA_LINE_ID)
        if (cityId.isNullOrBlank() || lineId.isNullOrBlank()) return null
        val sourceName = intent.getStringExtra(LEGACY_EXTRA_SOURCE).orEmpty()
        return DeepLinkUris.line(
            cityId = cityId,
            lineId = lineId,
            source = DataSourceType.entries.firstOrNull { it.name == sourceName } ?: DataSourceType.CHELAILE,
            stopId = intent.getStringExtra(LEGACY_EXTRA_STOP_ID).orEmpty(),
            targetOrder = intent.getIntExtra(LEGACY_EXTRA_TARGET_ORDER, 0),
            lineName = intent.getStringExtra(LEGACY_EXTRA_LINE_NAME).orEmpty(),
        )
    }

    /**
     * 一次性消费：清掉 [intent] 里的链接（data、旧 extra、旧乘车码 action），配置变更后 / 再次读到同一个 Intent 时不重放。
     * 和旧代码一样只改这个 Intent 对象本身。
     */
    fun consume(intent: Intent?) {
        if (intent == null) return
        if (intent.data?.scheme.equals(DeepLinkUris.SCHEME, ignoreCase = true)) intent.data = null
        if (intent.action == LEGACY_ACTION_OPEN_TRANSIT_CODE || intent.action == LEGACY_ACTION_OPEN_TRANSIT_CODE_SHORT) {
            runCatching { intent.removeExtra(LEGACY_EXTRA_PROVIDER) }
            intent.action = null
        }
        // 和 linkOf 一样：别的 App 塞了坏 Bundle 时 removeExtra 也会读它，读不了就算了
        runCatching { LEGACY_LINE_EXTRAS.forEach(intent::removeExtra) }
    }
}
