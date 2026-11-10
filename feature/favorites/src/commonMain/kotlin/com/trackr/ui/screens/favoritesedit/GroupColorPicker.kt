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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_check
import com.trackr.core.designsystem.theme.groupAccentColor
import com.trackr.core.designsystem.theme.groupColorLabel
import com.trackr.core.model.GroupColor
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 选色控件的 testTag：`group_color_none` / `group_color_RED` … */
internal fun groupColorOptionTag(color: GroupColor?) = "group_color_${color?.name ?: "none"}"

/**
 * 分组颜色选择（issue #38）：「不设颜色」+ 色板里的颜色，一排排下去，选中的打勾。
 */
@Composable
internal fun GroupColorPicker(
    selected: GroupColor?,
    onSelect: (GroupColor?) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        (listOf<GroupColor?>(null) + GroupColor.entries).forEach { color ->
            ColorSwatch(color = color, selected = selected == color, onClick = { onSelect(color) })
        }
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
                tint = if (fill != null) Color.White else MaterialTheme.colorScheme.onSurface,
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
