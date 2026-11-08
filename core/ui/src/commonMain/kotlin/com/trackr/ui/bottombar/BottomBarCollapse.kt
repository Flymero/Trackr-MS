package com.trackr.ui.bottombar

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** 应用底栏的实际内容高度，不包含系统安全区；没有底栏（详情页、长辈版）时为 0。 */
val LocalBottomBarHeight = staticCompositionLocalOf { 0.dp }

/**
 * 底栏（页签）收起时，页面底部贴着它的悬浮元素要跟着往下挪的距离（px）。
 *
 * 由应用根布局提供：0 = 底栏完全显示；正数 = 底栏已经滑下去多少，页面里贴底的按钮跟着挪多少。
 * 页面在布局阶段读这个值（`Modifier.offset { }`），底栏动画时不用重组也能逐帧跟上。
 */
val LocalBottomBarCollapseOffset = staticCompositionLocalOf<() -> Float> { { 0f } }
