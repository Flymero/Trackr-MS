package com.trackr.ui.screens.favoritesedit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.component.ColorPickerDialog
import com.trackr.core.designsystem.resources.ic_check
import com.trackr.core.designsystem.resources.ic_plus
import com.trackr.core.designsystem.theme.groupAccentColor
import com.trackr.core.designsystem.theme.groupColorLabel
import com.trackr.core.model.GroupColor
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 选色控件的 testTag：`group_color_none` / `group_color_RED` … / 自定义是 `group_color_custom` */
internal fun groupColorOptionTag(color: GroupColor?) = "group_color_" + when (color) {
    null -> "none"
    is GroupColor.Preset -> color.name
    is GroupColor.Custom -> "custom"
}

/** 自定义颜色第一次打开选色面板时的起始色（v3 蓝） */
private const val DEFAULT_CUSTOM_RGB = 0x1F5FD6

/**
 * 分组颜色选择（issue #38）：「不设颜色」+ 色板里的颜色 + 自定义，一排排下去，选中的打勾。
 *
 * 自定义：点了打开和主题色同一个选色面板（[ColorPickerDialog]），确定后选中这个颜色；已经是自定义时再点可以接着改。
 */
@Composable
internal fun GroupColorPicker(
    selected: GroupColor?,
    onSelect: (GroupColor?) -> Unit,
    modifier: Modifier = Modifier
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        (listOf<GroupColor?>(null) + GroupColor.Preset.entries).forEach { color ->
            ColorSwatch(color = color, selected = selected == color, onClick = { onSelect(color) })
        }
        CustomSwatch(custom = selected as? GroupColor.Custom, onClick = { picking = true })
    }
    if (picking) {
        // 起始色：当前是自定义就接着改它，是色板颜色就从那个颜色开始调
        val initial = when (selected) {
            is GroupColor.Custom -> selected.rgb
            is GroupColor.Preset -> groupAccentColor(selected, dark = false).toArgb() and 0xFFFFFF
            null -> DEFAULT_CUSTOM_RGB
        }
        ColorPickerDialog(
            rgb = initial,
            onDismiss = { picking = false },
            onColor = { rgb ->
                picking = false
                onSelect(GroupColor.Custom(rgb))
            }
        )
    }
}

/** 「自定义」：没选时是一个彩虹圆圈加「+」，选了是那个颜色加勾 */
@Composable
private fun CustomSwatch(custom: GroupColor.Custom?, onClick: () -> Unit) {
    val label = stringResource(groupColorLabel(GroupColor.Custom(0)))
    val fill = custom?.let { groupAccentColor(it) }
    Box(
        modifier = Modifier
            .size(SwatchSize)
            .clip(CircleShape)
            .background(
                if (fill != null) {
                    Brush.linearGradient(listOf(fill, fill))
                } else {
                    Brush.sweepGradient((0..6).map { Color.hsv((it * 60f) % 360f, 0.75f, 0.95f) })
                }
            )
            .selectable(selected = custom != null, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = label }
            .testTag(groupColorOptionTag(GroupColor.Custom(0))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = vectorResource(if (custom != null) Res.drawable.ic_check else Res.drawable.ic_plus),
            contentDescription = null,
            tint = if (fill != null && fill.luminance() > 0.6f) Color.Black else Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ColorSwatch(color: GroupColor?, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(groupColorLabel(color))
    val outline = MaterialTheme.colorScheme.outline
    val fill = color?.let { groupAccentColor(it) }
    Box(
        modifier = Modifier
            .size(SwatchSize)
            .clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill) else Modifier.border(1.5.dp, outline, CircleShape))
            .then(
                // 「不设颜色」：空心圆加一道斜线
                if (fill == null) {
                    Modifier.drawBehind {
                        val inset = size.minDimension * 0.25f
                        drawLine(
                            color = outline,
                            start = Offset(size.width - inset, inset),
                            end = Offset(inset, size.height - inset),
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }
                } else {
                    Modifier
                }
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = label }
            .testTag(groupColorOptionTag(color)),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_check),
                contentDescription = null,
                tint = when {
                    fill == null -> MaterialTheme.colorScheme.onSurface
                    fill.luminance() > 0.6f -> Color.Black
                    else -> Color.White
                },
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 分组名前的小色点；没设颜色时不画 */
@Composable
internal fun GroupColorDot(color: GroupColor?, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    if (color == null) return
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(groupAccentColor(color))
    )
}

private val SwatchSize = 32.dp
