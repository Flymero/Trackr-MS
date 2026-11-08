package com.trackr.ui.screens.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.domain.platform.ReminderAlarms
import com.trackr.core.model.ScheduledReminder
import com.trackr.data.repository.IScheduledReminderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 定时提醒编辑页状态。
 *
 * @param draft 正在改的提醒；[missing] 为 true（提醒已被删掉）时为 null
 * @param showExactAlarmHint 开了「到点自动开始追踪」但系统没给精确闹钟权限：到点只发通知，页面提示一句并给「去设置」
 * @param closed 已保存或已删除，Route 看到就返回
 */
data class ReminderEditUiState(
    val supportsAutoStart: Boolean = true,
    val draft: ScheduledReminder? = null,
    val missing: Boolean = false,
    val changed: Boolean = false,
    val showExactAlarmHint: Boolean = false,
    val confirmingDelete: Boolean = false,
    val saving: Boolean = false,
    val closed: Boolean = false,
    val userMessages: List<UserMessage> = emptyList()
)

sealed interface ReminderEditEvent {
    data class SetTime(val hour: Int, val minute: Int) : ReminderEditEvent
    /** 整组设重复日（工作日 / 每天 / 仅一次 = 空集） */
    data class SetDays(val days: Set<Int>) : ReminderEditEvent
    /** 点一个星期几（1 = 周一 … 7 = 周日） */
    data class ToggleDay(val day: Int) : ReminderEditEvent
    data class SetAutoStart(val enabled: Boolean) : ReminderEditEvent
    data object OpenExactAlarmSettings : ReminderEditEvent
    data object Save : ReminderEditEvent
    data object AskDelete : ReminderEditEvent
    data object CancelDelete : ReminderEditEvent
    data object ConfirmDelete : ReminderEditEvent
    data class MessageShown(val id: Long) : ReminderEditEvent

    /** 导航（Route 处理）：返回、去这条提醒的线路页 */
    data object Back : ReminderEditEvent
    data object OpenLine : ReminderEditEvent
}

class ReminderEditViewModel(
    private val reminderId: String,
    private val reminderRepository: IScheduledReminderRepository,
    private val alarms: ReminderAlarms,
    private val notices: MeNotices
) : ViewModel() {

    private val original: ScheduledReminder? = reminderRepository.getReminderById(reminderId)

    private val _uiState = MutableStateFlow(
        ReminderEditUiState(
            supportsAutoStart = alarms.supportsAutoStart,
            draft = original,
            missing = original == null,
            showExactAlarmHint = original?.let { needsExactAlarmHint(it) } ?: false
        )
    )
    val uiState: StateFlow<ReminderEditUiState> = _uiState.asStateFlow()

    fun onEvent(event: ReminderEditEvent) {
        when (event) {
            is ReminderEditEvent.SetTime -> editDraft {
                it.copy(hour = event.hour.coerceIn(0, 23), minute = event.minute.coerceIn(0, 59))
            }
            is ReminderEditEvent.SetDays -> editDraft { it.copy(daysOfWeek = event.days.filter { d -> d in 1..7 }.toSet()) }
            is ReminderEditEvent.ToggleDay -> editDraft {
                if (event.day !in 1..7) it
                else it.copy(daysOfWeek = if (event.day in it.daysOfWeek) it.daysOfWeek - event.day else it.daysOfWeek + event.day)
            }
            is ReminderEditEvent.SetAutoStart -> editDraft { it.copy(autoStartLiveTracking = event.enabled) }
            ReminderEditEvent.OpenExactAlarmSettings -> {
                if (!alarms.openExactAlarmSettings()) {
                    addMessage("打不开系统设置。请到系统设置里给追客开「闹钟和提醒」")
                }
            }
            ReminderEditEvent.Save -> save()
            ReminderEditEvent.AskDelete -> _uiState.update { it.copy(confirmingDelete = it.draft != null) }
            ReminderEditEvent.CancelDelete -> _uiState.update { it.copy(confirmingDelete = false) }
            ReminderEditEvent.ConfirmDelete -> delete()
            is ReminderEditEvent.MessageShown -> _uiState.update { state ->
                state.copy(userMessages = state.userMessages.filterNot { it.id == event.id })
            }
            ReminderEditEvent.Back, ReminderEditEvent.OpenLine -> Unit
        }
    }

    /** 回到前台时重查精确闹钟权限（用户可能刚从系统设置回来） */
    fun onScreenResumed() {
        _uiState.update { state ->
            state.copy(showExactAlarmHint = state.draft?.let { needsExactAlarmHint(it) } ?: false)
        }
    }

    private fun needsExactAlarmHint(reminder: ScheduledReminder): Boolean =
        alarms.supportsAutoStart && reminder.autoStartLiveTracking && !alarms.canScheduleExact()

    private inline fun editDraft(transform: (ScheduledReminder) -> ScheduledReminder) {
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            val next = transform(draft)
            state.copy(
                draft = next,
                changed = next != original,
                showExactAlarmHint = needsExactAlarmHint(next)
            )
        }
    }

    private fun save() {
        val state = _uiState.value
        val draft = state.draft ?: return
        if (state.saving || state.closed) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                reminderRepository.saveReminder(draft)
                notices.post("提醒已保存：${reminderSummary(draft)}")
                _uiState.update { it.copy(saving = false, closed = true) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update { it.copy(saving = false) }
                addMessage(error.message ?: "提醒保存失败，请重试")
            }
        }
    }

    private fun delete() {
        val state = _uiState.value
        if (state.draft == null || state.closed) return
        _uiState.update { it.copy(confirmingDelete = false, saving = true) }
        viewModelScope.launch {
            try {
                reminderRepository.deleteReminder(reminderId)
                notices.post("提醒已删除")
                _uiState.update { it.copy(saving = false, closed = true) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update { it.copy(saving = false) }
                addMessage(error.message ?: "提醒保存失败，请重试")
            }
        }
    }

    private fun addMessage(text: String) {
        _uiState.update { it.copy(userMessages = it.userMessages.plusMessage(text)) }
    }
}
