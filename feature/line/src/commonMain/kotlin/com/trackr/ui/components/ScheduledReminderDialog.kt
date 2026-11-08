package com.trackr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_cancel
import com.trackr.core.designsystem.resources.ic_clock
import com.trackr.core.model.ScheduledReminder
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_reminder_auto_track
import com.trackr.feature.line.resources.line_reminder_auto_track_desc
import com.trackr.feature.line.resources.line_reminder_everyday
import com.trackr.feature.line.resources.line_reminder_once
import com.trackr.feature.line.resources.line_reminder_repeat
import com.trackr.feature.line.resources.line_reminder_save
import com.trackr.feature.line.resources.line_reminder_subtitle
import com.trackr.feature.line.resources.line_reminder_title
import com.trackr.feature.line.resources.line_reminder_workdays
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduledReminderDialog(
    lineName: String,
    endStopName: String,
    targetStopName: String,
    initialReminder: ScheduledReminder? = null,
    supportsAutoStart: Boolean = true,
    onDismissRequest: () -> Unit,
    onConfirm: (hour: Int, minute: Int, daysOfWeek: Set<Int>, autoStartTracking: Boolean) -> Unit
) {
    val initialHour = initialReminder?.hour ?: 8
    val initialMinute = initialReminder?.minute ?: 0
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )

    var selectedDays by remember {
        mutableStateOf(initialReminder?.daysOfWeek ?: setOf(1, 2, 3, 4, 5))
    }
    var autoStartTracking by remember {
        mutableStateOf(initialReminder?.autoStartLiveTracking ?: false)
    }

    val dayLabels = listOf(
        1 to "一",
        2 to "二",
        3 to "三",
        4 to "四",
        5 to "五",
        6 to "六",
        7 to "日"
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_clock),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(LineRes.string.line_reminder_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(LineRes.string.line_reminder_subtitle, lineName, endStopName, targetStopName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 时间输入面板
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        TimeInput(state = timePickerState)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 重复周期预设 Chips
                Text(
                    text = stringResource(LineRes.string.line_reminder_repeat),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isWorkdays = selectedDays == setOf(1, 2, 3, 4, 5)
                    val isEveryday = selectedDays == setOf(1, 2, 3, 4, 5, 6, 7)
                    val isOneTime = selectedDays.isEmpty()

                    FilterChip(
                        selected = isWorkdays,
                        onClick = { selectedDays = setOf(1, 2, 3, 4, 5) },
                        label = { Text(stringResource(LineRes.string.line_reminder_workdays)) }
                    )
                    FilterChip(
                        selected = isEveryday,
                        onClick = { selectedDays = setOf(1, 2, 3, 4, 5, 6, 7) },
                        label = { Text(stringResource(LineRes.string.line_reminder_everyday)) }
                    )
                    FilterChip(
                        selected = isOneTime,
                        onClick = { selectedDays = emptySet() },
                        label = { Text(stringResource(LineRes.string.line_reminder_once)) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 独立星期选择
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    dayLabels.forEach { (day, label) ->
                        val isSelected = selectedDays.contains(day)
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    selectedDays = if (isSelected) {
                                        selectedDays - day
                                    } else {
                                        selectedDays + day
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // iOS reminders notify only; polling starts when the app is opened.
                if (!supportsAutoStart) Text("到点发送通知，打开追客后可开始追踪")
                if (supportsAutoStart) Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { autoStartTracking = !autoStartTracking }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(LineRes.string.line_reminder_auto_track),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(LineRes.string.line_reminder_auto_track_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = autoStartTracking,
                            onCheckedChange = { autoStartTracking = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        timePickerState.hour,
                        timePickerState.minute,
                        selectedDays,
                        autoStartTracking && supportsAutoStart
                    )
                }
            ) {
                Text(stringResource(LineRes.string.line_reminder_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}
