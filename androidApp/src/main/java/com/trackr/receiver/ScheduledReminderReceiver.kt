package com.trackr.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.trackr.core.domain.platform.ReminderAlarms
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.navigation.deeplink.DeepLinkUris
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.deeplink.DeepLinkIntents
import com.trackr.di.AppScopeQualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 定时提醒闹钟触发接收器
 * 到点后台执行单次线路实时状态拉取，并在通知栏发提醒和「开始追踪」按钮
 */
class ScheduledReminderReceiver : BroadcastReceiver(), KoinComponent {

    private val repository: ITransitRepository by inject()
    private val reminderRepository: IScheduledReminderRepository by inject()
    private val reminderScheduler: ReminderAlarms by inject()
    private val appScope: CoroutineScope by inject(AppScopeQualifier)
    private val trackingController: TrackingController by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_TRIGGER_REMINDER && action != ACTION_START_TRACKING_NOW) {
            return
        }

        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID).orEmpty()
        if (reminderId.isEmpty()) return

        if (action == ACTION_START_TRACKING_NOW) {
            handleStartTrackingNow(context, reminderId)
            return
        }

        val pendingResult = goAsync()
        // 应用级作用域（原来每次新建 CoroutineScope），仍在 IO 线程执行
        appScope.launch(Dispatchers.IO) {
            try {
                handleReminderTrigger(context, reminderId)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing scheduled reminder $reminderId", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    @VisibleForTesting
    internal suspend fun handleReminderTrigger(context: Context, reminderId: String) {
        val reminder = reminderRepository.getReminderById(reminderId) ?: return
        if (!reminder.enabled) return

        // 1. 处理下一次调度
        if (reminder.isOneTime) {
            // 一次性提醒触发后置为未启用
            reminderRepository.toggleReminder(reminder.id, false)
        } else {
            // 周期性提醒重新安排下一个周期
            reminderScheduler.schedule(reminder)
        }

        // 2. 如果配置了到点自动追踪，仅在精确闹钟（享有后台启动前台服务豁免）触发时直接拉起前台服务；
        //    非精确闹钟或启动被系统拒绝时降级为带「开始追踪」按钮的普通提醒通知（通知按钮点击享有豁免）
        var autoStartFallback = false
        if (reminder.autoStartLiveTracking) {
            if (shouldAutoStartTracking(reminder, reminderScheduler.canScheduleExact()) &&
                tryStartTracking(reminder)
            ) {
                return
            }
            autoStartFallback = true
        }

        // 3. 否则拉取单次实时公交数据并弹出通知卡片
        val city = TransitCity(
            cityId = reminder.cityId,
            cityName = reminder.cityName,
            mygolbsCityCode = reminder.mygolbsCityCode
        )

        val detailResult = withTimeoutOrNull(8_000L) {
            repository.getLineDetail(
                city = city,
                lineId = reminder.lineId,
                targetOrder = reminder.targetOrder,
                stationId = reminder.targetStopId,
                source = reminder.dataSourceType,
                lineNameHint = reminder.lineName,
                directionHint = reminder.direction
            )
        }

        val summaryText: String
        val contentText: String

        if (detailResult != null && detailResult.isSuccess) {
            val detail = detailResult.getOrNull()
            if (detail != null) {
                val candidateBuses = detail.buses.filter { it.order <= reminder.targetOrder }
                val bestBus = candidateBuses.maxByOrNull { it.order.toFloat() + it.segmentProgress }

                if (bestBus != null) {
                    val stopsAway = bestBus.stopsTo(reminder.targetOrder).coerceAtLeast(0)
                    val eta = if (bestBus.etaSeconds > 0) {
                        "${(bestBus.etaSeconds + 59) / 60} 分钟"
                    } else {
                        val estMinutes = (stopsAway * 2.5).toInt().coerceAtLeast(1)
                        "约 $estMinutes 分钟"
                    }

                    summaryText = if (stopsAway == 0) {
                        "即将进站"
                    } else {
                        "距目标站还有 $stopsAway 站 ($eta)"
                    }

                    val currentStopName = detail.stops.find { it.order == bestBus.order }?.name
                    contentText = if (!currentStopName.isNullOrBlank()) {
                        "最近车辆已到达【$currentStopName】，目标站：${reminder.targetStopName}"
                    } else {
                        "目标站：${reminder.targetStopName}，请注意出门候车"
                    }
                } else {
                    summaryText = when (detail.operatingState) {
                        LineOperatingState.WAITING -> "等待发车 (首班 ${detail.line.firstTime})"
                        LineOperatingState.ENDED -> "今日运营已结束"
                        LineOperatingState.SUSPENDED -> "线路临时停运"
                        else -> "暂无在途车辆"
                    }
                    contentText = "开往 ${reminder.endStopName} · 目标站：${reminder.targetStopName}"
                }
            } else {
                summaryText = "定时提醒到点了"
                contentText = "点击打开【${reminder.lineName}】查看实时到站动态"
            }
        } else {
            summaryText = "定时提醒到点了"
            contentText = "点击打开【${reminder.lineName}】查看实时到站动态"
        }

        val finalContent = if (autoStartFallback) {
            "$contentText\n系统没让自动开始追踪，点「开始追踪」"
        } else {
            contentText
        }
        showReminderNotification(context, reminder, summaryText, finalContent)
    }

    private fun showReminderNotification(
        context: Context,
        reminder: ScheduledReminder,
        titleSummary: String,
        content: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        ensureNotificationChannel(context, notificationManager)

        // 点击通知跳转线路详情（trackr://line，§3.6）
        val openAppIntent = DeepLinkIntents.activityIntent(
            context,
            DeepLinkUris.line(
                cityId = reminder.cityId,
                lineId = reminder.lineId,
                source = reminder.dataSourceType,
                stopId = reminder.targetStopId,
                targetOrder = reminder.targetOrder,
                lineName = reminder.lineName
            )
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            reminder.id.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 快捷按钮：开始追踪
        val startTrackingIntent = Intent(context, ScheduledReminderReceiver::class.java).apply {
            action = ACTION_START_TRACKING_NOW
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        val startTrackingPendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode() + 1,
            startTrackingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val iconRes = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SCHEDULED)
            .setSmallIcon(iconRes)
            .setContentTitle("${reminder.lineName} · $titleSummary")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$content\n开往：${reminder.endStopName}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(
                iconRes,
                "开始追踪",
                startTrackingPendingIntent
            )
            .build()

        notificationManager.notify(NOTIFICATION_BASE_ID + reminder.id.hashCode() % 10000, notification)
    }

    @VisibleForTesting
    internal fun handleStartTrackingNow(context: Context, reminderId: String) {
        val reminder = reminderRepository.getReminderById(reminderId) ?: return
        // 通知按钮点击享有后台启动前台服务豁免，这里仍兜底捕获异常避免崩溃
        if (!tryStartTracking(reminder)) return

        // 关闭快报通知（启动失败时保留通知，方便用户重试）
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_BASE_ID + reminder.id.hashCode() % 10000)
    }

    /**
     * 尝试为提醒开始追踪（经 [TrackingController.start]：会话写进仓库后启动前台服务）。
     * Android 12+ 后台受限时启动前台服务会被拒（ForegroundServiceStartNotAllowedException），
     * 此时 [TrackingController.start] 返回 false、不留下会话，由调用方降级为通知。
     * 这里仍兜底捕获异常避免崩溃。
     */
    internal fun tryStartTracking(
        reminder: ScheduledReminder,
        starter: (TrackingTarget) -> Boolean = trackingController::start
    ): Boolean {
        return try {
            starter(trackingTargetFor(reminder)).also { started ->
                if (!started) Log.w(TAG, "Cannot start tracking service for ${reminder.id}")
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot start tracking service for ${reminder.id}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start tracking service for ${reminder.id}", e)
            false
        }
    }

    private fun ensureNotificationChannel(context: Context, notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID_SCHEDULED)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_SCHEDULED,
                    CHANNEL_NAME_SCHEDULED,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "定时推送用户关心的通勤公交到站状态"
                    enableVibration(true)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledReminderRx"

        const val ACTION_TRIGGER_REMINDER = "com.trackr.action.TRIGGER_REMINDER"
        const val ACTION_START_TRACKING_NOW = "com.trackr.action.START_TRACKING_NOW"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"

        /**
         * 到点是否直接拉起前台追踪服务：只有配置了自动追踪且本次为精确闹钟时才直接启动。
         * Android 12+ 未授予精确闹钟权限时闹钟以非精确方式触发，不享有后台启动前台服务的豁免。
         */
        internal fun shouldAutoStartTracking(reminder: ScheduledReminder, canScheduleExact: Boolean): Boolean {
            return reminder.autoStartLiveTracking && canScheduleExact
        }

        /** 提醒对应的单线路追踪目标（提前 2 站提醒） */
        internal fun trackingTargetFor(reminder: ScheduledReminder): TrackingTarget.SingleLine =
            TrackingTarget.SingleLine(
                cityId = reminder.cityId,
                lineId = reminder.lineId,
                lineName = reminder.lineName,
                lineNo = reminder.lineName,
                endStopName = reminder.endStopName,
                targetStopId = reminder.targetStopId,
                targetStopName = reminder.targetStopName,
                targetOrder = reminder.targetOrder,
                totalStops = reminder.totalStops,
                alertThresholdStops = 2,
                source = reminder.dataSourceType,
                mygolbsCityCode = reminder.mygolbsCityCode.orEmpty(),
                direction = reminder.direction
            )

        private const val CHANNEL_ID_SCHEDULED = "channel_transit_scheduled"
        private const val CHANNEL_NAME_SCHEDULED = "通勤定时到站提醒"
        internal const val NOTIFICATION_BASE_ID = 20000
    }
}
