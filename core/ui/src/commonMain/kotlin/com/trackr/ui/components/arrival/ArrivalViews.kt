package com.trackr.ui.components.arrival

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.designsystem.theme.item
import com.trackr.core.domain.arrival.ArrivalDisplay
import com.trackr.core.domain.arrival.ArrivalTone

/**
 * 一条到站文字的扁平 UI 模型（重构计划 §4.1：只有 String / 枚举，编译器自动判稳定）。
 *
 * @param big 右侧大字（「4 分钟」「到站了」「下一班 07:20」）
 * @param small 大字下面的小字（「2 站」「从 白石洲 发车」）；空串不显示
 */
@Immutable
data class ArrivalUi(
    val big: String,
    val small: String,
    val tone: ArrivalTone
) {
    companion object {
        /** 由 [ArrivalDisplay] 转来 */
        fun of(display: ArrivalDisplay): ArrivalUi =
            ArrivalUi(big = display.headline, small = display.detail, tone = display.tone)
    }
}

/** 到站文字颜色：绿 = 快到 / 已到；正文色 = 在路上；灰 = 车都过了 / 没发车 / 加载；橙 = 末班已过 / 停运（v3） */
@Composable
fun arrivalToneColor(tone: ArrivalTone): Color = when (tone) {
    ArrivalTone.Go -> TrackrTheme.colors.go
    ArrivalTone.Normal -> MaterialTheme.colorScheme.onSurface
    ArrivalTone.Mute -> MaterialTheme.colorScheme.onSurfaceVariant
    ArrivalTone.Warn -> TrackrTheme.colors.warn
}

/** 行尾的到站文字：上面大字（按 [ArrivalTone] 上色），下面小字，右对齐 */
@Composable
fun ArrivalTrailing(arrival: ArrivalUi, modifier: Modifier = Modifier) {
    Column(modifier = modifier.widthIn(max = 160.dp), horizontalAlignment = Alignment.End) {
        Text(
            text = arrival.big,
            style = MaterialTheme.typography.item,
            color = arrivalToneColor(arrival.tone),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End
        )
        if (arrival.small.isNotBlank()) {
            Text(
                text = arrival.small,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End
            )
        }
    }
}

/** 线路号小方块（v3：圆角 8dp，品牌浅蓝底）；名字为空时不画空壳，免得只剩一个空白圆角块（issue #20） */
@Composable
fun LineBadge(name: String, modifier: Modifier = Modifier) {
    if (name.isBlank()) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
