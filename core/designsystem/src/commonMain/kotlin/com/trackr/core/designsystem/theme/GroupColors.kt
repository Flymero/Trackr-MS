package com.trackr.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.group_color_blue
import com.trackr.core.designsystem.resources.group_color_green
import com.trackr.core.designsystem.resources.group_color_none
import com.trackr.core.designsystem.resources.group_color_orange
import com.trackr.core.designsystem.resources.group_color_pink
import com.trackr.core.designsystem.resources.group_color_purple
import com.trackr.core.designsystem.resources.group_color_red
import com.trackr.core.designsystem.resources.group_color_teal
import com.trackr.core.designsystem.resources.group_color_yellow
import com.trackr.core.model.GroupColor
import org.jetbrains.compose.resources.StringResource

/**
 * 收藏分组颜色的色值（issue #38）。只用来做小面积的标记（卡片左边的色条、chip 前的圆点），
 * 不当文字或底色用；深色模式提亮一档，在深色卡片上也看得清。
 */
fun groupAccentColor(color: GroupColor, dark: Boolean): Color = when (color) {
    GroupColor.RED -> if (dark) Color(0xFFFF6B6E) else Color(0xFFE5484D)
    GroupColor.ORANGE -> if (dark) Color(0xFFFF9A3D) else Color(0xFFF07800)
    GroupColor.YELLOW -> if (dark) Color(0xFFF5C842) else Color(0xFFD4A100)
    GroupColor.GREEN -> if (dark) Color(0xFF5BCB6E) else Color(0xFF2F9E44)
    GroupColor.TEAL -> if (dark) Color(0xFF3CCFC9) else Color(0xFF0F9D9A)
    GroupColor.BLUE -> if (dark) Color(0xFF6B9BFF) else Color(0xFF2F6FED)
    GroupColor.PURPLE -> if (dark) Color(0xFFB18CFF) else Color(0xFF8B5CF6)
    GroupColor.PINK -> if (dark) Color(0xFFFF85BF) else Color(0xFFE0559B)
}

/** 按当前深浅色取分组颜色 */
@Composable
@ReadOnlyComposable
fun groupAccentColor(color: GroupColor): Color = groupAccentColor(color, TrackrTheme.isDark)

/** 颜色的名字（读屏、选色时的说明）；null = 不设颜色 */
fun groupColorLabel(color: GroupColor?): StringResource = when (color) {
    null -> Res.string.group_color_none
    GroupColor.RED -> Res.string.group_color_red
    GroupColor.ORANGE -> Res.string.group_color_orange
    GroupColor.YELLOW -> Res.string.group_color_yellow
    GroupColor.GREEN -> Res.string.group_color_green
    GroupColor.TEAL -> Res.string.group_color_teal
    GroupColor.BLUE -> Res.string.group_color_blue
    GroupColor.PURPLE -> Res.string.group_color_purple
    GroupColor.PINK -> Res.string.group_color_pink
}
