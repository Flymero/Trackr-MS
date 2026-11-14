package com.trackr.core.navigation.deeplink

import com.trackr.core.model.DataSourceType
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.TopLevel
import com.trackr.core.navigation.deeplink.DeepLinkUris.HOST_LINE
import com.trackr.core.navigation.deeplink.DeepLinkUris.HOST_ROUTE
import com.trackr.core.navigation.deeplink.DeepLinkUris.HOST_STOP
import com.trackr.core.navigation.deeplink.DeepLinkUris.HOST_TRACKING
import com.trackr.core.navigation.deeplink.DeepLinkUris.HOST_TRANSIT_CODE
import com.trackr.core.navigation.deeplink.DeepLinkUris.PATH_BOARD
import com.trackr.core.navigation.deeplink.DeepLinkUris.Q_NAME
import com.trackr.core.navigation.deeplink.DeepLinkUris.Q_ORDER
import com.trackr.core.navigation.deeplink.DeepLinkUris.Q_PROVIDER
import com.trackr.core.navigation.deeplink.DeepLinkUris.Q_SESSION
import com.trackr.core.navigation.deeplink.DeepLinkUris.Q_CITY
import com.trackr.core.navigation.deeplink.DeepLinkUris.Q_CITY_CODE
import com.trackr.core.navigation.deeplink.DeepLinkUris.Q_SOURCE
import com.trackr.core.navigation.deeplink.DeepLinkUris.Q_STOP

/**
 * 把 `trackr://` 链接解析成 [DeepLink]（重构计划 §3.6）。纯 Kotlin，`DeepLinkParserTest`（commonTest）直接测。
 *
 * `MainActivity` 是导出的，链接可能来自别的 App，所以这里是信任边界：
 * - scheme 不是 `trackr`、host 不认识、路径段数不对、百分号编码坏了 → null（忽略）。
 * - id（城市、线路、站、路线、会话）：非空、≤ [MAX_ID_LENGTH] 个字符、不含控制字符。
 *   计划写的 `[A-Za-z0-9:_-]{1,64}` 会挡掉掌上公交的 id（`mg:0755:深圳:M492路:0` 带中文），所以没照用。
 * - 展示用的名字：去掉控制字符，超过 [MAX_NAME_LENGTH] 个字符截断。
 * - 只产出 [ScreenKey]，不转发任何 Intent。
 */
object DeepLinkParser {
    const val MAX_ID_LENGTH = 256
    const val MAX_NAME_LENGTH = 64

    /** 站序上限：再大就当没给（线路没有上千站） */
    private const val MAX_ORDER = 10_000

    fun parse(uri: String?): DeepLink? {
        val parts = split(uri ?: return null) ?: return null
        return when (parts.host) {
            HOST_LINE -> parseLine(parts)
            HOST_ROUTE -> parts.path.singleOrNull()?.validId()?.let { groupId ->
                DeepLink.Stack(TopLevel.start, listOf(ScreenKey.RouteDetail(groupId)))
            }
            HOST_STOP -> parseStop(parts)
            HOST_TRACKING -> parts.takeIf { it.path == listOf(PATH_BOARD) }
                ?.query?.get(Q_SESSION)?.validId()
                ?.let { DeepLink.ChooseBoardedBus(it) }
            HOST_TRANSIT_CODE -> parts.takeIf { it.path.isEmpty() }?.let {
                // 和旧的 OPEN_TRANSIT_CODE 一样：只有 alipay 是支付宝，其余（含没给）都是云闪付
                val provider = if (it.query[Q_PROVIDER] == TransitCodeProvider.ALIPAY.id) {
                    TransitCodeProvider.ALIPAY
                } else {
                    TransitCodeProvider.UNIONPAY
                }
                DeepLink.TransitCode(provider)
            }
            else -> null
        }
    }

    private fun parseLine(parts: UriParts): DeepLink? {
        if (parts.path.size != 2) return null
        val cityId = parts.path[0].validId() ?: return null
        val lineId = parts.path[1].validId() ?: return null
        val q = parts.query
        // 和旧的 extra 一样：数据源不认识就当车来了，站序不是数字就当 0
        val source = q[Q_SOURCE]?.let { name -> DataSourceType.entries.firstOrNull { it.name == name } }
            ?: DataSourceType.CHELAILE
        val order = q[Q_ORDER]?.toIntOrNull()?.takeIf { it in 0..MAX_ORDER } ?: 0
        val key = ScreenKey.LineDetail(
            cityId = cityId,
            lineId = lineId,
            targetOrder = order,
            stopId = q[Q_STOP]?.validId().orEmpty(),
            source = source,
            lineName = q[Q_NAME].displayName(),
        )
        return DeepLink.Stack(TopLevel.start, listOf(key))
    }

    private fun parseStop(parts: UriParts): DeepLink? {
        val q = parts.query
        // 数据源不认识就不设置（沿用当前引擎模式），与线路深链的 CHELAILE 默认不同：站点页来源敏感
        val source = q[Q_SOURCE]?.let { name -> DataSourceType.entries.firstOrNull { it.name == name } }
        val cityName = q[Q_CITY].displayName()
        val cityCode = q[Q_CITY_CODE]?.validId().orEmpty()
        val cityId: String
        val stopId: String
        when (parts.path.size) {
            2 -> {
                cityId = parts.path[0].validId() ?: return null
                stopId = parts.path[1].validId() ?: return null
            }
            1 -> {
                // 眉山/仁寿官方源 cityId 为空：城市身份由 city/cityCode 查询参数承载，只认官方源 + 非空城市名
                if (source != DataSourceType.MEISHAN || cityName.isBlank()) return null
                cityId = ""
                stopId = parts.path[0].validId() ?: return null
            }
            else -> return null
        }
        val key = ScreenKey.StopDetail(
            cityId = cityId,
            stopId = stopId,
            stopName = q[Q_NAME].displayName(),
            source = source,
            cityName = cityName,
            mygolbsCityCode = cityCode,
        )
        return DeepLink.Stack(TopLevel.start, listOf(key))
    }

    private fun String.validId(): String? =
        takeIf { it.isNotBlank() && it.length <= MAX_ID_LENGTH && it.none(Char::isISOControl) }

    private fun String?.displayName(): String =
        this?.filterNot(Char::isISOControl)?.take(MAX_NAME_LENGTH).orEmpty()

    private class UriParts(val host: String, val path: List<String>, val query: Map<String, String>)

    /**
     * 切出 host、解码后的路径段、解码后的查询参数（同名取第一个）。`#` 之后丢掉。
     * 不认 userinfo、端口：`trackr://` 链接用不到，出现了就因 host 不认识而忽略。
     */
    private fun split(raw: String): UriParts? {
        if (raw.length > MAX_URI_LENGTH) return null
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0 || !raw.substring(0, schemeEnd).equals(DeepLinkUris.SCHEME, ignoreCase = true)) return null
        val rest = raw.substring(schemeEnd + 3).substringBefore('#')
        val beforeQuery = rest.substringBefore('?')
        val queryString = if ('?' in rest) rest.substringAfter('?') else ""
        val host = beforeQuery.substringBefore('/').lowercase()
        val pathSegments = if ('/' in beforeQuery) {
            beforeQuery.substringAfter('/').split('/').filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val path = pathSegments.map { UriCodec.decode(it) ?: return null }
        val query = LinkedHashMap<String, String>()
        if (queryString.isNotEmpty()) {
            for (pair in queryString.split('&')) {
                if (pair.isEmpty()) continue
                val name = UriCodec.decode(pair.substringBefore('=')) ?: return null
                val value = UriCodec.decode(if ('=' in pair) pair.substringAfter('=') else "") ?: return null
                if (name !in query) query[name] = value
            }
        }
        return UriParts(host, path, query)
    }

    /** 整条链接的长度上限，防止外部塞超长串 */
    private const val MAX_URI_LENGTH = 4096
}
