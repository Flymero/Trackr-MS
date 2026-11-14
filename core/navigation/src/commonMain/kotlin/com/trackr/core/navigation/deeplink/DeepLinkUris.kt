package com.trackr.core.navigation.deeplink

import com.trackr.core.model.DataSourceType
import com.trackr.core.model.TransitCodeProvider

/**
 * 拼 `trackr://` 链接（重构计划 §3.6 表）。小组件、快捷方式、通知、提醒都用这里拼，和 [DeepLinkParser] 对称。
 *
 * 路径段和查询参数按 UTF-8 百分号编码：掌上公交的 lineId / stopId 带中文和冒号（`mg:0755:深圳:M492路:0`）。
 */
object DeepLinkUris {
    const val SCHEME = "trackr"

    internal const val HOST_LINE = "line"
    internal const val HOST_ROUTE = "route"
    internal const val HOST_STOP = "stop"
    internal const val HOST_TRACKING = "tracking"
    internal const val PATH_BOARD = "board"
    internal const val HOST_TRANSIT_CODE = "transit-code"

    internal const val Q_SOURCE = "source"
    internal const val Q_STOP = "stop"
    internal const val Q_ORDER = "order"
    internal const val Q_NAME = "name"
    internal const val Q_SESSION = "session"
    internal const val Q_PROVIDER = "provider"
    internal const val Q_CITY = "city"
    internal const val Q_CITY_CODE = "cityCode"

    /** `trackr://line/{cityId}/{lineId}?source=&stop=&order=&name=`：线路详情。空的可选参数不写。 */
    fun line(
        cityId: String,
        lineId: String,
        source: DataSourceType,
        stopId: String = "",
        targetOrder: Int = 0,
        lineName: String = "",
    ): String = build(
        host = HOST_LINE,
        path = listOf(cityId, lineId),
        query = listOf(
            Q_SOURCE to source.name,
            Q_STOP to stopId,
            Q_ORDER to targetOrder.takeIf { it > 0 }?.toString().orEmpty(),
            Q_NAME to lineName,
        ),
    )

    /** `trackr://route/{groupId}`：路线详情。 */
    fun route(groupId: String): String = build(HOST_ROUTE, listOf(groupId))

    /**
     * `trackr://stop/{cityId}/{stopId}?name=&source=&city=&cityCode=`：站点详情。
     * 眉山/仁寿官方源的 cityId 为空：空段会被解析端过滤，城市身份改由 city/cityCode 查询参数承载，
     * 此时 path 只有一段（stopId），解析端按 MEISHAN + 非空 city 校验信任边界。
     */
    fun stop(
        cityId: String,
        stopId: String,
        stopName: String = "",
        source: DataSourceType? = null,
        cityName: String = "",
        mygolbsCityCode: String = "",
    ): String = build(
        HOST_STOP,
        path = listOf(cityId, stopId).filter { it.isNotEmpty() },
        query = buildList {
            add(Q_NAME to stopName)
            if (source != null) add(Q_SOURCE to source.name)
            if (cityName.isNotBlank()) add(Q_CITY to cityName)
            if (mygolbsCityCode.isNotBlank()) add(Q_CITY_CODE to mygolbsCityCode)
        },
    )

    /** `trackr://tracking/board?session=`：「上了哪辆？」弹层。 */
    fun chooseBoardedBus(sessionId: String): String =
        build(HOST_TRACKING, listOf(PATH_BOARD), listOf(Q_SESSION to sessionId))

    /** `trackr://transit-code?provider=`：直接拉起乘车码（`alipay` / `unionpay`）。 */
    fun transitCode(provider: TransitCodeProvider): String =
        build(HOST_TRANSIT_CODE, emptyList(), listOf(Q_PROVIDER to provider.id))

    private fun build(
        host: String,
        path: List<String>,
        query: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        append(SCHEME).append("://").append(host)
        path.forEach { append('/').append(UriCodec.encode(it)) }
        val params = query.filter { it.second.isNotEmpty() }
        params.forEachIndexed { index, (name, value) ->
            append(if (index == 0) '?' else '&')
            append(name).append('=').append(UriCodec.encode(value))
        }
    }
}

/** RFC 3986 百分号编码：只留 unreserved 字符（`A-Z a-z 0-9 - . _ ~`），其余按 UTF-8 字节编码。 */
internal object UriCodec {
    private const val HEX = "0123456789ABCDEF"

    fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (b < 0x80 && (c.isLetterOrDigit() || c == '-' || c == '.' || c == '_' || c == '~')) {
                append(c)
            } else {
                append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
    }

    /** 解码；`%` 后不是两位十六进制、或字节不是合法 UTF-8 时返回 null。`+` 不当空格（不是表单编码）。 */
    fun decode(value: String): String? {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            if (value[i] == '%') {
                if (i + 2 >= value.length) return null
                val hi = hexValue(value[i + 1])
                val lo = hexValue(value[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes.add(((hi shl 4) or lo).toByte())
                i += 3
            } else {
                // 一段不含 % 的原文整体转字节（不拆代理对）
                val end = value.indexOf('%', i).let { if (it < 0) value.length else it }
                value.substring(i, end).encodeToByteArray().forEach { bytes.add(it) }
                i = end
            }
        }
        return try {
            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
