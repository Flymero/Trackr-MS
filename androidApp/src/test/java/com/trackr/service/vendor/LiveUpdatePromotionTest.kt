package com.trackr.service.vendor

import android.app.Application
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.trackr.R
import com.trackr.core.model.TrackingSessionState
import com.trackr.service.LiveProgressNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveUpdatePromotionTest {

    /** App 启动时 Koin 建好的实例（原来经 object 转发） */
    private val liveNotifier: LiveProgressNotifier get() = GlobalContext.get().get()

    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testIsColorOs_recognizesOppoFamilyBrands() {
        assertTrue(OppoFluidCloudAdapter.isColorOs("OnePlus", "OnePlus"))
        assertTrue(OppoFluidCloudAdapter.isColorOs("OPPO", "OPPO"))
        assertTrue(OppoFluidCloudAdapter.isColorOs("realme", "realme"))
        assertTrue(OppoFluidCloudAdapter.isColorOs("oplus", "OPPO"))
        assertFalse(OppoFluidCloudAdapter.isColorOs("Xiaomi", "Xiaomi"))
        assertFalse(OppoFluidCloudAdapter.isColorOs("google", "Google"))
    }

    @Test
    fun testCanPromote_isTrueWhenPlatformHasNoPromotionSupport() {
        assertTrue(LiveUpdatePromotion.canPromote(context))
        assertTrue(OppoFluidCloudAdapter.canShowFluidCloud(context))
    }

    @Test
    fun testBuildNotification_usesDedicatedMonochromeSmallIcon() {
        val state = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7,
            currentBusOrder = 9,
            currentBusProgress = 0.5f,
            stopsRemaining = 1
        )

        val notification = liveNotifier.buildNotification(context, state)
        assertEquals(R.drawable.ic_stat_trackr, notification.smallIcon.resId)
    }

    @Test
    fun testBuildNotification_requestsPromotedOngoingWithShortCriticalText() {
        val state = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7,
            currentBusOrder = 9,
            currentBusProgress = 0.5f,
            stopsRemaining = 1
        )

        val notification = liveNotifier.buildNotification(context, state)
        assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))

        val shortCriticalText = NotificationCompat.getShortCriticalText(notification)
        assertNotNull(shortCriticalText)
        assertTrue(shortCriticalText!!.length <= 7)
        assertEquals("剩1站", shortCriticalText)
    }

    @Test
    fun testBuildNotification_isNotColorizedSoPromotionStaysEligible() {
        val state = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7,
            currentBusOrder = 9,
            currentBusProgress = 0.5f,
            stopsRemaining = 1
        )

        val notification = liveNotifier.buildNotification(context, state)
        // Android 16 的 hasPromotableCharacteristics() 要求「不能染色」，setColorized(true) 会直接失去上岛资格
        assertFalse(notification.extras.getBoolean(NotificationCompat.EXTRA_COLORIZED))
    }

    @Test
    fun testBuildNotification_progressStyleIsSetAsCompatTemplate() {
        val state = TrackingSessionState(
            isActive = true,
            lineName = "49路",
            targetStopName = "上海体育馆",
            targetOrder = 10,
            startTrackingOrder = 7,
            currentBusOrder = 9,
            currentBusProgress = 0.5f,
            stopsRemaining = 1
        )

        val notification = liveNotifier.buildNotification(context, state)
        val template = notification.extras.getString(NotificationCompat.EXTRA_COMPAT_TEMPLATE)
        assertEquals(NotificationCompat.ProgressStyle::class.java.name, template)
    }
}
