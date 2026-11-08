@file:OptIn(ExperimentalMaterial3Api::class)

package com.trackr.ui.screens.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.action_cancel
import com.trackr.core.designsystem.resources.action_confirm
import com.trackr.core.designsystem.resources.action_delete
import com.trackr.core.designsystem.resources.action_save
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.resources.ic_delete
import com.trackr.core.designsystem.resources.ic_warning
import com.trackr.core.model.ScheduledReminder
import com.trackr.feature.me.resources.Res as MeRes
import com.trackr.feature.me.resources.me_reminder_after
import com.trackr.feature.me.resources.me_reminder_auto_start
import com.trackr.feature.me.resources.me_reminder_auto_start_desc
import com.trackr.feature.me.resources.me_reminder_board_at
import com.trackr.feature.me.resources.me_reminder_change_time
import com.trackr.feature.me.resources.me_reminder_delete
import com.trackr.feature.me.resources.me_reminder_delete_body
import com.trackr.feature.me.resources.me_reminder_delete_title
import com.trackr.feature.me.resources.me_reminder_edit_title
import com.trackr.feature.me.resources.me_reminder_go_settings
import com.trackr.feature.me.resources.me_reminder_gone
import com.trackr.feature.me.resources.me_reminder_gone_hint
import com.trackr.feature.me.resources.me_reminder_hint
import com.trackr.feature.me.resources.me_reminder_line
import com.trackr.feature.me.resources.me_reminder_loading
import com.trackr.feature.me.resources.me_reminder_no_alarm_permission
import com.trackr.feature.me.resources.me_reminder_repeat
import com.trackr.feature.me.resources.me_reminder_set_time
import com.trackr.feature.me.resources.me_reminder_this
import com.trackr.feature.me.resources.me_reminder_time_hint
import com.trackr.feature.me.resources.me_reminder_towards
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 定时提醒编辑页（第 25 步新增）：改时间、改重复日、开关「到点自动追踪」、删除。
 * 线路和上车站不在这里改，顶部那一行可以跳回线路页。
 *
 * 开了「到点自动开始追踪」但系统没给精确闹钟权限时，页面里提示一句并给「去设置」（[ReminderEditUiState.showExactAlarmHint]）。
 */
@Composable
fun ReminderEditScreen(
    state: ReminderEditUiState,
    onEvent: (ReminderEditEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    val draft = state.draft

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { MeMessageHost(state.userMessages) { onEvent(ReminderEditEvent.MessageShown(it)) } },
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(MeRes.string.me_reminder_edit_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(ReminderEditEvent.Back) }) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_back),
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                actions = {
                    if (draft != null) {
                        IconButton(
                            onClick = { onEvent(ReminderEditEvent.AskDelete) },
                            modifier = Modifier.testTag("btn_delete_reminder")
                        ) {
                            Icon(
                                imageVector = vectorResource(Res.drawable.ic_delete),
                                contentDescription = stringResource(MeRes.string.me_reminder_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (draft != null) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Button(
                        onClick = { onEvent(ReminderEditEvent.Save) },
                        enabled = state.changed && !state.saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(48.dp)
                            .testTag("btn_save_reminder")
                    ) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            }
        }
    ) { innerPadding ->
        if (draft == null) {
            MissingReminder(
                loading = !state.missing,
                onBack = { onEvent(ReminderEditEvent.Back) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "time") {
                TimeCard(
                    reminder = draft,
                    onClick = { showTimePicker = true }
                )
            }

            item(key = "repeat") {
                SettingsCard(title = stringResource(MeRes.string.me_reminder_repeat)) {
                    RepeatPresetRow(
                        days = draft.daysOfWeek,
                        onSelect = { onEvent(ReminderEditEvent.SetDays(it)) },
                        onToggleDay = { onEvent(ReminderEditEvent.ToggleDay(it)) }
                    )
                }
            }

            if (!state.supportsAutoStart) item(key = "notification_only") {
                SettingsCard(title = stringResource(MeRes.string.me_reminder_after)) {
                    Text("到点发送通知，打开追客后可开始追踪", modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.supportsAutoStart) item(key = "auto_start") {
                SettingsCard(title = stringResource(MeRes.string.me_reminder_after)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(MeRes.string.me_reminder_auto_start),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(MeRes.string.me_reminder_auto_start_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = draft.autoStartLiveTracking,
                            onCheckedChange = { onEvent(ReminderEditEvent.SetAutoStart(it)) }
                        )
                    }

                    if (state.showExactAlarmHint) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        ExactAlarmHint(onOpenSettings = { onEvent(ReminderEditEvent.OpenExactAlarmSettings) })
                    }
                }
            }

            item(key = "line") {
                SettingsCard(title = stringResource(MeRes.string.me_reminder_this)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEvent(ReminderEditEvent.OpenLine) }
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = draft.lineName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(MeRes.string.me_reminder_towards, draft.endStopName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(MeRes.string.me_reminder_board_at, draft.targetStopName, draft.targetOrder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(MeRes.string.me_reminder_line),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            item(key = "hint") {
                Text(
                    text = stringResource(MeRes.string.me_reminder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    if (showTimePicker && draft != null) {
        TimePickerDialog(
            initialHour = draft.hour,
            initialMinute = draft.minute,
            onConfirm = { hour, minute ->
                onEvent(ReminderEditEvent.SetTime(hour, minute))
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false }
        )
    }

    if (state.confirmingDelete) {
        AlertDialog(
            onDismissRequest = { onEvent(ReminderEditEvent.CancelDelete) },
            title = { Text(stringResource(MeRes.string.me_reminder_delete_title)) },
            text = { Text(if (draft != null) stringResource(MeRes.string.me_reminder_delete_body, reminderSummary(draft)) else "") },
            confirmButton = {
                TextButton(onClick = { onEvent(ReminderEditEvent.ConfirmDelete) }) {
                    Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(ReminderEditEvent.CancelDelete) }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }
}

/** 提醒已经被删掉（比如在别处删了）时的兜底 */
@Composable
private fun MissingReminder(
    loading: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (loading) stringResource(MeRes.string.me_reminder_loading) else stringResource(MeRes.string.me_reminder_gone),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (loading) "" else stringResource(MeRes.string.me_reminder_gone_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!loading) {
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text(stringResource(Res.string.action_back)) }
        }
    }
}

/** 大号时间，点一下弹时间选择器 */
@Composable
private fun TimeCard(
    reminder: ScheduledReminder,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("card_reminder_time"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reminder.formatTime(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(MeRes.string.me_reminder_time_hint, shortRepeatText(reminder.daysOfWeek)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = vectorResource(Res.drawable.ic_chevron_right),
                contentDescription = stringResource(MeRes.string.me_reminder_change_time),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** 重复日：上面四个常用预设，下面七个星期几 */
@Composable
private fun RepeatPresetRow(
    days: Set<Int>,
    onSelect: (Set<Int>) -> Unit,
    onToggleDay: (Int) -> Unit
) {
    val presets = listOf(
        "仅一次" to emptySet(),
        "工作日" to setOf(1, 2, 3, 4, 5),
        "每天" to (1..7).toSet(),
        "周末" to setOf(6, 7)
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (label, value) ->
                Pill(
                    label = label,
                    selected = days == value,
                    onClick = { onSelect(value) }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..7).forEach { day ->
                DayToggle(
                    label = DAY_NAMES.getValue(day),
                    selected = day in days,
                    onClick = { onToggleDay(day) }
                )
            }
        }
    }
}

private val DAY_NAMES = mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")

@Composable
private fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun DayToggle(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .size(38.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** 一组设置：小号主色标题 + 圆角卡片 */
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun ExactAlarmHint(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = vectorResource(Res.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(MeRes.string.me_reminder_no_alarm_permission),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                modifier = Modifier.testTag("btn_exact_alarm_settings")
            ) {
                Text(stringResource(MeRes.string.me_reminder_go_settings), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MeRes.string.me_reminder_set_time)) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) {
                Text(stringResource(Res.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}
