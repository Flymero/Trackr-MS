package com.trackr.deeplink

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.trackr.MainActivity
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.core.navigation.deeplink.DeepLinkUris
import com.trackr.widget.BusStopWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Intent ↔ `trackr://` 链接（重构计划 §3.6）：新链接、旧版 6 个 extra 和旧乘车码 action 的兼容、一次性消费、
 * 外部传来的坏 Intent，以及动态快捷方式发出的 Intent。
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkIntentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val line49 = ScreenKey.LineDetail(
        cityId = "034",
        lineId = "line_49",
        targetOrder = 4,
        stopId = "stop_004",
        source = DataSourceType.MYGOLBS,
        lineName = "49路"
    )
    private val line49Stack = DeepLink.Stack(ScreenKey.Home, listOf(line49))

    private fun legacyLineIntent() = Intent(context, MainActivity::class.java).apply {
        putExtra("extra_city_id", "034")
        putExtra("extra_line_id", "line_49")
        putExtra("extra_target_order", 4)
        putExtra("extra_stop_id", "stop_004")
        putExtra("extra_source", "MYGOLBS")
        putExtra("extra_line_name", "49路")
    }

    @Test
    fun trackrLink_inData_isParsed() {
        val intent = DeepLinkIntents.activityIntent(
            context,
            DeepLinkUris.line("034", "line_49", DataSourceType.MYGOLBS, "stop_004", 4, "49路")
        )
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(line49Stack, DeepLinkIntents.parse(intent))
    }

    @Test
    fun legacyLineExtras_stillOpenTheSameLine() {
        assertEquals(line49Stack, DeepLinkIntents.parse(legacyLineIntent()))
    }

    @Test
    fun legacyLineExtras_sameDefaultsAsOldCode() {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("extra_city_id", "034")
            putExtra("extra_line_id", "mg:0755:深圳:M492路:0")
            putExtra("extra_source", "NOT_A_SOURCE")
        }
        assertEquals(
            DeepLink.Stack(ScreenKey.Home, listOf(ScreenKey.LineDetail(cityId = "034", lineId = "mg:0755:深圳:M492路:0"))),
            DeepLinkIntents.parse(intent)
        )
        // 缺城市或线路：和旧代码一样不跳
        assertNull(DeepLinkIntents.parse(Intent().putExtra("extra_city_id", "034")))
        assertNull(DeepLinkIntents.parse(Intent().putExtra("extra_city_id", "034").putExtra("extra_line_id", " ")))
    }

    @Test
    fun legacyTransitCodeActions_bothSpellings_onlyAlipayIsAlipay() {
        listOf(
            "com.github.u5151561.trackr.ms.action.OPEN_TRANSIT_CODE",
            "com.trackr.action.OPEN_TRANSIT_CODE"
        ).forEach { action ->
            assertEquals(
                DeepLink.TransitCode(TransitCodeProvider.ALIPAY),
                DeepLinkIntents.parse(Intent(action).putExtra("extra_provider", "alipay"))
            )
            assertEquals(
                DeepLink.TransitCode(TransitCodeProvider.UNIONPAY),
                DeepLinkIntents.parse(Intent(action).putExtra("extra_provider", "unionpay"))
            )
            assertEquals(DeepLink.TransitCode(TransitCodeProvider.UNIONPAY), DeepLinkIntents.parse(Intent(action)))
        }
    }

    @Test
    fun consume_clearsLinkSoTheSameIntentDoesNotReplay() {
        val fresh = DeepLinkIntents.activityIntent(context, DeepLinkUris.route("g1"))
        DeepLinkIntents.consume(fresh)
        assertNull(fresh.data)
        assertNull(DeepLinkIntents.parse(fresh))

        val legacy = legacyLineIntent()
        DeepLinkIntents.consume(legacy)
        assertNull(DeepLinkIntents.parse(legacy))
        assertNull(legacy.getStringExtra("extra_city_id"))

        val legacyTransit = Intent("com.github.u5151561.trackr.ms.action.OPEN_TRANSIT_CODE").putExtra("extra_provider", "alipay")
        DeepLinkIntents.consume(legacyTransit)
        assertNull(legacyTransit.action)
        assertNull(DeepLinkIntents.parse(legacyTransit))
    }

    @Test
    fun consume_leavesNonTrackrDataAlone() {
        val launcher = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/x"))
        DeepLinkIntents.consume(launcher)
        assertEquals("https://example.com/x", launcher.dataString)
    }

    @Test
    fun foreignOrBadIntents_areIgnored() {
        listOf(
            null,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/line/034/L1")),
            Intent(Intent.ACTION_VIEW, Uri.parse("trackr://unknown/x")),
            Intent(Intent.ACTION_VIEW, Uri.parse("trackr://line/034/L1%0A")),
            // 别的 App 塞了类型不对的 extra：读不到当没有
            Intent().putExtra("extra_city_id", 34).putExtra("extra_line_id", 49),
        ).forEach { intent -> assertNull(DeepLinkIntents.parse(intent)) }
    }

    @Test
    fun dynamicShortcuts_sendTrackrLineLinks() {
        val favorite = FavoriteStationLine(
            id = "fav_1",
            cityId = "034",
            cityName = "上海",
            lineId = "line_49",
            lineName = "49路",
            startStop = "起点",
            endStop = "终点",
            targetStopId = "stop_004",
            targetStopName = "人民广场",
            targetOrder = 4,
            source = DataSourceType.MYGOLBS
        )

        BusStopWidgetProvider.updateDynamicShortcuts(context, listOf(favorite))

        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).single()
        val intent = shortcut.intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNull("不再带旧 extra", intent.getStringExtra("extra_city_id"))
        assertEquals(line49Stack, DeepLinkIntents.parse(intent))
    }
}
