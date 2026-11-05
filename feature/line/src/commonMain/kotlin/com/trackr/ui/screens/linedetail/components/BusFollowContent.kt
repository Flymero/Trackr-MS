package com.trackr.ui.screens.linedetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_bus
import com.trackr.core.designsystem.theme.busColors
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.LiveBus
import com.trackr.core.model.sanitizePlateOrFleetNo
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_distance_to_wait_station
import com.trackr.feature.line.resources.line_follow_approaching
import com.trackr.feature.line.resources.line_follow_at_stop
import com.trackr.feature.line.resources.line_follow_eta
import com.trackr.feature.line.resources.line_follow_gone
import com.trackr.feature.line.resources.line_follow_heading
import com.trackr.feature.line.resources.line_follow_next
import com.trackr.feature.line.resources.line_follow_passed
import com.trackr.feature.line.resources.line_follow_speed
import com.trackr.feature.line.resources.line_follow_stops
import com.trackr.feature.line.resources.line_follow_stops_flow
import com.trackr.feature.line.resources.line_follow_title
import com.trackr.feature.line.resources.line_follow_to_terminus
import com.trackr.feature.line.resources.line_follow_unknown_plate
import com.trackr.feature.line.resources.line_no_prediction
import com.trackr.ui.components.StationNode
import com.trackr.ui.components.common.BusArrivalBadge
import com.trackr.ui.components.drawVerticalTrackLine
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 状态 3：单车跟随行程轴内容 (`BUS_FOLLOW`)
 */
@Composable
fun BusFollowContent(
    lineDetail: BusLineDetail,
    followedBus: LiveBus?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // 顶栏：返回按钮与车牌号
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_back),
                    contentDescription = stringResource(Res.string.action_back),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                val followedPlate = followedBus?.let { sanitizePlateOrFleetNo(it.plateOrFleetNo) }?.ifBlank { null }
                Text(
                    text = stringResource(LineRes.string.line_follow_title, followedPlate ?: stringResource(LineRes.string.line_follow_unknown_plate)),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(LineRes.string.line_follow_heading, lineDetail.line.endStop),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (followedBus == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(LineRes.string.line_follow_gone),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        } else {
            val terminusOrder = lineDetail.stops.lastOrNull()?.order ?: 0
            val stopsLeftToTerminus = followedBus.stopsTo(terminusOrder).coerceAtLeast(0)

            // 统计概要卡
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(LineRes.string.line_follow_to_terminus), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(LineRes.string.line_follow_stops, stopsLeftToTerminus), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(LineRes.string.line_follow_speed), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${followedBus.speedKmh.toInt()} km/h", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(stringResource(LineRes.string.line_follow_eta), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val etaText = if (followedBus.etaSeconds > 0) RouteProjectionMath.formatEtaText(followedBus.etaSeconds) else stringResource(LineRes.string.line_no_prediction)
                        BusArrivalBadge(
                            text = etaText,
                            isHighlighted = followedBus.etaSeconds > 0
                        )
                    }
                }
            }

            val followDistance = RouteProjectionMath.formatDistanceText(followedBus.distanceToTargetMeters)
            if (followDistance.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(LineRes.string.line_distance_to_wait_station, followDistance),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(LineRes.string.line_follow_stops_flow),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            val scrollState = rememberScrollState()

            // 当选中的车辆位置发生变化时，平滑滚动使当前车辆所在站点居中可见
            LaunchedEffect(followedBus.order) {
                val currentIndex = (followedBus.order - 1).coerceAtLeast(0)
                val scrollOffset = ((currentIndex - 2).coerceAtLeast(0) * 110)
                scrollState.animateScrollTo(scrollOffset)
            }

            // 纵向行程节点流（展示已过站、当前接近站、未到站）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .verticalScroll(scrollState)
            ) {
                lineDetail.stops.forEachIndexed { index, stop ->
                    val isPassed = stop.order < followedBus.order
                    val isCurrent = stop.order == followedBus.order
                    val isFirst = index == 0
                    val isLast = index == lineDetail.stops.size - 1
                    val isNextStop = followedBus.stopsTo(stop.order) == 1

                    // 计算相邻站点轨道的严格连贯颜色，保证接缝处颜色严格一致无突变
                    val topTrackColor = if (stop.order <= followedBus.order) {
                        busColors.busPassedGray.copy(alpha = 0.35f)
                    } else {
                        busColors.routeTrackActive
                    }
                    val bottomTrackColor = if (stop.order < followedBus.order) {
                        busColors.busPassedGray.copy(alpha = 0.35f)
                    } else {
                        busColors.routeTrackActive
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .drawVerticalTrackLine(
                                isFirst = isFirst,
                                isLast = isLast,
                                topTrackColor = topTrackColor,
                                bottomTrackColor = bottomTrackColor,
                                centerX = 14.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. 左侧时间轴节点（单次测量，高度由整行撑开，轨道由 drawVerticalTrackLine 统一绘制）
                        Box(
                            modifier = Modifier.width(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(busColors.busArrivingAmber, CircleShape)
                                        .border(2.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = vectorResource(Res.drawable.ic_bus),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            } else {
                                StationNode(
                                    isSelected = false,
                                    isPassed = isPassed,
                                    isConditionStop = !stop.statusNote.isNullOrEmpty()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // 2. 站点右侧信息内容区
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 36.dp)
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 站点序号
                            Text(
                                text = stop.order.toString().padStart(2, '0'),
                                fontSize = 11.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    isCurrent -> busColors.busArrivingAmber
                                    isPassed -> busColors.busPassedGray.copy(alpha = 0.6f)
                                    else -> busColors.routeTrackActive
                                },
                                modifier = Modifier.width(22.dp)
                            )

                            // 站点名称
                            Text(
                                text = stop.name,
                                fontSize = if (isCurrent) 14.sp else 13.sp,
                                fontWeight = if (isCurrent) FontWeight.Bold else if (!isPassed) FontWeight.Medium else FontWeight.Normal,
                                color = when {
                                    isCurrent -> busColors.busArrivingAmber
                                    isPassed -> busColors.busPassedGray
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )

                            // 右侧状态指示与徽标（复用 BusArrivalBadge 统一组件）
                            when {
                                isCurrent -> {
                                    BusArrivalBadge(
                                        text = if (followedBus.isArrived) stringResource(LineRes.string.line_follow_at_stop) else stringResource(LineRes.string.line_follow_approaching),
                                        isHighlighted = true
                                    )
                                }
                                isNextStop -> {
                                    BusArrivalBadge(
                                        text = stringResource(LineRes.string.line_follow_next),
                                        isHighlighted = false
                                    )
                                }
                                isPassed -> {
                                    Text(
                                        text = stringResource(LineRes.string.line_follow_passed),
                                        fontSize = 11.sp,
                                        color = busColors.busPassedGray.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
