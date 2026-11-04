package com.trackr.ui.screens.routeplan

import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.GroupTrackingSessionState
import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 规划页纯函数：方案标签选法、文案、分段条、追踪状态条（原 RoutePlanScreenTest 里的纯函数用例搬来） */
class SchemeTextsTest {

    private fun scheme(
        id: String,
        minutes: Int,
        transfers: Int,
        walk: Int,
        lineNames: List<String> = listOf("1路")
    ): AmapTransitSchemeResult {
        val legs = lineNames.mapIndexed { index, name ->
            TransitLeg(
                legIndex = index,
                fromStopName = "站$index",
                toStopName = "站${index + 1}",
                candidateLines = listOf(CandidateLine(cityId = "0755", lineId = "amap_$name", lineName = name)),
                walkingDistanceMeters = if (index == 0) 0 else 80
            )
        }
        return AmapTransitSchemeResult(
            title = id,
            durationMinutes = minutes,
            walkingMeters = walk,
            costYuan = "4.0",
            transferCount = transfers,
            group = TransitGroup(id = id, name = id, legs = legs)
        )
    }

    @Test
    fun pickSchemeTabs_threeDistinctWinners_getFixedNames() {
        val tabs = pickSchemeTabs(
            listOf(
                scheme("a", 32, 1, 820),
                scheme("b", 38, 0, 640),
                scheme("c", 35, 1, 260),
                scheme("d", 36, 1, 1100)
            )
        )
        assertEquals(listOf("最快", "少换乘", "少走路"), tabs.map { it.label })
        assertEquals(listOf("a", "b", "c"), tabs.map { it.scheme.group.id })
    }

    @Test
    fun pickSchemeTabs_sameSchemeWinsTwice_mergesNamesAndFillsWithOther() {
        // a 最快也换乘最少；c 走路最少；剩下的 b 补成「备选」
        val tabs = pickSchemeTabs(
            listOf(
                scheme("a", 30, 0, 800),
                scheme("b", 40, 1, 600),
                scheme("c", 45, 1, 200),
                scheme("d", 50, 2, 900)
            )
        )
        assertEquals(listOf("最快 · 少换乘", "少走路", "备选"), tabs.map { it.label })
        assertEquals(listOf("a", "c", "b"), tabs.map { it.scheme.group.id })
    }

    @Test
    fun pickSchemeTabs_tiesPreferFasterThenEarlier() {
        // 换乘平手取更快的 b；步行平手且时长平手取靠前的 a
        val tabs = pickSchemeTabs(
            listOf(
                scheme("a", 30, 1, 300),
                scheme("b", 28, 1, 300)
            )
        )
        assertEquals(listOf("最快 · 少换乘 · 少走路", "备选"), tabs.map { it.label })
        assertEquals(listOf("b", "a"), tabs.map { it.scheme.group.id })
    }

    @Test
    fun pickSchemeTabs_oneOrNone() {
        assertEquals(emptyList(), pickSchemeTabs(emptyList()))
        assertEquals(listOf("唯一方案"), pickSchemeTabs(listOf(scheme("a", 30, 1, 300))).map { it.label })
    }

    @Test
    fun schemeTexts_summaryTimesAndSegments() {
        assertEquals("步行 820 米 · 换乘 1 次 · ¥4.0", schemeSummaryText(scheme("s0", 32, 1, 820)))
        assertEquals("步行 640 米 · 不用换乘 · ¥4.0", schemeSummaryText(scheme("s1", 38, 0, 640)))
        assertEquals("直达", schemeTransferText(scheme("s1", 38, 0, 640)))
        assertEquals("换 1 次", schemeTransferText(scheme("s0", 32, 1, 820)))

        val zone = TimeZone.of("Asia/Shanghai")
        val now = LocalDateTime(2026, 10, 31, 7, 42).toInstant(zone).toEpochMilliseconds()
        assertEquals("07:42 出发 · 08:14 到", departArriveText(now, 32, zone))

        // 步行 240 米：第 2 程前 80 米 + 末段 160 米；站序不可信，两程平分剩余时长
        val segments = planSegments(scheme("x", 30, 1, 240, listOf("B612", "M492")))
        assertEquals(listOf(false, true, false, true), segments.map { it.isWalk })
        assertEquals(listOf("B612", "", "M492", ""), segments.map { it.label })
        assertEquals(1f, segments[1].weight, 0.01f)
        assertEquals(2f, segments[3].weight, 0.01f)
        assertEquals(13.5f, segments[0].weight, 0.01f)
    }

    private fun trackingSession(phase: LegPhase, stops: Int, eta: Int) = GroupTrackingSessionState(
        groupId = "s0",
        activeLegIndex = 0,
        totalLegs = 2,
        trackingSessionState = TrackingSessionState(
            isActive = true,
            legPhase = phase,
            stopsRemaining = stops,
            etaSeconds = eta,
            currentLegIndex = 0,
            totalLegs = 2
        )
    )

    @Test
    fun trackingStatusText_byPhase() {
        assertEquals("第 1/2 程 · 等车 · 约 4 分钟", trackingStatusText(trackingSession(LegPhase.WAITING_BOARD, 2, 240)))
        assertEquals("第 1/2 程 · 乘车 · 2 站后下车", trackingStatusText(trackingSession(LegPhase.ON_BOARD, 2, 300)))
        assertEquals(LegPhase.ON_BOARD, trackingPhaseOf(trackingSession(LegPhase.ON_BOARD, 2, 300)))
    }
}
