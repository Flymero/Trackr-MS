package com.trackr.ui.screens.routeplan

import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.GroupTrackingSessionState
import com.trackr.core.model.LegPhase
import com.trackr.ui.screens.favoritesgroup.components.etaMinutesOf
import com.trackr.ui.screens.favoritesgroup.components.reliableRideStops
import com.trackr.ui.screens.favoritesgroup.components.trackingPhaseLabel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

// 规划页的纯文案 / 选法函数（不依赖 Compose，commonTest 直接测）。

/** 方案标签的名字（v3：固定 最快 / 少换乘 / 少走路） */
const val TAG_FASTEST = "最快"
const val TAG_FEWEST_TRANSFERS = "少换乘"
const val TAG_LEAST_WALK = "少走路"

/** 拿不到上面三个名字的方案（凑满 3 个时用） */
const val TAG_OTHER = "备选"

/** 只有一个方案时 */
const val TAG_ONLY = "唯一方案"

/** 方案标签最多几个（v3） */
const val MAX_SCHEME_TABS = 3

/** 一个方案标签：名字 + 对应的方案 */
data class SchemeTabUi(
    val label: String,
    val scheme: AmapTransitSchemeResult
)

/**
 * 从候选方案里选出最多 3 个标签（v3「方案页签固定 3 个，名字写清楚」）：
 * 1. 最快 = 时长最短；少换乘 = 换乘最少（平手取更快）；少走路 = 步行最短（平手取更快）；再平手取靠前的。
 * 2. 一个方案拿到几个名字就并成一个标签（「最快 · 少换乘」），名字不说假话。
 * 3. 不够 3 个时按原顺序补剩下的方案，写「备选」。
 * 4. 只有 1 个方案时写「唯一方案」。
 */
fun pickSchemeTabs(candidates: List<AmapTransitSchemeResult>): List<SchemeTabUi> {
    if (candidates.isEmpty()) return emptyList()
    if (candidates.size == 1) return listOf(SchemeTabUi(TAG_ONLY, candidates[0]))

    val indices = candidates.indices
    fun bestBy(selector: (AmapTransitSchemeResult) -> Int): Int =
        indices.minWith(
            compareBy<Int> { selector(candidates[it]) }
                .thenBy { candidates[it].durationMinutes }
                .thenBy { it }
        )

    val winners = listOf(
        TAG_FASTEST to indices.minWith(compareBy<Int> { candidates[it].durationMinutes }.thenBy { it }),
        TAG_FEWEST_TRANSFERS to bestBy { it.transferCount },
        TAG_LEAST_WALK to bestBy { it.walkingMeters }
    )

    // 按名字顺序排，同一方案的名字并在一起
    val labelsByIndex = linkedMapOf<Int, MutableList<String>>()
    winners.forEach { (label, index) -> labelsByIndex.getOrPut(index) { mutableListOf() } += label }

    val tabs = labelsByIndex.map { (index, labels) ->
        SchemeTabUi(labels.joinToString(" · "), candidates[index])
    }.toMutableList()
    indices.filter { it !in labelsByIndex }.forEach { index ->
        if (tabs.size < MAX_SCHEME_TABS) tabs += SchemeTabUi(TAG_OTHER, candidates[index])
    }
    return tabs
}

/** 标签上的换乘文字："直达" / "换 1 次" */
internal fun schemeTransferText(scheme: AmapTransitSchemeResult): String =
    if (scheme.transferCount == 0) "直达" else "换 ${scheme.transferCount} 次"

/** "步行 820 米 · 换乘 1 次 · ¥4.0" */
internal fun schemeSummaryText(scheme: AmapTransitSchemeResult): String {
    val transfer = if (scheme.transferCount == 0) "不用换乘" else "换乘 ${scheme.transferCount} 次"
    val cost = scheme.costYuan.trim().takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }?.let { "¥$it" }
    return listOfNotNull("步行 ${scheme.walkingMeters} 米", transfer, cost).joinToString(" · ")
}

/** 毫秒时间戳 → "HH:mm" */
internal fun formatHourMinute(millis: Long, timeZone: TimeZone): String {
    val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

/** "07:42 出发 · 08:14 到"（按当前时间估算） */
internal fun departArriveText(
    nowMillis: Long,
    durationMinutes: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault()
): String {
    val arrive = nowMillis + durationMinutes.coerceAtLeast(0) * 60_000L
    return "${formatHourMinute(nowMillis, timeZone)} 出发 · ${formatHourMinute(arrive, timeZone)} 到"
}

/** 分段条的一段 */
internal data class PlanSegment(
    val isWalk: Boolean,
    val legIndex: Int,
    val weight: Float,
    val label: String
)

/**
 * 分段条：步行 / 每程按时长比例分配。
 * 方案里没有分段时长，所以步行按 80 米 / 分钟估算，乘车按站数 × 2 分钟；
 * 站数不可信的程平分剩余时长。乘车段文字只写线路名，步行段不写字。
 */
internal fun planSegments(scheme: AmapTransitSchemeResult): List<PlanSegment> {
    val legs = scheme.group.legs
    if (legs.isEmpty()) return emptyList()
    fun walkMinutes(meters: Int): Float = if (meters <= 0) 0f else (meters / 80f).coerceAtLeast(1f)

    val walkBefore = legs.map { it.walkingDistanceMeters.coerceAtLeast(0) }
    val finalWalk = (scheme.walkingMeters - walkBefore.sum()).coerceAtLeast(0)
    val totalWalk = walkBefore.sumOf { walkMinutes(it).toDouble() }.toFloat() + walkMinutes(finalWalk)
    val knownRide = legs.map { leg -> reliableRideStops(leg.primaryLine)?.let { it * 2f } }
    val unknownCount = knownRide.count { it == null }
    val rideBudget = (scheme.durationMinutes - totalWalk - knownRide.sumOf { (it ?: 0f).toDouble() }.toFloat())
        .coerceAtLeast(0f)
    val fallbackRide = if (unknownCount > 0) (rideBudget / unknownCount).coerceAtLeast(1f) else 0f

    return buildList {
        legs.forEachIndexed { index, leg ->
            val walk = walkMinutes(walkBefore[index])
            if (walk > 0f) add(PlanSegment(isWalk = true, legIndex = index, weight = walk, label = ""))
            add(
                PlanSegment(
                    isWalk = false,
                    legIndex = index,
                    weight = (knownRide[index] ?: fallbackRide).coerceAtLeast(1f),
                    label = leg.primaryLine?.lineName.orEmpty()
                )
            )
        }
        val last = walkMinutes(finalWalk)
        if (last > 0f) add(PlanSegment(isWalk = true, legIndex = legs.lastIndex, weight = last, label = ""))
    }
}

/** 追踪状态条上当前程的阶段；没有分程数据时当作等车 */
internal fun trackingPhaseOf(session: GroupTrackingSessionState): LegPhase =
    session.trackingSessionState
        ?.takeIf { it.legPhase != LegPhase.SINGLE && it.currentLegIndex == session.activeLegIndex }
        ?.legPhase ?: LegPhase.WAITING_BOARD

/**
 * 规划页追踪状态条文字：
 * 等车 "第 1/2 程 · 等车 · 4 分钟"；乘车 "第 1/2 程 · 乘车 · 2 站后下车"。
 */
internal fun trackingStatusText(session: GroupTrackingSessionState): String {
    val legSession = session.trackingSessionState?.takeIf {
        it.legPhase != LegPhase.SINGLE && it.currentLegIndex == session.activeLegIndex
    }
    val phase = legSession?.legPhase ?: LegPhase.WAITING_BOARD
    val label = trackingPhaseLabel(session.activeLegIndex, session.totalLegs, phase)
    val detail = when {
        legSession == null || legSession.stopsRemaining < 0 -> null
        phase == LegPhase.ON_BOARD -> "${legSession.stopsRemaining} 站后下车"
        else -> etaMinutesOf(legSession.etaSeconds, legSession.stopsRemaining)?.let { "约 $it 分钟" }
    }
    return if (detail != null) "$label · $detail" else label
}
