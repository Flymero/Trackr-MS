package com.trackr.ui.screens.linedetail.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_arrow_drop_down
import com.trackr.core.designsystem.theme.busColors
import com.trackr.core.designsystem.theme.extendedColors
import com.trackr.core.domain.arrival.ArrivalDisplay
import com.trackr.core.domain.arrival.ArrivalState
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import com.trackr.core.model.sanitizePlateOrFleetNo
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_distance_to_station
import com.trackr.feature.line.resources.line_station_buses_on_line
import com.trackr.feature.line.resources.line_station_change_stop
import com.trackr.feature.line.resources.line_station_collapse_card
import com.trackr.feature.line.resources.line_station_expand_card
import com.trackr.feature.line.resources.line_station_no_buses
import com.trackr.feature.line.resources.line_station_order
import com.trackr.feature.line.resources.line_station_pick_stop
import com.trackr.feature.line.resources.line_station_tracking
import com.trackr.ui.components.common.arrivalToneColor
import com.trackr.ui.screens.linedetail.LineLive
import com.trackr.ui.screens.linedetail.LineStatic
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 主卡：站点到站（v3「线路详情」hero）。
 *
 * 「XX站上车 ▾」→ 大字（[ArrivalText] 8 种状态）→ 小字 + 车牌 / 预计到达 / 车速 → 分隔线 →「下一辆 …」+「全线 N 辆车在途 · 查看」。
 * 全部车过站时只给下一班始发发车时间，不估到站；实时没给下一班就用时刻表补的 [LineLive.fallbackNextDeparture]。
 * 实时还没回来（[live] 为 null，站表来自缓存）时显示「正在查车…」。
 *
 * @param onPickStop 点「XX站上车」：滚到走向条上这一站，在那儿换站
 * @param now 判「已过末班」用的本地时刻；默认取这次数据的刷新时刻
 * @param collapsed 收起主卡：只留一行「候车站 + 到站 + 展开」
 * @param onToggleCollapsed 收起 / 展开；null = 不显示按钮（长辈版或测试）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StationArrivalContent(
    static: LineStatic,
    live: LineLive?,
    selectedStop: BusStop?,
    isTrackingActive: Boolean,
    onPickStop: () -> Unit,
    onOpenBusList: () -> Unit,
    modifier: Modifier = Modifier,
    now: LocalTime? = null,
    collapsed: Boolean = false,
    onToggleCollapsed: (() -> Unit)? = null
) {
    val currentOrder = selectedStop?.order ?: 1
    val buses = live?.buses.orEmpty()
    val idleBuses = live?.idleBuses.orEmpty()

    // 最近一辆还没过本站的车
    val upcomingBus = remember(buses, currentOrder) { ArrivalText.nearestBus(currentOrder, buses) }

    val nowTime = now ?: remember(live?.updatedAtMillis) { live?.let { ArrivalText.localTimeOf(it.updatedAtMillis) } }
    val arrivalState = remember(static, live, currentOrder, nowTime) {
        stationArrivalState(static, live, currentOrder, nowTime)
    }
    val arrival = remember(arrivalState, static.line.name, selectedStop?.name) {
        ArrivalText.format(arrivalState, lineName = static.line.name, stopName = selectedStop?.name.orEmpty())
    }

    // 「下一辆」：只在「在路上」时单独列出（到站了的小字里已经带了下一辆）
    val followingText = remember(buses, currentOrder, upcomingBus, arrivalState) {
        if (arrivalState !is ArrivalState.OnTheWay || upcomingBus == null) {
            null
        } else {
            ArrivalText.nearestBus(currentOrder, buses - upcomingBus)?.let { following -> followingBusText(currentOrder, following) }
        }
    }

    // 收起：只留一行「候车站 + 到站」，把高度让给走向条
    if (collapsed) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StopPickChip(stop = selectedStop, onClick = onPickStop)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = arrival.short,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = arrivalToneColor(arrival.tone),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
            if (isTrackingActive) {
                Spacer(modifier = Modifier.width(6.dp))
                TrackingBadge()
            }
            if (onToggleCollapsed != null) {
                Spacer(modifier = Modifier.width(4.dp))
                CollapseButton(expanded = false, onClick = onToggleCollapsed)
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StopPickChip(stop = selectedStop, onClick = onPickStop)
            if (!selectedStop?.statusNote.isNullOrEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.busColors.conditionStopBorder.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = selectedStop.statusNote.orEmpty(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.busColors.conditionStopBorder,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (isTrackingActive) {
                TrackingBadge()
            } else if (selectedStop != null) {
                Text(
                    text = stringResource(LineRes.string.line_station_order, currentOrder),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onToggleCollapsed != null) {
                CollapseButton(expanded = true, onClick = onToggleCollapsed)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        ArrivalHeadline(arrival = arrival, color = arrivalToneColor(arrival.tone))

        // 小字：「还有 2 站」·车牌·预计到达·车速（各自一段，信息多时换行）
        val busFacts = upcomingBus?.let { busFacts(it) }.orEmpty()
        if (arrival.detail.isNotBlank() || busFacts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (arrival.detail.isNotBlank()) {
                    Text(
                        text = arrival.detail,
                        fontSize = 13.sp,
                        color = if (arrival.detailIsWarning) {
                            MaterialTheme.extendedColors.warning
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                busFacts.forEachIndexed { index, fact ->
                    if (index > 0 || arrival.detail.isNotBlank()) {
                        Text(text = "·", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = fact,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = followingText.orEmpty(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpenBusList) {
                Text(
                    text = if (buses.isNotEmpty()) {
                        stringResource(LineRes.string.line_station_buses_on_line, buses.size)
                    } else if (idleBuses.isNotEmpty()) {
                        "暂无在途车辆 · ${idleBuses.size} 辆停场/非运营"
                    } else {
                        stringResource(LineRes.string.line_station_no_buses)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** 「XX站上车 ▾」 */
@Composable
private fun StopPickChip(stop: BusStop?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stop?.let { boardingLabel(it.name) } ?: stringResource(LineRes.string.line_station_pick_stop),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
                contentDescription = stringResource(LineRes.string.line_station_change_stop),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 「● 追踪中」 */
@Composable
private fun TrackingBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(LineRes.string.line_station_tracking),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 收起 / 展开主卡的小三角：展开时朝上（收起），收起时朝下（展开） */
@Composable
private fun CollapseButton(expanded: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = vectorResource(Res.drawable.ic_arrow_drop_down),
            contentDescription = stringResource(
                if (expanded) LineRes.string.line_station_collapse_card else LineRes.string.line_station_expand_card
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .rotate(if (expanded) 180f else 0f)
        )
    }
}

/** 到站大字（[ArrivalText] 的输出） */
@Composable
private fun ArrivalHeadline(arrival: ArrivalDisplay, color: Color) {
    // 只包住大字，轮询时卡片其余部分不跟着做动画（§4.4）
    AnimatedContent(
        targetState = arrival.value to arrival.unit,
        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
        label = "arrival_headline"
    ) { (value, unit) ->
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = if (unit != null) 40.sp else 28.sp,
                fontWeight = FontWeight.Black,
                color = color
            )
            if (unit != null) {
                Text(
                    text = unit,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = color,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
            }
        }
    }
}

/**
 * 候车站此刻的到站状态（标准版主卡和长辈版线路页共用）：实时没回来是「正在查车」；
 * 全部车过站时下一班用实时给的，没给就用时刻表补的 [LineLive.fallbackNextDeparture]。
 */
internal fun stationArrivalState(static: LineStatic, live: LineLive?, targetOrder: Int, now: LocalTime?): ArrivalState {
    if (live == null) return ArrivalState.Loading
    val line = static.line
    return ArrivalText.stateFor(
        targetOrder = targetOrder,
        buses = live.buses,
        operatingState = live.operatingState,
        startStop = line.startStop,
        firstTime = line.firstTime,
        lastTime = line.lastTime,
        nextDeparture = live.nextDepartureFromStart?.takeIf { it.isNotBlank() } ?: live.fallbackNextDeparture,
        statusDescription = live.statusDescription,
        now = now
    )
}

/** 站名已经以「站」结尾就不再加 */
internal fun boardingLabel(stopName: String): String =
    if (stopName.endsWith("站")) "$stopName 上车" else "${stopName}站上车"

/** 最近那辆车的车牌、距本站距离、预计到达、车速（拿不到的不显示） */
@Composable
private fun busFacts(bus: LiveBus): List<String> {
    val distanceText = RouteProjectionMath.formatDistanceText(bus.distanceToTargetMeters)
    return listOfNotNull(
        sanitizePlateOrFleetNo(bus.plateOrFleetNo).takeIf { it.isNotBlank() },
        distanceText.takeIf { it.isNotBlank() }?.let {
            stringResource(LineRes.string.line_distance_to_station, it)
        },
        bus.expectedArrivalTimeText?.takeIf { it.isNotBlank() }?.let { "$it 到" },
        bus.speedKmh.takeIf { it > 0 }?.let { "${it.toInt()} km/h" }
    )
}

/** 「下一辆 约 12 分钟 · 5 站」 */
internal fun followingBusText(targetOrder: Int, bus: LiveBus): String {
    val minutes = ArrivalText.minutesOf(bus.etaSeconds)
    val stops = bus.stopsTo(targetOrder).takeIf { bus.order > 0 && it >= 0 }
    return when {
        minutes != null && stops != null -> "下一辆 约 $minutes 分钟 · $stops 站"
        minutes != null -> "下一辆 约 $minutes 分钟"
        stops != null -> "下一辆还有 $stops 站"
        else -> "后面还有车"
    }
}
