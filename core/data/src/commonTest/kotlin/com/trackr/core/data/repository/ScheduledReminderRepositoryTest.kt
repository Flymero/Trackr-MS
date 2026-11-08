package com.trackr.core.data.repository

import com.trackr.core.data.local.ReminderStore
import com.trackr.core.domain.platform.ReminderAlarms
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.testing.FakeReminderAlarms
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ScheduledReminderRepositoryTest {
    private val reminder = ScheduledReminder(id = "r", hour = 8, minute = 0, cityId = "sh", cityName = "上海",
        lineId = "71", lineName = "71路", direction = 0, endStopName = "终点", targetStopId = "s3",
        targetStopName = "站3", targetOrder = 3, autoStartLiveTracking = true)
    private class Store : ReminderStore {
        var items = emptyList<ScheduledReminder>()
        var fail = false
        override suspend fun load() = items
        override suspend fun save(list: List<ScheduledReminder>) {
            if (fail) error("disk full")
            items = list
        }
    }
    @Test fun deniedPermissionDoesNotPersistOrSchedule() = runTest {
        val store = Store()
        val fake = FakeReminderAlarms()
        val alarms = object : ReminderAlarms by fake { override suspend fun requestPermission() = false }
        val repo = DefaultScheduledReminderRepository.create(store, alarms, StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalStateException> { repo.saveReminder(reminder) }
        assertTrue(repo.reminders.value.isEmpty())
        assertTrue(store.items.isEmpty())
        assertTrue(fake.scheduled.isEmpty())
    }
    @Test fun storageFailureDoesNotPublishOrSchedule() = runTest {
        val store = Store().apply { fail = true }
        val alarms = FakeReminderAlarms()
        val repo = DefaultScheduledReminderRepository.create(store, alarms, StandardTestDispatcher(testScheduler))
        assertFailsWith<IllegalStateException> { repo.saveReminder(reminder) }
        assertTrue(repo.reminders.value.isEmpty())
        assertTrue(alarms.scheduled.isEmpty())
    }
    @Test fun notificationOnlyPlatformNormalizesAndReopens() = runTest {
        val store = Store()
        val alarms = object : ReminderAlarms by FakeReminderAlarms() { override val supportsAutoStart = false }
        val repo = DefaultScheduledReminderRepository.create(store, alarms, StandardTestDispatcher(testScheduler))
        repo.saveReminder(reminder)
        assertFalse(repo.reminders.value.single().autoStartLiveTracking)
        val reopened = DefaultScheduledReminderRepository.create(store, alarms, StandardTestDispatcher(testScheduler))
        assertEquals(repo.reminders.value, reopened.reminders.value)
        repo.toggleReminder("r", false)
        assertFalse(repo.reminders.value.single().enabled)
        repo.deleteReminder("r")
        assertTrue(store.items.isEmpty())
    }
    @Test fun concurrentWritesAreNotLostAndNotificationLimitIsEnforced() = runTest {
        val store = Store()
        val alarms = object : ReminderAlarms by FakeReminderAlarms() { override val maxPendingRequests = 10 }
        val repo = DefaultScheduledReminderRepository.create(store, alarms, StandardTestDispatcher(testScheduler))
        coroutineScope {
            launch { repo.saveReminder(reminder) }
            launch { repo.saveReminder(reminder.copy(id = "second")) }
        }
        assertEquals(2, repo.reminders.value.size)
        assertFailsWith<IllegalArgumentException> { repo.saveReminder(reminder.copy(id = "third")) }
        assertEquals(2, store.items.size)
    }
}
