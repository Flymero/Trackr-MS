package com.trackr.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.group_color_blue
import com.trackr.core.designsystem.resources.group_color_custom
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
 * 不当文字或底色用；色板颜色深色模式提亮一档，在深色卡片上也看得清；自定义颜色深浅色都用用户选的那个。
 */
fun groupAccentColor(color: GroupColor, dark: Boolean): Color = when (color) {
    is GroupColor.Custom -> Color(0xFF000000L or color.rgb.toLong())
    is GroupColor.Preset -> presetColor(color, dark)
}

private fun presetColor(color: GroupColor.Preset, dark: Boolean): Color = when (color) {
    GroupColor.Preset.RED -> if (dark) Color(0xFFFF6B6E) else Color(0xFFE5484D)
    GroupColor.Preset.ORANGE -> if (dark) Color(0xFFFF9A3D) else Color(0xFFF07800)
    GroupColor.Preset.YELLOW -> if (dark) Color(0xFFF5C842) else Color(0xFFD4A100)
    GroupColor.Preset.GREEN -> if (dark) Color(0xFF5BCB6E) else Color(0xFF2F9E44)
    GroupColor.Preset.TEAL -> if (dark) Color(0xFF3CCFC9) else Color(0xFF0F9D9A)
    GroupColor.Preset.BLUE -> if (dark) Color(0xFF6B9BFF) else Color(0xFF2F6FED)
    GroupColor.Preset.PURPLE -> if (dark) Color(0xFFB18CFF) else Color(0xFF8B5CF6)
    GroupColor.Preset.PINK -> if (dark) Color(0xFFFF85BF) else Color(0xFFE0559B)
}

/** 按当前深浅色取分组颜色 */
@Composable
@ReadOnlyComposable
fun groupAccentColor(color: GroupColor): Color = groupAccentColor(color, TrackrTheme.isDark)

/** 颜色的名字（读屏、选色时的说明）；null = 不设颜色 */
fun groupColorLabel(color: GroupColor?): StringResource = when (color) {
    null -> Res.string.group_color_none
    is GroupColor.Custom -> Res.string.group_color_custom
    GroupColor.Preset.RED -> Res.string.group_color_red
    GroupColor.Preset.ORANGE -> Res.string.group_color_orange
    GroupColor.Preset.YELLOW -> Res.string.group_color_yellow
    GroupColor.Preset.GREEN -> Res.string.group_color_green
    GroupColor.Preset.TEAL -> Res.string.group_color_teal
    GroupColor.Preset.BLUE -> Res.string.group_color_blue
    GroupColor.Preset.PURPLE -> Res.string.group_color_purple
    GroupColor.Preset.PINK -> Res.string.group_color_pink
}
