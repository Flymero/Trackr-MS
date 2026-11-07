package com.trackr.ui.screens.linedetail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.model.LiveBus
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_idle_info_close
import com.trackr.feature.line.resources.line_idle_info_direction
import com.trackr.feature.line.resources.line_idle_info_direction_value
import com.trackr.feature.line.resources.line_idle_info_line
import com.trackr.feature.line.resources.line_idle_info_note
import com.trackr.feature.line.resources.line_idle_info_plate
import com.trackr.feature.line.resources.line_idle_info_position
import com.trackr.feature.line.resources.line_idle_info_position_unknown
import com.trackr.feature.line.resources.line_idle_info_speed
import com.trackr.feature.line.resources.line_idle_info_title
import org.jetbrains.compose.resources.stringResource

/**
 * 停场/非运营车辆的只读信息卡：不提供追踪入口（停场车追踪无意义），纯查看。
 */
@Composable
fun IdleBusInfoDialog(
    bus: LiveBus,
    lineName: String,
    towards: String,
    positionName: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(LineRes.string.line_idle_info_title)) },
        text = {
            Column {
                InfoRow(
                    label = stringResource(LineRes.string.line_idle_info_plate),
                    value = bus.friendlyPlate
                )
                InfoRow(
                    label = stringResource(LineRes.string.line_idle_info_line),
                    value = lineName
                )
                InfoRow(
                    label = stringResource(LineRes.string.line_idle_info_direction),
                    value = stringResource(LineRes.string.line_idle_info_direction_value, towards)
                )
                InfoRow(
                    label = stringResource(LineRes.string.line_idle_info_position),
                    value = positionName ?: stringResource(LineRes.string.line_idle_info_position_unknown)
                )
                InfoRow(
                    label = stringResource(LineRes.string.line_idle_info_speed),
                    value = "${bus.speedKmh.toInt()} km/h"
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(LineRes.string.line_idle_info_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(LineRes.string.line_idle_info_close))
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
