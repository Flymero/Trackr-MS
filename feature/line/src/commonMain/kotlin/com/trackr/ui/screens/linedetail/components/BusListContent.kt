package com.trackr.ui.screens.linedetail.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_bus
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.theme.busColors
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import com.trackr.core.model.sanitizePlateOrFleetNo
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_arrived
import com.trackr.feature.line.resources.line_distance_to_station
import com.trackr.feature.line.resources.line_list_approaching
import com.trackr.feature.line.resources.line_list_arrive_at
import com.trackr.feature.line.resources.line_list_back
import com.trackr.feature.line.resources.line_list_count
import com.trackr.feature.line.resources.line_list_current_stop
import com.trackr.feature.line.resources.line_list_empty
import com.trackr.feature.line.resources.line_list_entering
import com.trackr.feature.line.resources.line_list_follow
import com.trackr.feature.line.resources.line_list_kind_at_stop
import com.trackr.feature.line.resources.line_list_kind_moving
import com.trackr.feature.line.resources.line_list_kind_passed
import com.trackr.feature.line.resources.line_list_passed
import com.trackr.feature.line.resources.line_list_passed_stop
import com.trackr.feature.line.resources.line_list_title
import com.trackr.feature.line.resources.line_no_prediction
import com.trackr.ui.components.common.BusArrivalBadge
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 状态 2：车辆列表内容 (`BUS_LIST`)
 */
@Composable
fun BusListContent(
    lineDetail: BusLineDetail,
    selectedStop: BusStop?,
    onBack: () -> Unit,
    onSelectBus: (LiveBus) -> Unit,
    modifier: Modifier = Modifier
) {
    val busColors = MaterialTheme.busColors
    val currentOrder = selectedStop?.order ?: 1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_back),
                    contentDescription = stringResource(LineRes.string.line_list_back),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(LineRes.string.line_list_title, selectedStop?.name ?: stringResource(LineRes.string.line_list_current_stop)),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(LineRes.string.line_list_count, lineDetail.buses.size),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (lineDetail.buses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(LineRes.string.line_list_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        } else {
            // 排序：未过本站（按距离升序）在前，已过本站沉底
            val sortedBuses = remember(lineDetail.buses, currentOrder) {
                lineDetail.buses.sortedWith(
                    compareBy<LiveBus> { it.order > currentOrder }
                        .thenBy { if (it.etaSeconds > 0) it.etaSeconds else Int.MAX_VALUE }
                        .thenBy { it.stopsTo(currentOrder) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sortedBuses.forEach { bus ->
                    val isPassed = bus.order > currentOrder
                    val isAtStop = bus.order == currentOrder && bus.isArrived
                    val stopsLeft = bus.stopsTo(currentOrder)

                    Surface(
                        onClick = { onSelectBus(bus) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (isPassed) busColors.busPassedGray.copy(alpha = 0.2f) else busColors.busChipContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = vectorResource(Res.drawable.ic_bus),
                                        contentDescription = null,
                                        tint = if (isPassed) busColors.busPassedGray else busColors.busChipContent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                val cleanPlate = sanitizePlateOrFleetNo(bus.plateOrFleetNo)
                                val displayPlate = cleanPlate.ifBlank {
                                    when {
                                        isPassed -> stringResource(LineRes.string.line_list_kind_passed)
                                        isAtStop -> stringResource(LineRes.string.line_list_kind_at_stop)
                                        else -> stringResource(LineRes.string.line_list_kind_moving)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = displayPlate,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isPassed) busColors.busPassedGray else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (bus.speedKmh > 0 && !isPassed) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${bus.speedKmh.toInt()} km/h",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when {
                                        isPassed -> stringResource(LineRes.string.line_list_passed_stop)
                                        isAtStop -> stringResource(LineRes.string.line_arrived)
                                        stopsLeft > 0 -> stringResource(LineRes.string.line_list_approaching, stopsLeft, bus.order)
                                        else -> stringResource(LineRes.string.line_list_entering)
                                    },
                                    fontSize = 12.sp,
                                    color = if (isPassed) busColors.busPassedGray else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                // 没有到站预测时，用上游给的「距本站距离」补位；两者都没有才写「无预测」
                                val distanceText = RouteProjectionMath.formatDistanceText(
                                    if (isPassed) null else bus.distanceToTargetMeters
                                )
                                val statusText = when {
                                    isPassed -> stringResource(LineRes.string.line_list_passed)
                                    isAtStop -> stringResource(LineRes.string.line_arrived)
                                    bus.etaSeconds > 0 -> RouteProjectionMath.formatEtaText(bus.etaSeconds)
                                    distanceText.isNotBlank() ->
                                        stringResource(LineRes.string.line_distance_to_station, distanceText)
                                    else -> stringResource(LineRes.string.line_no_prediction)
                                }
                                BusArrivalBadge(
                                    text = statusText,
                                    isHighlighted = isAtStop || (!isPassed && stopsLeft <= 1)
                                )
                                if (!isPassed && !bus.expectedArrivalTimeText.isNullOrEmpty()) {
                                    Text(
                                        text = stringResource(LineRes.string.line_list_arrive_at, bus.expectedArrivalTimeText.toString()),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = vectorResource(Res.drawable.ic_chevron_right),
                                contentDescription = stringResource(LineRes.string.line_list_follow),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
