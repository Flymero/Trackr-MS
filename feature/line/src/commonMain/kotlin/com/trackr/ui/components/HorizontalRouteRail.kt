package com.trackr.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.rail.drawHorizontalTrackSegment
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_bus
import com.trackr.core.designsystem.theme.busChipSpringSpec
import com.trackr.core.designsystem.theme.busColors
import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_rail_arrived
import com.trackr.feature.line.resources.line_rail_condition
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 横排走向条（横排模式）：一条从左到右的轨道，站名竖排在线下方，车标按 [LiveBus.segmentProgress] 在站间走。
 *
 * 没有卡片外壳，和竖排走向条一样直接铺在页面背景上；高度按全线最长站名算，见 [horizontalRailHeight]。
 * 点整列 = 选这个站，点车标 = 跟随这辆车。
 */
val HorizontalRailColumnWidth = 56.dp
private val RailColumnWidth = HorizontalRailColumnWidth
private val RailSidePadding = 16.dp
private val RailBusLaneHeight = 36.dp
private val RailTrackHeight = 24.dp
private val RailChipWidth = 46.dp
private val RailChipHeight = 18.dp
private val RailNameLineHeight = 14.sp
/** 轨道上下各留一点，不贴着主卡和页面底部 */
private val RailVerticalPadding = 16.dp
/** 站名层固定多出来的部分：末行与「响应」之间的 3dp + 条件站「响应」一行 18dp */
private val RailNameExtraHeight = 21.dp
/** 没有站（还在加载）时按几行估高度 */
private const val RailDefaultNameLines = 5
private const val RailColumnWidthValue = 56f
private const val RailChipWidthValue = 46f

/** 测试用的 tag */
internal object HorizontalRailTags {
    const val ROOT = "horizontal_rail"
}

/**
 * 站名竖排几行：全线最长站名的字数，不截断。
 *
 * 站名一个字一行，所以行数就是最长的那个站名。以前封在 8 行，超过 8 个字的站名被省略号吃掉；
 * 现在高度跟着它走，多长的站名都给完整显示。
 */
internal fun railNameLines(stops: List<BusStop>): Int =
    stops.maxOfOrNull { it.name.length }?.coerceAtLeast(1) ?: RailDefaultNameLines

/** 竖排里要换成横版括号的字符：圆括号一组、方括号一组，其余照常竖着写 */
private enum class RailBracketShape { Round, Square }

/** 这个字是哪一种括号；null = 不是括号 */
private fun railBracketShape(ch: Char): RailBracketShape? = when (ch) {
    '（', '）', '(', ')', '｛', '｝', '{', '}' -> RailBracketShape.Round
    '【', '】', '［', '］', '[', ']', '〔', '〕' -> RailBracketShape.Square
    else -> null
}

/** 左半边括号横过来是开口朝下（︵、︻），右半边开口朝上（︶、︼） */
private fun railBracketOpensDown(ch: Char): Boolean = when (ch) {
    '（', '(', '｛', '{', '【', '［', '[', '〔' -> true
    else -> false
}

/** 竖排时这个字要不要换成横版括号 */
internal fun railCharIsVerticalBracket(ch: Char): Boolean = railBracketShape(ch) != null

/**
 * 横排走向条的高度：车标层 + 轨道层 + 竖排站名 + 条件站「响应」和上下留白。
 *
 * @param charHeight 一行站名的实际高度（14.sp 按当前字体缩放换算成 dp）
 */
fun horizontalRailHeight(stops: List<BusStop>, charHeight: Dp): Dp =
    RailBusLaneHeight + RailTrackHeight + charHeight * railNameLines(stops) + RailNameExtraHeight + RailVerticalPadding

/** 按当前字体缩放算高度，页面外层留白和组件本身都用这一个 */
@Composable
fun horizontalRailHeight(stops: List<BusStop>): Dp {
    val charHeight = with(LocalDensity.current) { RailNameLineHeight.toDp() }
    return horizontalRailHeight(stops, charHeight)
}

/** Compatibility alias for callers from the pre-v0.1.6 rail layout. */
@Composable
fun horizontalRailCardHeight(stops: List<BusStop>): Dp = horizontalRailHeight(stops)

/**
 * 车在横条上的浮点站下标；null = 这辆车不在本站表里，不画。
 *
 * 用列表下标算，不用 order - 1：个别数据源的站序不连续、或有重复，按下标才不会画到线外面。
 * 没坐标（[LiveBus.hasValidProgress] 为 false）或已到站时贴目标站，不猜中间的进度。
 */
internal fun busRailPosition(stops: List<BusStop>, bus: LiveBus): Float? {
    val index = stops.indexOfFirst { it.order == bus.order }
    if (index < 0) return null
    if (bus.isArrived || !bus.hasValidProgress) return index.toFloat()
    if (index == 0) return 0f
    return (index - 1) + bus.segmentProgress.coerceIn(0f, 1f)
}

@Composable
fun HorizontalRouteRail(
    stops: List<BusStop>,
    buses: List<LiveBus>,
    selectedStop: BusStop?,
    selectedBusId: String?,
    scrollState: ScrollState,
    onSelectStop: (BusStop) -> Unit,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    if (stops.isEmpty()) return

    val railHeight = horizontalRailHeight(stops)
    val stripHeight = railHeight - RailVerticalPadding
    val nameLines = railNameLines(stops)
    val contentWidth = RailSidePadding * 2 + RailColumnWidth * stops.size
    val selectedOrder = selectedStop?.order ?: 1
    val busColors = MaterialTheme.busColors
    val placements = remember(stops, buses) { placeBuses(stops, buses) }

    // 没有卡片：轨道和站名直接铺在页面背景上，和竖排走向条一致；左右贴满整宽，组件内留 16dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(railHeight)
            .testTag(HorizontalRailTags.ROOT),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stripHeight)
                .horizontalScroll(scrollState)
        ) {
            Box(Modifier.width(contentWidth).height(stripHeight)) {
                Spacer(
                    Modifier
                        .matchParentSize()
                        .drawWithCache {
                            val arrowPath = Path()
                            val arrowStroke = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                            val trackStroke = 3.dp.toPx()
                            val arrowGap = 4.dp.toPx()
                            val arrowArm = 3.dp.toPx()
                            val arrowSpread = 3.5.dp.toPx()
                            onDrawBehind {
                                val y = (RailBusLaneHeight + RailTrackHeight / 2).toPx()
                                for (i in 0 until stops.size - 1) {
                                    val startX = (RailSidePadding + RailColumnWidth * i + RailColumnWidth / 2).toPx()
                                    val endX = (RailSidePadding + RailColumnWidth * (i + 1) + RailColumnWidth / 2).toPx()
                                    drawHorizontalTrackSegment(
                                        startX = startX,
                                        endX = endX,
                                        y = y,
                                        color = getRouteSegmentColor(
                                            fromOrder = stops[i].order,
                                            toOrder = stops[i + 1].order,
                                            currentSelectedOrder = selectedOrder,
                                            busColors = busColors
                                        ),
                                        strokeWidth = trackStroke,
                                        hasArrow = i % 3 == 0,
                                        arrowPath = arrowPath,
                                        arrowStroke = arrowStroke,
                                        arrowGap = arrowGap,
                                        arrowArm = arrowArm,
                                        arrowSpread = arrowSpread
                                    )
                                }
                            }
                        }
                )

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = RailSidePadding)
                ) {
                    stops.forEach { stop ->
                        RailStopColumn(
                            stop = stop,
                            nameLines = nameLines,
                            isSelected = stop.order == selectedStop?.order,
                            isPassed = stop.order < selectedOrder,
                            onSelect = { onSelectStop(stop) },
                            modifier = Modifier
                                .width(RailColumnWidth)
                                .height(stripHeight)
                        )
                    }
                }

                placements.forEach { placement ->
                    key(placement.bus.busId) {
                        RailBusChip(
                            bus = placement.bus,
                            position = placement.position,
                            tier = placement.tier,
                            isSelected = placement.bus.busId == selectedBusId,
                            onClick = { onSelectBus(placement.bus) }
                        )
                    }
                }
            }
        }
    }
}

/** 每列：车标层留白 + 轨道节点 + 竖排站名 + 条件站的「响应」 */
@Composable
private fun RailStopColumn(
    stop: BusStop,
    nameLines: Int,
    isSelected: Boolean,
    isPassed: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors
    val nameColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isPassed -> busColors.busPassedGray
        else -> MaterialTheme.colorScheme.onSurface
    }
    val isConditionStop = !stop.statusNote.isNullOrEmpty()

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) { contentDescription = stop.name }
            .clickable { onSelect() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(RailBusLaneHeight))
        Box(Modifier.height(RailTrackHeight), contentAlignment = Alignment.Center) {
            StationNode(
                isSelected = isSelected,
                isPassed = isPassed,
                isConditionStop = isConditionStop,
                onClick = onSelect
            )
        }
        RailStopName(
            name = stop.name,
            maxLines = nameLines,
            color = nameColor,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
        if (isConditionStop) {
            Text(
                text = stringResource(LineRes.string.line_rail_condition),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = busColors.conditionStopBorder,
                maxLines = 1
            )
        }
    }
}

/**
 * 站名竖排：整段一个 Text（一个字的行高 14sp 比 CJK 字形的反体还紧，拆成一个字一个 Text 会被逐个截底），
 * 括号的位置换成全角空格占位，再在同一块区域上盖一层画出来的横版括号。
 */
@Composable
private fun RailStopName(
    name: String,
    maxLines: Int,
    color: Color,
    fontWeight: FontWeight
) {
    val text = railVerticalNameText(name)
    if (name.none(::railCharIsVerticalBracket)) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = RailNameLineHeight,
            fontWeight = fontWeight,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }

    val lineHeight = with(LocalDensity.current) { RailNameLineHeight.toDp() }
    val bold = fontWeight.weight >= FontWeight.SemiBold.weight
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = RailNameLineHeight,
            fontWeight = fontWeight,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )
        // 括号盖在占位空行上。位置按每行的 baseline 算：字形贴着 baseline 坐，行框的几何中心比字心低
        Canvas(modifier = Modifier.matchParentSize()) {
            val textLayout = layout ?: return@Canvas
            val rowHeightPx = lineHeight.toPx()
            val glyphCenterOffset = 12.sp.toPx() * RailGlyphCenterAboveBaseline
            val cx = size.width / 2f
            for (index in 0 until minOf(name.length, textLayout.lineCount, maxLines)) {
                val ch = name[index]
                val shape = railBracketShape(ch) ?: continue
                drawRailBracket(
                    shape = shape,
                    opensDown = railBracketOpensDown(ch),
                    centerX = cx,
                    centerY = textLayout.getLineBaseline(index) - glyphCenterOffset,
                    rowHeight = rowHeightPx,
                    color = color,
                    bold = bold
                )
            }
        }
    }
}

/**
 * CJK 字形当方块看，字心在 baseline 往上约 0.42 个字号（字形墨迹大约 baseline-0.9em ~ baseline+0.1em）。
 * 括号要对齐字心，不能对齐行框中心——行框中心比字心低，看起来就往上蹿了半截。
 */
private const val RailGlyphCenterAboveBaseline = 0.42f

/** 竖排站名的文本：括号换成全角空格，位置留给画出来的横版括号 */
internal fun railVerticalNameText(name: String): String =
    name.map { if (railCharIsVerticalBracket(it)) RailBracketPlaceholder else it.toString() }
        .joinToString("\n")

/** 占一个全角字的空位，等画出来的括号盖上 */
private const val RailBracketPlaceholder = '　'

/**
 * 竖排站名里的横版括号：按行高自己画，不用字体里的「（」转 90°。
 *
 * 字体里的括号转过来对不齐：字形墨迹在自己的行框里本来就偏下（baseline 贴在行底），
 * 墨迹又宽，转完就跑到旁边去、还挤到上下两个字（#23 反馈）。自己画就固定：
 * 宽度、起伏按行高算，水平、垂直都居中，换什么字体都不会跑偏。
 *
 * @param centerX 列心（px）
 * @param centerY 这一行字心（px，按 baseline 推出来的）
 * @param rowHeight 一行的高度（px），括号的宽窄粗细都按它算
 */
private fun DrawScope.drawRailBracket(
    shape: RailBracketShape,
    opensDown: Boolean,
    centerX: Float,
    centerY: Float,
    rowHeight: Float,
    color: Color,
    bold: Boolean
) {
    val halfWidth = rowHeight * 0.34f
    val stroke = rowHeight * if (bold) 0.105f else 0.08f
    val path = Path()
    when (shape) {
        // 圆括号：一条对称的弧，两端往下（或往上）翘
        RailBracketShape.Round -> {
            val bow = rowHeight * 0.1f
            val endY = if (opensDown) centerY + bow else centerY - bow
            val controlY = if (opensDown) centerY - bow * 1.7f else centerY + bow * 1.7f
            path.moveTo(centerX - halfWidth, endY)
            path.cubicTo(
                centerX - halfWidth * 0.55f, controlY,
                centerX + halfWidth * 0.55f, controlY,
                centerX + halfWidth, endY
            )
        }
        // 方括号：一条横梁加两边的短竖，转角磨圆
        RailBracketShape.Square -> {
            val arm = rowHeight * 0.13f
            val barY = if (opensDown) centerY - arm else centerY + arm
            val endY = if (opensDown) centerY + arm else centerY - arm
            path.moveTo(centerX - halfWidth, endY)
            path.lineTo(centerX - halfWidth, barY)
            path.lineTo(centerX + halfWidth, barY)
            path.lineTo(centerX + halfWidth, endY)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** 车标：位置用 spring 平滑过去；两辆挤在 46dp 内时上下错开一层 */
@Composable
private fun RailBusChip(
    bus: LiveBus,
    position: Float,
    tier: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val density = LocalDensity.current
    val busColors = MaterialTheme.busColors
    val animatedPosition by animateFloatAsState(
        targetValue = position,
        animationSpec = busChipSpringSpec(),
        label = "bus_${bus.busId}"
    )
    val arrived = bus.isArrived
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        arrived -> busColors.busArrivingAmber
        else -> busColors.busChipContainer
    }
    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        arrived -> Color.White
        else -> busColors.busChipContent
    }
    val shortPlate = remember(bus.plateOrFleetNo) { formatBusChipShortText(bus.plateOrFleetNo) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = containerColor,
        contentColor = contentColor,
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .offset {
                val centerPx = with(density) {
                    (RailSidePadding + RailColumnWidth * animatedPosition + RailColumnWidth / 2).toPx()
                }
                val halfChipPx = with(density) { RailChipWidth.toPx() } / 2f
                IntOffset(
                    x = (centerPx - halfChipPx).roundToInt(),
                    y = with(density) { (RailChipHeight * tier).toPx() }.roundToInt()
                )
            }
            .width(RailChipWidth)
            .height(RailChipHeight)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_bus),
                contentDescription = null,
                modifier = Modifier.size(11.dp)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = if (arrived) stringResource(LineRes.string.line_rail_arrived) else shortPlate,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class RailBusPlacement(
    val bus: LiveBus,
    val position: Float,
    val tier: Int
)

/** 按位置排车，挤在 [RailChipWidthValue] 以内的车上下错开一层 */
private fun placeBuses(stops: List<BusStop>, buses: List<LiveBus>): List<RailBusPlacement> {
    val positioned = buses.mapNotNull { bus ->
        val position = busRailPosition(stops, bus) ?: return@mapNotNull null
        bus to position
    }.sortedBy { it.second }

    var lastX = Float.NEGATIVE_INFINITY
    var tier = 0
    return positioned.map { (bus, position) ->
        val x = position * RailColumnWidthValue
        tier = if (x - lastX < RailChipWidthValue) (1 - tier) else 0
        lastX = x
        RailBusPlacement(bus = bus, position = position, tier = tier)
    }
}
