package com.trackr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.trackr.R
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.TrackingSessionState
import com.trackr.service.vendor.LiveUpdatePromotion
import com.trackr.service.vendor.XiaomiFocusAdapter

/**
 * 负责管理高优先级到站/进站预警通知渠道及震动声音提醒
 *
 * 第 4 步从 object 改成类，由 Koin 提供单例；旧的 [ArrivalAlertManager] 暂时转发到这里（第 12 步删）。
 *
 * @param liveProgressNotifier 读「实时通知」开关，与常驻通知共用同一个兜底值
 * @param showOnLockScreenSetting 读「锁屏显示」开关（D3），和常驻通知同一份设置；读不到按开
 */
class ArrivalAlertNotifier(
    private val liveProgressNotifier: LiveProgressNotifier,
    private val showOnLockScreenSetting: () -> Boolean = { true }
) {

    /**
     * 强提醒统一走这里建通知。
     *
     * 锁屏可见性跟「我的 → 锁屏显示」走，判定和常驻通知一致：开着用 PUBLIC（锁屏上看得到线路和站名），
     * 关着用 PRIVATE。**不能不管**：`Notification.visibility` 默认就是 PRIVATE，用户把系统锁屏设成
     * 「隐藏敏感内容」时，这五条提醒在锁屏上会被抹成「内容已隐藏」——震动照响，但人掏出手机看到一条空通知，
     * 而那一刻正是「车到站了，快上车」。
     */
    private fun alertNotification(
        context: Context,
        title: String,
        content: String,
        category: String,
        requestCode: Int
    ): Notification {
        val lockScreenOn = runCatching { showOnLockScreenSetting() }.getOrDefault(true)
        return NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.drawable.ic_stat_trackr)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(category)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(
                if (lockScreenOn) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE
            )
            .setContentIntent(createMainActivityPendingIntent(context, requestCode))
            .build()
    }

    fun triggerTrackingExpired(context: Context, state: TrackingSessionState) {
        ensureNotificationChannel(context)
        val notification = alertNotification(
            context = context,
            title = "追踪已停止",
            content = "${state.lineName} 追踪已超过 ${maxTrackingDurationText()}，已停止追踪。",
            category = NotificationCompat.CATEGORY_STATUS,
            requestCode = NOTIFICATION_ID_EXPIRED
        )
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_EXPIRED, notification) }
    }

    fun triggerTrackingInterrupted(context: Context, state: TrackingSessionState) {
        ensureNotificationChannel(context)
        val notification = alertNotification(
            context = context,
            title = "追踪已中断",
            content = "${state.lineName} 追踪未能自动接续，请打开应用重试。",
            category = NotificationCompat.CATEGORY_STATUS,
            requestCode = NOTIFICATION_ID_INTERRUPTED
        )
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_INTERRUPTED, notification) }
    }

    /** 最长追踪时长写成文案（「3 小时」），常量改了文案自动跟着变 */
    private fun maxTrackingDurationText(): String {
        val hours = TrackingEngine.MAX_TRACKING_DURATION_MILLIS / 3_600_000L
        return if (hours > 0) "$hours 小时" else "${TrackingEngine.MAX_TRACKING_DURATION_MILLIS / 60_000L} 分钟"
    }

    /**
     * 确保高优先级预警渠道创建完毕
     */
    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID_ALERT)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_ALERT,
                    CHANNEL_NAME_ALERT,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "当追踪的公交车即将到站或已经进站时发送高优先级浮动与震动强提醒"
                    enableVibration(true)
                    vibrationPattern = APPROACHING_VIBRATION_PATTERN
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * 当车辆距离目标候车站 stopsRemaining <= state.alertThresholdStops 时触发强提醒通知及三段式节奏震动
     */
    fun triggerApproachingAlert(context: Context, state: TrackingSessionState, sessionId: String = "") {
        ensureNotificationChannel(context)
        val notificationId = alertNotificationId(NOTIFICATION_ID_APPROACHING, sessionId)
        cancelCounterpart(context, alertNotificationId(NOTIFICATION_ID_ARRIVED, sessionId))

        var posted = false
        if (shouldPostStandaloneNotification(context, state)) {
            val etaText = if (state.etaSeconds > 0) {
                "（${RouteProjectionMath.formatEtaText(state.etaSeconds)}）"
            } else {
                ""
            }
            val title = "【即将到站】${state.lineName}"
            val content = "距 ${state.targetStopName} 还有 ${state.stopsRemaining} 站$etaText，请准备上车/下车！"

            val notification = alertNotification(
                context = context,
                title = title,
                content = content,
                category = NotificationCompat.CATEGORY_REMINDER,
                requestCode = notificationId
            )

            try {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
                posted = areNotificationsEnabled(context)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot post approaching alert: missing POST_NOTIFICATIONS permission", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post approaching alert notification", e)
            }
        }
        // 独立通知已由渠道负责振动；只有没发出独立通知时才手动振动，避免振两次
        if (!posted) {
            performWaveformVibration(context, APPROACHING_VIBRATION_PATTERN)
        }
    }

    /**
     * 当车辆到达目标候车站（stopsRemaining == 0 且 isBusArrivedAtStop 或 etaSeconds <= 45）时触发到站提醒及长震动
     */
    fun triggerArrivedAlert(
        context: Context,
        state: TrackingSessionState,
        forceStandalone: Boolean = false,
        sessionId: String = ""
    ) {
        ensureNotificationChannel(context)
        val notificationId = alertNotificationId(NOTIFICATION_ID_ARRIVED, sessionId)
        cancelCounterpart(context, alertNotificationId(NOTIFICATION_ID_APPROACHING, sessionId))

        var posted = false
        if (forceStandalone || shouldPostStandaloneNotification(context, state)) {
            val title = "【车辆已到站】${state.lineName}"
            val content = "${state.lineName} 已到达 ${state.targetStopName}！请注意上车/下车。"

            val notification = alertNotification(
                context = context,
                title = title,
                content = content,
                category = NotificationCompat.CATEGORY_REMINDER,
                requestCode = notificationId
            )

            try {
                NotificationManagerCompat.from(context).notify(notificationId, notification)
                posted = areNotificationsEnabled(context)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot post arrived alert: missing POST_NOTIFICATIONS permission", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post arrived alert notification", e)
            }
        }
        // 独立通知已由渠道负责振动；只有没发出独立通知时才手动振动，避免振两次
        if (!posted) {
            performOneShotVibration(context, ARRIVED_VIBRATION_DURATION_MS)
        }
    }

    /**
     * 当换乘行程第一程到达换乘站时触发换乘提醒及节拍震动，通知用户准备下车换乘
     */
    fun triggerTransferAlert(
        context: Context,
        transferStationName: String,
        nextLineName: String,
        sessionId: String = ""
    ) {
        ensureNotificationChannel(context)
        val notificationId = alertNotificationId(NOTIFICATION_ID_TRANSFER, sessionId)
        // 换乘站即本程下车站：只保留换乘提醒，清掉同一站的到站 / 临近通知，避免连发两条
        cancelCounterpart(context, alertNotificationId(NOTIFICATION_ID_ARRIVED, sessionId))
        cancelCounterpart(context, alertNotificationId(NOTIFICATION_ID_APPROACHING, sessionId))

        val title = "【换乘提醒】到达 $transferStationName"
        val content = "已到达换乘站 $transferStationName，已自动切换追踪下一程：$nextLineName"

        val notification = alertNotification(
            context = context,
            title = title,
            content = content,
            category = NotificationCompat.CATEGORY_REMINDER,
            requestCode = notificationId
        )

        var posted = false
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            posted = areNotificationsEnabled(context)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post transfer alert: missing POST_NOTIFICATIONS permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post transfer alert notification", e)
        }
        // 独立通知由渠道负责振动；没发出去时才手动振动
        if (!posted) {
            performWaveformVibration(context, TRANSFER_VIBRATION_PATTERN)
        }
    }

    /**
     * 没赶上这班车：车已经开走，人还在原地等。
     *
     * 不提醒的话，只在后台把追踪悄悄换成下一班，用户会以为自己还在等原来那班，等到发现时已经晚了。
     * 顺手撤掉「车已到站」那条——车都走了，再挂着只会误导。
     */
    fun triggerMissedBusAlert(context: Context, state: TrackingSessionState, sessionId: String = "") {
        ensureNotificationChannel(context)
        val notificationId = alertNotificationId(NOTIFICATION_ID_MISSED, sessionId)
        cancelCounterpart(context, alertNotificationId(NOTIFICATION_ID_ARRIVED, sessionId))
        cancelCounterpart(context, alertNotificationId(NOTIFICATION_ID_APPROACHING, sessionId))

        val stop = state.targetStopName.ifBlank { "上车站" }
        val next = when {
            state.stopsRemaining == 0 -> "下一班已经到 $stop 了，快跑"
            state.etaSeconds > 0 -> "下一班约 ${RouteProjectionMath.formatEtaText(state.etaSeconds)}到"
            state.stopsRemaining > 0 -> "下一班还有 ${state.stopsRemaining} 站"
            else -> "暂时还没有开往 $stop 的车"
        }

        val notification = alertNotification(
            context = context,
            title = "错过 ${state.lineName} 了",
            content = "$next，仍在追踪 $stop",
            category = NotificationCompat.CATEGORY_REMINDER,
            requestCode = notificationId
        )

        var posted = false
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            posted = areNotificationsEnabled(context)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post missed-bus alert: missing POST_NOTIFICATIONS permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post missed-bus alert notification", e)
        }
        if (!posted) {
            performWaveformVibration(context, MISSED_VIBRATION_PATTERN)
        }
    }

    /**
     * 实时活动（流体云 / 焦点通知 / 状态栏胶囊）已经在通知栏常驻展示同一趟车的进度与到站状态时，
     * 不再重复发一条内容相同的强提醒通知，仅保留震动提示；
     * 若当前系统不支持提升通知或用户未授权实时活动/焦点通知，则回退为发送独立强提醒通知
     */
    internal var liveUpdateActiveChecker: (Context) -> Boolean = { context ->
        when {
            XiaomiFocusAdapter.isXiaomiRom() -> XiaomiFocusAdapter.canShowFocus(context)
            LiveUpdatePromotion.isPromotionSupported() -> LiveUpdatePromotion.canPromote(context)
            else -> false
        }
    }

    internal fun resetLiveUpdateActiveChecker() {
        liveUpdateActiveChecker = { context ->
            when {
                XiaomiFocusAdapter.isXiaomiRom() -> XiaomiFocusAdapter.canShowFocus(context)
                LiveUpdatePromotion.isPromotionSupported() -> LiveUpdatePromotion.canPromote(context)
                else -> false
            }
        }
    }

    /**
     * 是否开启常驻实时通知，以可注入 provider 的形式提供，
     * 避免直接耦合全局单例，同时保证读取失败时安全降级
     */
    internal var liveNotificationEnabledProvider: () -> Boolean = {
        liveProgressNotifier.readLiveNotificationEnabled()
    }

    internal fun resetLiveNotificationEnabledProvider() {
        liveNotificationEnabledProvider = {
            liveProgressNotifier.readLiveNotificationEnabled()
        }
    }

    private fun shouldPostStandaloneNotification(context: Context, state: TrackingSessionState): Boolean {
        if (!state.isActive) return true
        if (!liveNotificationEnabledProvider()) return true
        return !liveUpdateActiveChecker(context)
    }

    private fun areNotificationsEnabled(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(true)

    /**
     * 同时追多项时每项的到站 / 临近提醒各用一组 id：不同项的提醒不互相顶掉、也不互相取消。
     * [sessionId] 为空时还是原来的固定 id（旧调用 / 没有会话的场景）。
     */
    private fun alertNotificationId(base: Int, sessionId: String): Int =
        if (sessionId.isEmpty()) base else base * 4096 + (sessionId.hashCode() and 0xFFF)

    private fun cancelCounterpart(context: Context, notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification $notificationId", e)
        }
    }

    private fun createMainActivityPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setClassName(context.packageName, "com.trackr.MainActivity")
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun performWaveformVibration(context: Context, pattern: LongArray) {
        vibrateAsNotification(context, VibrationEffect.createWaveform(pattern, -1))
    }

    private fun performOneShotVibration(context: Context, durationMs: Long) {
        vibrateAsNotification(context, VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /**
     * 以“通知”用途振动：系统会按静音 / 勿扰 / 通知振动设置决定是否真正振动，
     * 不会在用户静音时强行振动
     */
    private fun vibrateAsNotification(context: Context, effect: VibrationEffect) {
        try {
            val vibrator = getVibrator(context)
            if (vibrator == null || !vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
            } else {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, audioAttributes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to perform notification vibration", e)
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    companion object {
        private const val TAG = "ArrivalAlertManager"

        const val CHANNEL_ID_ALERT = "bus_arrival_alert"
        private const val CHANNEL_NAME_ALERT = "公交到站与进站强提醒"

        const val NOTIFICATION_ID_APPROACHING = 10002
        const val NOTIFICATION_ID_ARRIVED = 10003
        const val NOTIFICATION_ID_TRANSFER = 10004
        // 追踪已停止 / 已中断用独立的 id，否则会顶掉同一时刻的换乘提醒
        const val NOTIFICATION_ID_EXPIRED = 10005
        const val NOTIFICATION_ID_INTERRUPTED = 10006
        const val NOTIFICATION_ID_MISSED = 10007

        private val APPROACHING_VIBRATION_PATTERN = longArrayOf(0, 280, 120, 280, 120, 400)
        private val TRANSFER_VIBRATION_PATTERN = longArrayOf(0, 300, 100, 300, 100, 300)
        private val MISSED_VIBRATION_PATTERN = longArrayOf(0, 350, 150, 350)
        private const val ARRIVED_VIBRATION_DURATION_MS = 800L
    }
}
