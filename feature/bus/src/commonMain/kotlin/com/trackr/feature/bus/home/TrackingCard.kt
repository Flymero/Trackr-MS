package com.trackr.feature.bus.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.component.DataSourceBadge
import com.trackr.core.designsystem.component.DataSourceBadgeStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_end
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.designsystem.theme.eta
import com.trackr.ui.components.arrival.arrivalToneColor
import org.jetbrains.compose.resources.stringResource

/**
 * 追踪卡（v3）：一项追踪一张 —— 同时追多条线时每张卡写自己那条线的到站，点卡片去这条线的线路页，
 * 点「结束」只停这一项（一起等里则是去掉这一条线）。
 */
@Composable
internal fun TrackingCard(
    card: TrackingCardUi,
    onOpen: () -> Unit,
    onStop: () -> Unit
) {
    val dimens = TrackrTheme.dimens

    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = dimens.screenPadding)
            .testTag(BusHomeTags.TRACKING_CARD)
    ) {
        Column(modifier = Modifier.padding(start = dimens.cardPadding, end = dimens.spaceXs, top = dimens.spaceM, bottom = dimens.spaceM)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // TCP 标签出现/消失淡入淡出，与线路详情页同款
                AnimatedVisibility(
                    visible = card.isPushOnline,
                    enter = fadeIn(tween(durationMillis = 180)),
                    exit = fadeOut(tween(durationMillis = 120))
                ) {
                    DataSourceBadge(
                        text = "TCP",
                        containerColor = DataSourceBadgeStyle.MEISHAN.contentColor,
                        contentColor = DataSourceBadgeStyle.MEISHAN.containerColor,
                        shape = RoundedCornerShape(3.dp)
                    )
                }
                TextButton(onClick = onStop, modifier = Modifier.testTag(BusHomeTags.STOP_TRACKING)) {
                    Text(stringResource(Res.string.action_end), color = MaterialTheme.colorScheme.error)
                }
            }
            Column(modifier = Modifier.padding(end = dimens.spaceM)) {
                Text(
                    text = card.arrival.big,
                    style = MaterialTheme.typography.eta,
                    color = arrivalToneColor(card.arrival.tone),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (card.detail.isNotBlank()) {
                    Text(text = card.detail, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
