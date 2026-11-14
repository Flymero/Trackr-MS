package com.trackr.core.navigation.deeplink

import com.trackr.core.model.DataSourceType
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.navigation.ScreenKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 重构计划 §3.6 的链接表：每种链接、和 [DeepLinkUris] 往返、掌上公交的中文 id、外部传进来的坏输入。 */
class DeepLinkParserTest {

    private fun homeStack(vararg keys: ScreenKey) = DeepLink.Stack(ScreenKey.Home, keys.toList())

    @Test
    fun line_fullLink_parsesToHomeStackWithLineDetail() {
        val link = DeepLinkParser.parse("trackr://line/021/line_49?source=CHELAILE&stop=stop_004&order=4&name=49%E8%B7%AF")
        val expected = ScreenKey.LineDetail(
            cityId = "021",
            lineId = "line_49",
            targetOrder = 4,
            stopId = "stop_004",
            source = DataSourceType.CHELAILE,
            lineName = "49路",
        )
        assertEquals(homeStack(expected), link)
    }

    @Test
    fun line_minimalLink_usesSameDefaultsAsOldExtras() {
        // 旧的 6 个 extra：站序缺省 0、站 id 空、数据源不认识就当车来了、线名空
        assertEquals(
            homeStack(ScreenKey.LineDetail(cityId = "021", lineId = "line_49")),
            DeepLinkParser.parse("trackr://line/021/line_49"),
        )
        assertEquals(
            homeStack(ScreenKey.LineDetail(cityId = "021", lineId = "line_49")),
            DeepLinkParser.parse("trackr://line/021/line_49?source=NOPE&order=abc"),
        )
    }

    @Test
    fun line_roundTripsThroughBuilder_includingMygolbsChineseIds() {
        val keys = listOf(
            ScreenKey.LineDetail("021", "021-15232-0", 7, "021-1234", DataSourceType.CHELAILE, "49路"),
            // 掌上公交：lineId = mg:城市编码:城市:线名:方向，stopId = mg:城市:站名
            ScreenKey.LineDetail("0755", "mg:0755:深圳:M492路:0", 3, "mg:深圳:科技园(南)", DataSourceType.MYGOLBS, "M492路"),
            // 线名里有空格、&、=、#、?、/、+ 也要原样回来
            ScreenKey.LineDetail("021", "a/b", 1, "s?x", DataSourceType.CHELAILE, "夜宵 1&2=3#?/+"),
        )
        keys.forEach { key ->
            val uri = DeepLinkUris.line(key.cityId, key.lineId, key.source, key.stopId, key.targetOrder, key.lineName)
            assertEquals(homeStack(key), DeepLinkParser.parse(uri), uri)
        }
    }

    @Test
    fun builder_onlyEmitsAsciiAndOmitsEmptyParams() {
        val uri = DeepLinkUris.line("0755", "mg:0755:深圳:M492路:0", DataSourceType.MYGOLBS)
        assertEquals("trackr://line/0755/mg%3A0755%3A%E6%B7%B1%E5%9C%B3%3AM492%E8%B7%AF%3A0?source=MYGOLBS", uri)
        assertEquals("trackr://transit-code?provider=alipay", DeepLinkUris.transitCode(TransitCodeProvider.ALIPAY))
        assertEquals("trackr://route/group_commute", DeepLinkUris.route("group_commute"))
    }

    @Test
    fun route_parsesToHomeStackWithRouteDetail() {
        assertEquals(homeStack(ScreenKey.RouteDetail("group_commute")), DeepLinkParser.parse("trackr://route/group_commute"))
        assertEquals(homeStack(ScreenKey.RouteDetail("g 1")), DeepLinkParser.parse(DeepLinkUris.route("g 1")))
        assertNull(DeepLinkParser.parse("trackr://route"))
        assertNull(DeepLinkParser.parse("trackr://route/a/b"))
    }

    @Test
    fun stop_parsesToHomeStackWithStopDetail() {
        assertEquals(
            homeStack(ScreenKey.StopDetail(cityId = "021", stopId = "stop_555", stopName = "陕西南路")),
            DeepLinkParser.parse(DeepLinkUris.stop("021", "stop_555", "陕西南路")),
        )
        assertEquals(
            homeStack(ScreenKey.StopDetail(cityId = "021", stopId = "stop_555")),
            DeepLinkParser.parse("trackr://stop/021/stop_555"),
        )
        assertNull(DeepLinkParser.parse("trackr://stop/021"))
    }

    @Test
    fun stop_meishanEmptyCityId_carriesIdentityInQuery() {
        // 眉山/仁寿官方源 cityId 为空：城市身份走 city/cityCode 查询参数，path 只剩 stopId
        val uri = DeepLinkUris.stop(
            cityId = "",
            stopId = "ms-st:10",
            stopName = "眉山东站",
            source = DataSourceType.MEISHAN,
            cityName = "眉山",
            mygolbsCityCode = "",
        )
        assertEquals(
            homeStack(
                ScreenKey.StopDetail(
                    cityId = "",
                    stopId = "ms-st:10",
                    stopName = "眉山东站",
                    source = DataSourceType.MEISHAN,
                    cityName = "眉山",
                )
            ),
            DeepLinkParser.parse(uri),
        )
        // 信任边界：单段 path 只认官方源 + 非空城市名
        assertNull(DeepLinkParser.parse("trackr://stop/ms-st%3A10"), "没有来源与城市名：不解析")
        assertNull(
            DeepLinkParser.parse(
                DeepLinkUris.stop("", "ms-st:10", "眉山东站", source = DataSourceType.CHELAILE, cityName = "眉山")
            ),
            "非官方源不接受单段 path",
        )
        assertNull(
            DeepLinkParser.parse(
                DeepLinkUris.stop("", "ms-st:10", "眉山东站", source = DataSourceType.MEISHAN, cityName = "")
            ),
            "官方源但城市名为空：不解析",
        )
    }

    @Test
    fun stop_thirdPartyWithSourceAndCity_roundTripsContext() {
        val uri = DeepLinkUris.stop(
            cityId = "021",
            stopId = "mg:0755:深圳:M492:0",
            stopName = "深大",
            source = DataSourceType.MYGOLBS,
            cityName = "深圳",
            mygolbsCityCode = "0755",
        )
        assertEquals(
            homeStack(
                ScreenKey.StopDetail(
                    cityId = "021",
                    stopId = "mg:0755:深圳:M492:0",
                    stopName = "深大",
                    source = DataSourceType.MYGOLBS,
                    cityName = "深圳",
                    mygolbsCityCode = "0755",
                )
            ),
            DeepLinkParser.parse(uri),
        )
    }

    @Test
    fun trackingBoard_parsesToChooseBoardedBus() {
        assertEquals(DeepLink.ChooseBoardedBus("s-1"), DeepLinkParser.parse(DeepLinkUris.chooseBoardedBus("s-1")))
        assertNull(DeepLinkParser.parse("trackr://tracking/board"), "没有会话 id")
        assertNull(DeepLinkParser.parse("trackr://tracking/other?session=s-1"))
    }

    @Test
    fun transitCode_onlyAlipayIsAlipay_everythingElseIsUnionPay() {
        // 和旧的 OPEN_TRANSIT_CODE action 一样
        assertEquals(DeepLink.TransitCode(TransitCodeProvider.ALIPAY), DeepLinkParser.parse("trackr://transit-code?provider=alipay"))
        assertEquals(DeepLink.TransitCode(TransitCodeProvider.UNIONPAY), DeepLinkParser.parse("trackr://transit-code?provider=unionpay"))
        assertEquals(DeepLink.TransitCode(TransitCodeProvider.UNIONPAY), DeepLinkParser.parse("trackr://transit-code"))
        assertEquals(DeepLink.TransitCode(TransitCodeProvider.UNIONPAY), DeepLinkParser.parse("trackr://transit-code?provider=ask_always"))
    }

    @Test
    fun schemeAndHost_areCaseInsensitive_fragmentIgnored_firstDuplicateParamWins() {
        assertEquals(
            homeStack(ScreenKey.LineDetail(cityId = "021", lineId = "L1", targetOrder = 2)),
            DeepLinkParser.parse("TRACKR://Line/021/L1?order=2&order=9#frag"),
        )
        assertEquals(
            homeStack(ScreenKey.LineDetail(cityId = "021", lineId = "L1")),
            DeepLinkParser.parse("trackr://line//021//L1/"),
            "多余的斜杠不算段",
        )
    }

    @Test
    fun foreignOrUnknownLinks_areIgnored() {
        listOf(
            null,
            "",
            "trackr:",
            "trackr://",
            "https://line/021/L1",
            "trackrx://line/021/L1",
            "intent://line/021/L1#Intent;scheme=trackr;end",
            "trackr://unknown/021/L1",
            "trackr://settings",
            "trackr://line/021",
            "trackr://line/021/L1/extra",
        ).forEach { uri -> assertNull(DeepLinkParser.parse(uri), "应忽略：$uri") }
    }

    @Test
    fun badIds_areRejected() {
        val tooLong = "a".repeat(DeepLinkParser.MAX_ID_LENGTH + 1)
        listOf(
            "trackr://line/021/%20%20",           // 空白
            "trackr://line/021/L1%0A",            // 换行（控制字符）
            "trackr://line/%00/L1",               // NUL
            "trackr://line/021/$tooLong",         // 太长
            "trackr://line/021/L%ZZ",             // 坏的百分号编码
            "trackr://line/021/L%E8",             // 不完整的 UTF-8
            "trackr://line/021/L%",               // 截断的 %
            "trackr://route/%7F",                 // DEL
            "trackr://tracking/board?session=%0D",
        ).forEach { uri -> assertNull(DeepLinkParser.parse(uri), "应拒绝：$uri") }
        assertEquals(
            homeStack(ScreenKey.LineDetail(cityId = "021", lineId = "a".repeat(DeepLinkParser.MAX_ID_LENGTH))),
            DeepLinkParser.parse("trackr://line/021/${"a".repeat(DeepLinkParser.MAX_ID_LENGTH)}"),
        )
    }

    @Test
    fun badOptionalParams_fallBackInsteadOfRejectingTheLink() {
        val long = "名".repeat(DeepLinkParser.MAX_NAME_LENGTH + 1)
        assertEquals(
            homeStack(ScreenKey.LineDetail(cityId = "021", lineId = "L1", lineName = "49路")),
            DeepLinkParser.parse("trackr://line/021/L1?stop=%0A&order=-3&name=49%E8%B7%AF%0A"),
            "站 id 带控制字符当没给；负站序当 0；线名去掉控制字符",
        )
        assertEquals(
            homeStack(ScreenKey.LineDetail(cityId = "021", lineId = "L1", lineName = long.take(DeepLinkParser.MAX_NAME_LENGTH))),
            DeepLinkParser.parse(DeepLinkUris.line("021", "L1", DataSourceType.CHELAILE, lineName = long)),
            "线名太长截断",
        )
        assertEquals(
            homeStack(ScreenKey.LineDetail(cityId = "021", lineId = "L1")),
            DeepLinkParser.parse("trackr://line/021/L1?order=99999999999"),
        )
    }

    @Test
    fun overlongUri_isIgnored() {
        assertNull(DeepLinkParser.parse("trackr://line/021/L1?name=" + "a".repeat(5000)))
    }
}
