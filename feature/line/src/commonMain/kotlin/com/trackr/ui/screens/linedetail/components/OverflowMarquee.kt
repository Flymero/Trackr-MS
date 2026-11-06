package com.trackr.ui.screens.linedetail.components

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 单行文字超出可用宽度时缓慢横向滚动，避免长站名被省略号截断。 */
fun Modifier.overflowMarquee(): Modifier = basicMarquee(
    initialDelayMillis = 900,
    repeatDelayMillis = 1400,
    velocity = 36.dp,
    spacing = MarqueeSpacing(24.dp)
)
