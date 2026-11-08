package com.trackr.data.repository

import android.content.Context
import com.trackr.core.data.local.ReminderStore
import com.trackr.core.data.repository.DefaultScheduledReminderRepository
import com.trackr.core.domain.platform.ReminderAlarms
import com.trackr.data.local.SharedPrefsReminderStore
import com.trackr.util.ReminderAlarmScheduler
import kotlinx.coroutines.*

/** Android keeps its legacy storage constructor; persistence is shared with iOS. */
class ScheduledReminderRepository(
    store: ReminderStore,
    alarms: ReminderAlarms,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IScheduledReminderRepository by runBlocking(block = {
    DefaultScheduledReminderRepository.create(store, alarms, ioDispatcher)
}) {
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(SharedPrefsReminderStore(context), ReminderAlarmScheduler(context), ioDispatcher)

    companion object {
        internal const val KEY_CORRUPT_BACKUP_PREFIX = SharedPrefsReminderStore.KEY_CORRUPT_BACKUP_PREFIX
    }
}
