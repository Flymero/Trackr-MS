package com.trackr.core.data.repository

import com.trackr.core.data.local.ReminderStore
import com.trackr.core.domain.platform.ReminderAlarms
import com.trackr.core.model.ScheduledReminder
import com.trackr.data.repository.IScheduledReminderRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared persistence and scheduling. Publish only after a successful durable write. */
class DefaultScheduledReminderRepository private constructor(
    private val store: ReminderStore,
    private val alarms: ReminderAlarms,
    initial: List<ScheduledReminder>,
    private val ioDispatcher: CoroutineDispatcher
) : IScheduledReminderRepository {
    override val supportsAutoStart get() = alarms.supportsAutoStart
    /** 串行化所有读改写，避免 UI 与提醒接收器并发调用时丢失更新 */
    private val mutex = Mutex()

    private val _reminders = MutableStateFlow(initial)
    override val reminders: StateFlow<List<ScheduledReminder>> = _reminders.asStateFlow()

    override suspend fun saveReminder(reminder: ScheduledReminder) = withContext(ioDispatcher) {
        val reminder = if (supportsAutoStart) reminder else reminder.copy(autoStartLiveTracking = false)
        mutex.withLock {
            if (reminder.enabled) check(alarms.requestPermission()) { "请在系统设置中允许通知后再保存提醒" }
            val current = _reminders.value.toMutableList()
            val index = current.indexOfFirst { it.id == reminder.id }
            if (index >= 0) {
                current[index] = reminder
            } else {
                current.add(reminder)
            }
            persistReminders(current)
            alarms.scheduleAndAwait(reminder)
        }
    }

    override suspend fun deleteReminder(id: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = _reminders.value.filterNot { it.id == id }
            persistReminders(current)
            alarms.cancelAndAwait(id)
        }
    }

    override suspend fun toggleReminder(id: String, enabled: Boolean) = withContext(ioDispatcher) {
        mutex.withLock {
            if (enabled) check(alarms.requestPermission()) { "请在系统设置中允许通知后再开启提醒" }
            val current = _reminders.value.map { reminder ->
                if (reminder.id == id) reminder.copy(enabled = enabled) else reminder
            }
            persistReminders(current)
            val updated = current.find { it.id == id }
            if (updated != null) {
                if (enabled) {
                    alarms.scheduleAndAwait(updated)
                } else {
                    alarms.cancelAndAwait(id)
                }
            }
        }
    }

    override fun getReminderById(id: String): ScheduledReminder? {
        return _reminders.value.find { it.id == id }
    }

    private suspend fun persistReminders(list: List<ScheduledReminder>) {
        require(list.filter { it.enabled }.sumOf { it.daysOfWeek.size.coerceAtLeast(1) } <= alarms.maxPendingRequests) {
            "提醒数量超过系统上限，请减少重复日期或关闭部分提醒"
        }
        store.save(list)
        _reminders.value = list
    }

    companion object {
        suspend fun create(store: ReminderStore, alarms: ReminderAlarms,
            dispatcher: CoroutineDispatcher = Dispatchers.Default) =
            DefaultScheduledReminderRepository(store, alarms, store.load(), dispatcher)
    }
}
