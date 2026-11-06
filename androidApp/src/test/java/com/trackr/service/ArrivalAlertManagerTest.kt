package com.trackr.service

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.TrackingSessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ArrivalAlertManagerTest {

    private lateinit var app: TrackrApp
    private lateinit var notificationManager: NotificationManager
    private var liveEnabled = true

    /** App 启动时 Koin 建好的实例（原来经 object 转发） */
    private val alertNotifier: ArrivalAlertNotifier get() = GlobalContext.get().get()
    private val liveNotifier: LiveProgressNotifier get() = GlobalContext.get().get()

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        notificationManager = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager
        liveEnabled = true
        alertNotifier.liveNotificationEnabledProvider = { liveEnabled }
        alertNotifier.liveUpdateActiveChecker = { true }
    }

    @After
    fun tearDown() {
        alertNotifier.resetLiveUpdateActiveChecker()
        alertNotifier.resetLiveNotificationEnabledProvider()
    }

    @Test
    fun alertNotifications_followLockScreenSetting() {
        val notifier = ArrivalAlertNotifier(liveNotifier) { false }.apply {
            liveNotificationEnabledProvider = { false }
        }

        fun assertPrivate(id: Int) {
            assertEquals(
                android.app.Notification.VISIBILITY_PRIVATE,
                shadowOf(notificationManager).getNotification(id)?.visibility
            )
        }

        notifier.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 2))
        assertPrivate(ArrivalAlertNotifier.NOTIFICATION_ID_APPROACHING)
        notifier.triggerArrivedAlert(
            app,
            activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true)
        )
        assertPrivate(ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED)
        notifier.triggerTransferAlert(app, "换乘站", "下一条线")
        assertPrivate(ArrivalAlertNotifier.NOTIFICATION_ID_TRANSFER)
        notifier.triggerTrackingExpired(app, activeTrackingState(stopsRemaining = 3))
        assertPrivate(ArrivalAlertNotifier.NOTIFICATION_ID_EXPIRED)
        notifier.triggerTrackingInterrupted(app, activeTrackingState(stopsRemaining = 3))
        assertPrivate(ArrivalAlertNotifier.NOTIFICATION_ID_INTERRUPTED)
    }

    @Test
    fun alertSettingReadFailure_defaultsToPublic() {
        val notifier = ArrivalAlertNotifier(liveNotifier) { error("unavailable") }.apply {
            liveNotificationEnabledProvider = { false }
        }

        notifier.triggerArrivedAlert(
            app,
            activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true)
        )

        assertEquals(
            android.app.Notification.VISIBILITY_PUBLIC,
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED)?.visibility
        )
    }

    @Test
    fun testTriggerApproachingAlert_skipsStandaloneNotificationWhenLiveUpdateIsActive() {
        val state = activeTrackingState(stopsRemaining = 2)

        alertNotifier.triggerApproachingAlert(app, state)

        assertNull(
            "实时活动已展示同一趟车时不应再发一条内容重复的强提醒",
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_APPROACHING)
        )
    }

    @Test
    fun testTriggerArrivedAlert_skipsStandaloneNotificationWhenLiveUpdateIsActive() {
        val state = activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true)

        alertNotifier.triggerArrivedAlert(app, state)

        assertNull(
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED)
        )
    }

    @Test
    fun testTriggerArrivedAlert_clearsStaleApproachingNotification() {
        alertNotifier.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 2))
        alertNotifier.triggerArrivedAlert(app, activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true))

        assertNull(shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_APPROACHING))
    }

    @Test
    fun testTriggerApproachingAlert_postsNotificationWhenLiveUpdateDisabled() {
        liveEnabled = false

        alertNotifier.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 2))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_APPROACHING)
        val text = posted?.extras?.getCharSequence("android.text")?.toString().orEmpty()
        assertEquals(true, text.contains("还有 2 站"))
    }

    @Test
    fun testTriggerApproachingAlert_postsNotificationWhenSystemDoesNotSupportPromotion() {
        alertNotifier.liveUpdateActiveChecker = { false }

        alertNotifier.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 2))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_APPROACHING)
        assertNotNull("当系统不支持实时活动胶囊时，应降级发送独立强提醒", posted)
        val text = posted?.extras?.getCharSequence("android.text")?.toString().orEmpty()
        assertEquals(true, text.contains("还有 2 站"))
    }

    @Test
    fun testStandaloneAlerts_useReminderCategory() {
        liveEnabled = false

        alertNotifier.triggerArrivedAlert(app, activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED)
        assertNotNull(posted)
        assertEquals(android.app.Notification.CATEGORY_REMINDER, posted?.category)
    }

    @Test
    fun testTransferAlert_cancelsArrivedAndApproachingOfSameStop() {
        liveEnabled = false
        alertNotifier.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 1))
        alertNotifier.triggerArrivedAlert(app, activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true))

        alertNotifier.triggerTransferAlert(app, "珞雄路雄楚大道", "M492")

        assertNull(shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED))
        assertNull(shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_APPROACHING))
        assertNotNull(shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_TRANSFER))
    }

    @Test
    fun testLiveNotificationFallback_isSameForBothHelpers() {
        alertNotifier.resetLiveNotificationEnabledProvider()
        liveNotifier.resetLiveNotificationEnabledProvider()
        assertEquals(
            liveNotifier.liveNotificationEnabledProvider(),
            alertNotifier.liveNotificationEnabledProvider()
        )
    }

    @Test
    fun testTrackingExpired_usesOwnNotificationIdAndDoesNotOverwriteTransferAlert() {
        alertNotifier.triggerTransferAlert(app, "珞雄路雄楚大道", "M492")
        assertNotNull("换乘提醒应先发出来", shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_TRANSFER))

        alertNotifier.triggerTrackingExpired(app, activeTrackingState(stopsRemaining = 3))

        // 两条通知各占各的 id，不能互相顶掉
        assertNotNull(
            "换乘提醒不该被「追踪已停止」盖掉",
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_TRANSFER)
        )
        assertNotNull(
            "「追踪已停止」应用自己的通知 id",
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_EXPIRED)
        )
    }

    @Test
    fun testTrackingInterrupted_usesOwnNotificationIdAndDoesNotOverwriteTransferAlert() {
        alertNotifier.triggerTransferAlert(app, "珞雄路雄楚大道", "M492")

        alertNotifier.triggerTrackingInterrupted(app, activeTrackingState(stopsRemaining = 3))

        assertNotNull(
            "换乘提醒不该被「追踪已中断」盖掉",
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_TRANSFER)
        )
        assertNotNull(
            "「追踪已中断」应用自己的通知 id",
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_INTERRUPTED)
        )
    }

    @Test
    fun testTrackingExpired_textFollowsMaxTrackingDurationConstant() {
        alertNotifier.triggerTrackingExpired(app, activeTrackingState(stopsRemaining = 3))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_EXPIRED)
        val text = posted?.extras?.getCharSequence("android.text")?.toString().orEmpty()
        val expectedHours = TrackingEngine.MAX_TRACKING_DURATION_MILLIS / 3_600_000L
        assertTrue("文案里的时长应与常量一致，实际：$text", text.contains("$expectedHours 小时"))
    }

    @Test
    fun testMissedBusAlert_clearsStaleArrivedNotificationAndSaysNextBus() {
        alertNotifier.triggerArrivedAlert(
            app,
            activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true),
            forceStandalone = true
        )
        assertNotNull(shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED))

        alertNotifier.triggerMissedBusAlert(app, activeTrackingState(stopsRemaining = 1).copy(etaSeconds = 480))

        assertNull(
            "车都开走了，别再挂着「车已到站」",
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED)
        )
        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_MISSED)
        assertNotNull(posted)
        val text = posted?.extras?.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue("要说下一班还有多久，实际：$text", text.contains("下一班"))
        assertEquals(Notification.CATEGORY_REMINDER, posted?.category)
    }

    /**
     * D3：强提醒的锁屏可见性跟「我的 → 锁屏显示」走。
     * 不设的话 `Notification.visibility` 默认 PRIVATE，用户把系统锁屏设成「隐藏敏感内容」时，
     * 到站提醒在锁屏上只剩「内容已隐藏」，震动照响但看不到是哪条线哪个站。
     */
    private fun alertsWithLockScreen(setting: () -> Boolean) =
        ArrivalAlertNotifier(liveNotifier, showOnLockScreenSetting = setting).apply {
            liveNotificationEnabledProvider = { true }
            liveUpdateActiveChecker = { false }
        }

    @Test
    fun testStandaloneAlert_lockScreenOn_isPublic() {
        alertsWithLockScreen { true }
            .triggerArrivedAlert(app, activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED)
        assertNotNull(posted)
        assertEquals(Notification.VISIBILITY_PUBLIC, posted!!.visibility)
    }

    @Test
    fun testStandaloneAlert_lockScreenOff_isPrivateWithoutPublicVersion() {
        alertsWithLockScreen { false }
            .triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 2))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_APPROACHING)
        assertNotNull(posted)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted!!.visibility)
        assertNull("关掉锁屏显示时不给公开版本，锁屏上不该出现站名", posted.publicVersion)
    }

    @Test
    fun testStandaloneAlert_lockScreenSettingThrows_fallsBackToPublic() {
        alertsWithLockScreen { error("读不到设置") }
            .triggerTrackingExpired(app, activeTrackingState(stopsRemaining = 3))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_EXPIRED)
        assertNotNull(posted)
        assertEquals(Notification.VISIBILITY_PUBLIC, posted!!.visibility)
    }

    private fun activeTrackingState(stopsRemaining: Int) = TrackingSessionState(
        isActive = true,
        lineName = "788",
        endStopName = "珞雄路公交场站",
        targetStopName = "珞雄路雄楚大道",
        targetOrder = 11,
        startTrackingOrder = 10,
        currentBusOrder = 11,
        currentBusProgress = 0.4f,
        stopsRemaining = stopsRemaining,
        etaSeconds = 420
    )
}
