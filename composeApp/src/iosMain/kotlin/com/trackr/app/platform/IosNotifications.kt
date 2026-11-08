@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.trackr.app.platform

import com.trackr.core.data.local.ReminderScheduleStateStore
import com.trackr.core.domain.platform.ReminderAlarms
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.navigation.deeplink.DeepLinkUris
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.trackr.ui.platform.NotificationHealth
import com.trackr.ui.platform.NotificationHealthAccess
import com.trackr.ui.platform.NotificationHealthIssue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.*
import platform.UserNotifications.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns local notifications only; notification delivery never starts polling. */
class IosNotifications(private val scope: CoroutineScope, private val state: ReminderScheduleStateStore) : ReminderAlarms, NotificationHealthAccess {
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private val health = MutableStateFlow(NotificationHealth())
    override val changes = health.asStateFlow()
    override fun status() = health.value

    /** iOS 没有 Android 渠道概念：任何 issue 都落到系统通知设置页（fork 的 issue 化接口） */
    override fun openSettings(issue: NotificationHealthIssue) { IosSystemSettingsLauncher.openAppSettings() }
    suspend fun refreshAuthorization(): Unit = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            health.value = NotificationHealth(notificationsEnabled = settings?.authorizationStatus in listOf(
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional))
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
    private val mutex = Mutex()
    private var triggers: Map<String, Long> = emptyMap()
    var onOneTimeConsumed: (String) -> Unit = {}
    override val supportsAutoStart = false
    override val maxPendingRequests = 64

    override suspend fun requestPermission(): Boolean {
        val granted = suspendCancellableCoroutine { continuation ->
            center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
                if (continuation.isActive) continuation.resume(granted)
            }
        }
        refreshAuthorization()
        return granted
    }

    override fun schedule(reminder: ScheduledReminder) {
        scope.launch { runCatching { scheduleAndAwait(reminder) } }
    }

    override suspend fun scheduleAndAwait(reminder: ScheduledReminder) = mutex.withLock {
        scheduleLocked(reminder, restoring = false)
    }

    private suspend fun scheduleLocked(reminder: ScheduledReminder, restoring: Boolean) {
        val ids = identifiers(reminder.id)
        center.removePendingNotificationRequestsWithIdentifiers(ids)
        center.removeDeliveredNotificationsWithIdentifiers(ids)
        triggers = state.loadAll()
        if (!reminder.enabled) {
            state.remove(reminder.id)
            triggers = triggers - reminder.id
            return
        }
        val now = NSDate().timeIntervalSince1970 * 1000
        val previous = triggers[reminder.id]
        // One-shot reminders remain consumed across process death and backup restoration.
        if (restoring && reminder.isOneTime && previous != null && previous <= now) {
            onOneTimeConsumed(reminder.id)
            return
        }
        val content = UNMutableNotificationContent().apply {
            setTitle("乘车提醒 · ${reminder.lineName}")
            setBody("${reminder.targetStopName}，打开追客查看实时到站并开始追踪")
            setSound(UNNotificationSound.defaultSound())
            setUserInfo(mapOf("url" to DeepLinkUris.line(reminder.cityId, reminder.lineId,
                reminder.dataSourceType, reminder.targetStopId, reminder.targetOrder, reminder.lineName)))
        }
        val days = reminder.daysOfWeek.sorted().ifEmpty { listOf(0) }
        var earliest: Long? = null
        try {
            for (day in days) {
                val components = NSDateComponents().apply {
                    hour = reminder.hour.toLong()
                    minute = reminder.minute.toLong()
                    second = 0
                    if (day != 0) weekday = (day % 7 + 1).toLong()
                }
                var trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, day != 0)
                if (day == 0) {
                    val date = if (restoring && previous != null) NSDate.dateWithTimeIntervalSince1970(previous / 1000.0)
                        else trigger.nextTriggerDate() ?: error("无法计算提醒时间")
                    val exact = NSCalendar.currentCalendar.components(
                        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute,
                        fromDate = date)
                    trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(exact, false)
                }
                val next = trigger.nextTriggerDate()?.timeIntervalSince1970?.times(1000)?.toLong() ?: continue
                earliest = minOf(earliest ?: next, next)
                add(UNNotificationRequest.requestWithIdentifier("reminder.${reminder.id}.$day", content, trigger))
            }
            earliest?.let { state.set(reminder.id, it); triggers = triggers + (reminder.id to it) }
        } catch (error: Exception) {
            center.removePendingNotificationRequestsWithIdentifiers(ids)
            throw error
        }
    }

    override fun cancel(reminderId: String) { scope.launch { cancelAndAwait(reminderId) } }
    override suspend fun cancelAndAwait(reminderId: String) = mutex.withLock {
        center.removePendingNotificationRequestsWithIdentifiers(identifiers(reminderId))
        center.removeDeliveredNotificationsWithIdentifiers(identifiers(reminderId))
        state.remove(reminderId)
        triggers = triggers - reminderId
    }

    /** Restore using persisted one-shot deadlines, so an old reminder cannot recur tomorrow. */
    override fun rescheduleAll(reminders: List<ScheduledReminder>) {
        scope.launch {
            mutex.withLock {
                val pending = suspendCancellableCoroutine<List<UNNotificationRequest>> { continuation ->
                    center.getPendingNotificationRequestsWithCompletionHandler { requests ->
                        if (continuation.isActive) continuation.resume(requests.orEmpty().filterIsInstance<UNNotificationRequest>())
                    }
                }
                val keep = reminders.filter { it.enabled }.flatMap { identifiers(it.id) }.toSet()
                center.removePendingNotificationRequestsWithIdentifiers(pending.map { it.identifier }.filter {
                    it.startsWith("reminder.") && it !in keep
                })
                reminders.forEach { reminder ->
                    currentCoroutineContext().ensureActive()
                    try { scheduleLocked(reminder, restoring = true) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Retry on the next activation; never invent a successful schedule. */ }
                }
            }
        }
    }
    override fun canScheduleExact() = false
    override fun lastScheduledTriggerMillis(reminderId: String) = triggers[reminderId]
    private fun identifiers(id: String) = (0..7).map { "reminder.$id.$it" }

    fun alert(effect: TrackingEngine.Effect, sessionId: String) {
        val text = when (effect) {
            is TrackingEngine.Effect.Alert -> when (effect.kind) {
                TrackingEngine.AlertKind.ARRIVED -> "${effect.state.lineName} 已到 ${effect.state.targetStopName}"
                TrackingEngine.AlertKind.APPROACHING -> "${effect.state.lineName} 即将到站"
                TrackingEngine.AlertKind.MISSED -> "车辆已驶离，请查看下一班"
            }
            is TrackingEngine.Effect.TransferAlert -> "到达 ${effect.stationName}，请换乘 ${effect.nextLineName}"
            is TrackingEngine.Effect.Finished.Boarded -> "已上车，追踪结束"
            TrackingEngine.Effect.Finished.Completed -> "已到达目的地，追踪结束"
        }
        val content = UNMutableNotificationContent().apply {
            setTitle("追客")
            setBody(text)
            setSound(UNNotificationSound.defaultSound())
        }
        scope.launch { runCatching { add(UNNotificationRequest.requestWithIdentifier("tracking.$sessionId", content, null)) } }
    }

    private suspend fun add(request: UNNotificationRequest): Unit = suspendCancellableCoroutine { continuation ->
        center.addNotificationRequest(request) { error ->
            if (continuation.isActive) {
                if (error == null) continuation.resume(Unit)
                else continuation.resumeWithException(IllegalStateException("通知安排失败，请检查系统通知设置"))
            }
        }
    }
}
