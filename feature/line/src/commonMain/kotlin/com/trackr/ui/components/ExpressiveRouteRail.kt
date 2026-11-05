package com.trackr.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.rail.RouteRailArrowArm
import com.trackr.core.designsystem.rail.RouteRailArrowGap
import com.trackr.core.designsystem.rail.RouteRailArrowSpread
import com.trackr.core.designsystem.rail.RouteRailArrowStrokeWidth
import com.trackr.core.designsystem.rail.RouteRailStrokeWidth
import com.trackr.core.designsystem.rail.drawVerticalTrackSegment
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_bus
import com.trackr.core.designsystem.theme.BusSemanticColors
import com.trackr.core.designsystem.theme.busColors
import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import com.trackr.core.model.sanitizePlateOrFleetNo
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_arrived
import com.trackr.feature.line.resources.line_rail_bus
import com.trackr.feature.line.resources.line_rail_waiting
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

// 第 20 步：挪到 commonMain，只保留纵向时间轴（横向走向条与 RouteRailLayoutMode 已删除）

/**
 * 车牌简写转换函数：
 * 车牌号（如“桂K03853D”）在窄列宽走向条上提取核心号码（如“3853”），自编号（如“430”）保留；
 * 若无有效车牌/自编号则显示“在途”。
 */
fun formatBusChipShortText(plateOrFleetNo: String): String {
    val clean = sanitizePlateOrFleetNo(plateOrFleetNo)
    if (clean.isBlank()) return "在途"
    val hasChinese = clean.any { it in '\u4e00'..'\u9fa5' }
    return if (hasChinese) {
        val alphanum = clean.filter { it.isLetterOrDigit() }
        val suffix = alphanum.dropWhile { it.isLetter() }
        suffix.trimStart('0').ifEmpty { clean }
    } else {
        clean
    }
}

/**
 * 纵向时间轴实时公交走向条（普通 Column 版，站点不多或外层不需要懒加载时用）
 *
 * - 候车站呼吸高亮胶囊与「候车中」标签，条件停靠站空心描边节点
 * - 每站右侧展示停靠/接近本站的车辆小标签
 * - 外层是 LazyColumn 时请改用 [routeRailItems]
 */
@Composable
fun ExpressiveRouteRail(
    stops: List<BusStop>,
    buses: List<LiveBus>,
    selectedStop: BusStop?,
    selectedBusId: String? = null,
    onSelectStop: (BusStop) -> Unit,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    if (stops.isEmpty()) return

    // 循环前一次性对车辆按站序分组，实现 O(1) 查找并避免在 50+ 站点循环中重复 filter 与 remember 槽位开销
    val busesByOrder = remember(buses) {
        buses.groupBy { it.order }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        stops.indices.forEach { index ->
            RouteRailStopItem(
                stops = stops,
                index = index,
                busesByOrder = busesByOrder,
                selectedOrder = selectedStop?.order,
                selectedBusId = selectedBusId,
                onSelectStop = onSelectStop,
                onSelectBus = onSelectBus
            )
        }
    }
}

/**
 * 纵向时间轴走向条的 LazyList 版：一站一个 item（站行 + 到下一站的轨道段），
 * 让整页 LazyColumn 只组合可见站点。
 *
 * @param busesByOrder 车辆按 [LiveBus.order] 分组（调用方 remember 好再传）
 * @param selectedOrder 候车站站序，null 视为未选（轨道全绿）
 */
fun LazyListScope.routeRailItems(
    stops: List<BusStop>,
    busesByOrder: Map<Int, List<LiveBus>>,
    selectedOrder: Int?,
    selectedBusId: String?,
    onSelectStop: (BusStop) -> Unit,
    onSelectBus: (LiveBus) -> Unit
) {
    items(
        count = stops.size,
        // 按下标做 key：个别数据源的站表可能有重复站序，用站序做 key 会让 LazyColumn 崩溃
        key = { index -> "rail_$index" },
        contentType = { "rail_stop" }
    ) { index ->
        RouteRailStopItem(
            stops = stops,
            index = index,
            busesByOrder = busesByOrder,
            selectedOrder = selectedOrder,
            selectedBusId = selectedBusId,
            onSelectStop = onSelectStop,
            onSelectBus = onSelectBus,
            modifier = Modifier.padding(
                start = 16.dp,
                end = 16.dp,
                top = if (index == 0) 8.dp else 0.dp,
                bottom = if (index == stops.size - 1) 8.dp else 0.dp
            )
        )
    }
}

/**
 * 站序 [order] 在 [routeRailItems] 里的 0-based item 下标（不含调用方在前面放的其他 item）。
 * 找不到该站序时按 order - 1 夹到合法范围。
 */
fun routeRailItemIndex(stops: List<BusStop>, order: Int): Int {
    val index = stops.indexOfFirst { it.order == order }
    if (index >= 0) return index
    return (order - 1).coerceIn(0, (stops.size - 1).coerceAtLeast(0))
}

/**
 * 走向条单站 item：站行 + 到下一站的轨道连接段（末站没有）
 */
@Composable
private fun RouteRailStopItem(
    stops: List<BusStop>,
    index: Int,
    busesByOrder: Map<Int, List<LiveBus>>,
    selectedOrder: Int?,
    selectedBusId: String?,
    onSelectStop: (BusStop) -> Unit,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors
    val stop = stops[index]
    val currentSelectedOrder = selectedOrder ?: 1
    val isSelected = stop.order == selectedOrder
    val isPassed = stop.order < currentSelectedOrder
    val isLast = index == stops.size - 1

    // 计算该站点上方和下方轨道的连贯颜色，保证相邻站点接缝处颜色严格一致
    val topTrackColor = if (index > 0) {
        getRouteSegmentColor(stops[index - 1].order, stop.order, currentSelectedOrder, busColors)
    } else {
        busColors.routeTrackActive
    }
    val bottomTrackColor = if (!isLast) {
        getRouteSegmentColor(stop.order, stops[index + 1].order, currentSelectedOrder, busColors)
    } else {
        busColors.routeTrackActive
    }

    Column(modifier = modifier.fillMaxWidth()) {
        VerticalTimelineRow(
            stop = stop,
            isSelected = isSelected,
            isPassed = isPassed,
            isFirst = index == 0,
            isLast = isLast,
            topTrackColor = topTrackColor,
            bottomTrackColor = bottomTrackColor,
            // 首站没有上游连接段，仍在站行显示，避免车辆标签消失。
            stationBuses = busesByOrder[stop.order].orEmpty().filter {
                index == 0 || !it.hasAuthoritativeArrival || it.isArrived
            },
            selectedBusId = selectedBusId,
            onSelectStop = { onSelectStop(stop) },
            onSelectBus = onSelectBus
        )

        // 站点之间的轨道连接段（含精准居中的方向指示箭头），颜色与本站下方轨道一致
        if (!isLast) {
            VerticalTrackSegment(
                segmentColor = bottomTrackColor,
                hasArrow = (index % 3 == 0),
                buses = busesByOrder[stops[index + 1].order].orEmpty().filter {
                    it.hasAuthoritativeArrival && !it.isArrived
                },
                onSelectBus = onSelectBus
            )
        }
    }
}

/**
 * 候车站高亮呼吸脉冲光晕子组件（仅在 isSelected == true 时实例化与运行动画，隔离动画开销）
 */
@Composable
private fun SelectedStationPulseHalo() {
    val pulseTransition = rememberInfiniteTransition(label = "node_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = Modifier
            .size(22.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = CircleShape
            )
    )
}

/**
 * 走向条站点节点（支持呼吸光晕高亮胶囊与条件停靠站空心描边）
 */
@Composable
internal fun StationNode(
    isSelected: Boolean,
    isPassed: Boolean,
    isConditionStop: Boolean = false,
    onClick: () -> Unit = {}
) {
    val busColors = MaterialTheme.busColors

    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            // 外层呼吸光晕（仅在选中时才创建并执行动画，避免 50+ 站点全部无休止运行动画时钟）
            SelectedStationPulseHalo()
            // 内层高亮核心胶囊
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    )
                    .border(2.dp, Color.White, CircleShape)
            )
        } else if (isConditionStop) {
            // 条件启用站：醒目描边空心节点
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(2.dp, busColors.conditionStopBorder, CircleShape)
            )
        } else {
            // 普通站：实心节点
            val dotColor = if (isPassed) busColors.busPassedGray else busColors.routeTrackActive
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            )
        }
    }
}

/**
 * 计算两个连续站点之间的轨道颜色：
 * 保证整段轨道在相邻 Station 之间颜色统一，不会在中间出现截断或突变
 */
internal fun getRouteSegmentColor(
    fromOrder: Int,
    toOrder: Int,
    currentSelectedOrder: Int,
    busColors: BusSemanticColors
): Color {
    // 初始状态（选中第一站或默认全览），整条轨道均为连续的活跃翡翠绿
    if (currentSelectedOrder <= 1) {
        return busColors.routeTrackActive
    }
    // 候车站之前的区间（发车站到候车站之间的路段）渲染为已过站灰色，候车站出发及后续乘车区间为活跃绿
    return if (fromOrder < currentSelectedOrder) {
        busColors.busPassedGray
    } else {
        busColors.routeTrackActive
    }
}

/**
 * 绘制纵向时间轴单站行轨道连接线（消除浮点接缝，统一轨道宽度与连贯色）
 */
fun Modifier.drawVerticalTrackLine(
    isFirst: Boolean,
    isLast: Boolean,
    topTrackColor: Color,
    bottomTrackColor: Color,
    centerX: Dp = 14.dp,
    strokeWidth: Dp = 3.dp
): Modifier = this.drawBehind {
    val centerXPx = centerX.toPx()
    val centerY = size.height / 2f
    val overlap = 1.5f
    val strokeWidthPx = strokeWidth.toPx()

    if (!isFirst) {
        drawLine(
            color = topTrackColor,
            start = Offset(centerXPx, -overlap),
            end = Offset(centerXPx, centerY),
            strokeWidth = strokeWidthPx
        )
    }
    if (!isLast) {
        drawLine(
            color = bottomTrackColor,
            start = Offset(centerXPx, centerY),
            end = Offset(centerXPx, size.height + overlap),
            strokeWidth = strokeWidthPx
        )
    }
}

/**
 * 纵向时间轴单站行
 */
@Composable
private fun VerticalTimelineRow(
    stop: BusStop,
    isSelected: Boolean,
    isPassed: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    topTrackColor: Color,
    bottomTrackColor: Color,
    stationBuses: List<LiveBus>,
    selectedBusId: String?,
    onSelectStop: () -> Unit,
    onSelectBus: (LiveBus) -> Unit
) {
    val busColors = MaterialTheme.busColors
    val rowBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg, shape = MaterialTheme.shapes.small)
            .clickable { onSelectStop() }
            .padding(horizontal = 8.dp)
            .drawVerticalTrackLine(
                isFirst = isFirst,
                isLast = isLast,
                topTrackColor = topTrackColor,
                bottomTrackColor = bottomTrackColor,
                centerX = 40.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 站序号
        Text(
            text = stop.order.toString().padStart(2, '0'),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else busColors.busPassedGray,
            modifier = Modifier.width(26.dp)
        )

        // 2. 纵向轴线节点（单次测量，高度由整行撑开，轨道由 drawBehind 绘制）
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.Center
        ) {
            StationNode(
                isSelected = isSelected,
                isPassed = isPassed,
                isConditionStop = !stop.statusNote.isNullOrEmpty(),
                onClick = onSelectStop
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 3. 站名与备注
        Column(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 44.dp)
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stop.name,
                    fontSize = if (isSelected) 15.sp else 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        isPassed -> busColors.busPassedGray
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = stringResource(LineRes.string.line_rail_waiting),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // 状态附注（如“响应式停靠”）或地铁换乘
            if (!stop.statusNote.isNullOrEmpty() || stop.metroTransfers.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!stop.statusNote.isNullOrEmpty()) {
                        Text(
                            text = stop.statusNote.orEmpty(),
                            fontSize = 11.sp,
                            color = busColors.conditionStopBorder
                        )
                    }
                    stop.metroTransfers.forEach { transfer ->
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = transfer,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // 4. 到站车辆小标签
        stationBuses.forEach { bus ->
            RouteRailBusChip(bus = bus, onSelectBus = onSelectBus)
        }
    }
}

@Composable
private fun RouteRailBusChip(bus: LiveBus, onSelectBus: (LiveBus) -> Unit) {
    val busColors = MaterialTheme.busColors
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (bus.isArrived) busColors.busArrivingAmber else busColors.busChipContainer,
        onClick = { onSelectBus(bus) },
        modifier = Modifier.padding(start = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_bus),
                contentDescription = stringResource(LineRes.string.line_rail_bus),
                tint = if (bus.isArrived) Color.White else busColors.busChipContent,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = if (bus.isArrived) stringResource(LineRes.string.line_arrived) else formatBusChipShortText(bus.plateOrFleetNo),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (bus.isArrived) Color.White else busColors.busChipContent
            )
        }
    }
}

/**
 * 纵向时间轴站点之间的轨道连接段（含精准居中于两站正中间的行车方向箭头）
 */
@Composable
private fun VerticalTrackSegment(
    segmentColor: Color,
    hasArrow: Boolean,
    buses: List<LiveBus>,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (buses.isEmpty()) 16.dp else 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 与站序号 26.dp 严格对齐
        Spacer(modifier = Modifier.width(26.dp))

        // 纵向轨道段 (宽度 28.dp，严格对齐上下站点的轨道中心，复用 Path 缓冲区)
        Spacer(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
                .drawWithCache {
                    val arrowPath = Path()
                    val arrowStroke = Stroke(
                        width = RouteRailArrowStrokeWidth.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                    val strokeWidthPx = RouteRailStrokeWidth.toPx()
                    val arrowGap = RouteRailArrowGap.toPx()
                    val arrowArm = RouteRailArrowArm.toPx()
                    val arrowSpread = RouteRailArrowSpread.toPx()
                    val overlap = 1.5f

                    onDrawBehind {
                        val centerX = size.width / 2f
                        drawVerticalTrackSegment(
                            x = centerX,
                            startY = -overlap,
                            endY = size.height + overlap,
                            color = segmentColor,
                            strokeWidth = strokeWidthPx,
                            hasArrow = hasArrow,
                            arrowPath = arrowPath,
                            arrowStroke = arrowStroke,
                            arrowGap = arrowGap,
                            arrowArm = arrowArm,
                            arrowSpread = arrowSpread
                        )
                    }
                }
        )
        Spacer(modifier = Modifier.width(8.dp))
        buses.forEach { bus ->
            RouteRailBusChip(bus = bus, onSelectBus = onSelectBus)
        }
    }
}
