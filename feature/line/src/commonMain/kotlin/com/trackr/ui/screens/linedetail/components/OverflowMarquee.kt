package com.trackr.ui.screens.linedetail.components

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 单行文字装不下时原地慢慢滚。
 *
 * 线路页里有几处「可能很长的单行文字」：候车站胶囊、顶上的「始发 → 终点」方向行。
 * 打省略号会把站名吃掉，所以统一改成滚动：先停一下让人看清开头，滚到底停一下再从头来。
 * 文字装得下时 `basicMarquee` 自己不滚，外观和普通单行文字一样。
 */
fun Modifier.overflowMarquee(): Modifier = basicMarquee(
    initialDelayMillis = 900,
    repeatDelayMillis = 1400,
    velocity = 36.dp,
    // 滚回开头时隔一小段，别让尾巴和开头挤在一起像两段文字
    spacing = MarqueeSpacing(24.dp)
)
