package com.trackr.service

import android.app.Application
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.core.data.repository.MeishanPushRepository
import com.trackr.core.data.repository.MeishanPushTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.trackr.core.data.repository.MeishanPushUpdate
import com.trackr.core.data.repository.MeishanPushAvailability
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.domain.platform.LocationSource
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.UserFix
import com.trackr.core.model.WatchLine
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.testing.FakeLocationSource
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.data.repository.ITransitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * 追踪服务（Robolectric，SDK 34）：前台服务类型、定位开关、会话结束时停服务。
 * 实时数据用 [FakeTransitRepository]，定位用 [FakeLocationSource]，会话用 App 里真实的 [TrackingRepository]。
 */
@RunWith(RobolectricTestRunner::class)
class BusTrackingServiceTest {

    private lateinit var app: TrackrApp
    private lateinit var location: FakeLocationSource
    private lateinit var transit: FakeTransitRepository
    private lateinit var push: ServicePushFake
    private val trackingRepository: TrackingRepository get() = GlobalContext.get().get()
    private val notificationManager: NotificationManager
        get() = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        location = FakeLocationSource(precisePermission = true)
        transit = FakeTransitRepository()
        push = ServicePushFake()
        loadKoinModules(
            module {
                single<LocationSource> { location }
                single<ITransitRepository> { transit }
                single<MeishanPushRepository> { push }
            }
        )
    }

    private class ServicePushFake : MeishanPushRepository {
        data class Subscription(
            val base: BusLineDetail,
            val target: StateFlow<MeishanPushTarget>,
            val updates: MutableSharedFlow<MeishanPushUpdate> = MutableSharedFlow(extraBufferCapacity = 16)
        ) {
            val order: Int? get() = target.value.order
        }

        val subscriptions = mutableListOf<Subscription>()
        val released = mutableListOf<Subscription>()

        override fun stream(base: BusLineDetail, targetOrder: Int?, stationId: String?): Flow<MeishanPushUpdate> =
            stream(base, MutableStateFlow(MeishanPushTarget(targetOrder, stationId)))

        override fun stream(base: BusLineDetail, target: StateFlow<MeishanPushTarget>): Flow<MeishanPushUpdate> = flow {
            val subscription = Subscription(base, target)
            subscriptions.add(subscription)
            try { subscription.updates.collect { emit(it) } }
            finally { released.add(subscription) }
        }
    }

    private fun officialTarget() = singleTarget().copy(lineId = "ms:1_1_0", source = DataSourceType.MEISHAN)

    private fun officialDetail(id: String = "http", order: Int = 3) = lineDetail(
        "ms:1_1_0", listOf(LiveBus(id, id, order, segmentProgress = 0.4f))
    ).let { it.copy(line = it.line.copy(source = DataSourceType.MEISHAN)) }

    @Test
    fun officialTracking_pushUpdatesSharedState_disconnectFallsBack_stopReleases() {
        transit.realtime.lineDetailResult = Result.success(officialDetail())
        val session = requireNotNull(trackingRepository.start(officialTarget()))
        val controller = startService(session.sessionId)
        val service = controller.get()
        try {
            waitUntil("官方订阅建立") { push.subscriptions.size == 1 }
            assertTrue(service.isTrackingWakeLockHeld)
            val subscription = push.subscriptions.single()
            subscription.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, officialDetail("http", 5)))
            waitUntil("TCP写入首页共享仓库") { trackingRepository.legState.value.currentBusOrder == 5 }
            val calls = transit.realtime.lineDetailCalls.size
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(12))
            assertEquals(calls, transit.realtime.lineDetailCalls.size)
            subscription.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.DISCONNECTED))
            waitUntil("断开立即HTTP降级") { transit.realtime.lineDetailCalls.size > calls }
            subscription.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, officialDetail("http", 6)))
            waitUntil("TCP恢复") { trackingRepository.legState.value.currentBusOrder == 6 }
            trackingRepository.stop()
            waitUntil("订阅释放") { push.released.size == 1 }
            assertFalse(service.isTrackingWakeLockHeld)
        } finally { controller.destroy() }
    }

    @Test
    fun officialTracking_destroyReleasesSubscriptionAndWakeLock() {
        transit.realtime.lineDetailResult = Result.success(officialDetail())
        val session = requireNotNull(trackingRepository.start(officialTarget()))
        val controller = startService(session.sessionId)
        val service = controller.get()
        waitUntil("订阅建立") { push.subscriptions.size == 1 }
        controller.destroy()
        waitUntil("销毁释放订阅") { push.released.size == 1 }
        assertFalse(service.isTrackingWakeLockHeld)
        assertNull(trackingRepository.session.value)
    }

    @Test
    fun officialRoute_sameGenerationBoarding_rebindsEtaTarget() {
        val target = routeTarget().let { route -> route.copy(group = route.group.copy(legs = route.group.legs.map { leg ->
            leg.copy(candidateLines = leg.candidateLines.map { it.copy(lineId = "ms:1_1_0", source = DataSourceType.MEISHAN) })
        })) }
        transit.realtime.lineDetailResult = Result.success(officialDetail(order = 3))
        val session = requireNotNull(trackingRepository.start(target))
        val controller = startService(session.sessionId)
        try {
            waitUntil("上车站订阅") { push.subscriptions.size == 1 }
            assertEquals(3, push.subscriptions.first().order)
            trackingRepository.confirmBoarded()
            waitUntil("下车站切换到目标订阅") { push.subscriptions.first().order == 7 }
            assertEquals(1, push.subscriptions.size)
            assertEquals(session.generation, trackingRepository.session.value?.generation)
            assertTrue(push.released.isEmpty())
        } finally { controller.destroy() }
    }

    @Test
    fun thirdPartyTracking_doesNotSubscribeOrHoldWakeLock() {
        transit.realtime.lineDetailResult = Result.success(lineDetail("L59", listOf(LiveBus("http", "http", 3))))
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        val controller = startService(session.sessionId)
        try {
            waitUntil("HTTP完成") { trackingRepository.legState.value.currentBusId == "http" }
            assertTrue(push.subscriptions.isEmpty())
            assertFalse(controller.get().isTrackingWakeLockHeld)
        } finally { controller.destroy() }
    }

    @Test
    fun officialWatchTogether_subscribesEachLine_andPartialUpdateKeepsOtherLines() {
        val lines = listOf(WatchLine("ms:1_1_0", "1路", 7, source = DataSourceType.MEISHAN), WatchLine("ms:1_2_0", "2路", 7, source = DataSourceType.MEISHAN))
        for (line in lines) transit.realtime.lineDetailsMap[line.lineId] = officialDetail().let { it.copy(line = it.line.copy(lineId = line.lineId)) }
        val target = watchTarget().copy(lines = lines)
        val session = requireNotNull(trackingRepository.start(target))
        val controller = startService(session.sessionId)
        try {
            waitUntil("两线订阅建立") { push.subscriptions.size == 2 && trackingRepository.session.value?.watchedLines?.all { it.hasData } == true }
            val first = push.subscriptions.first()
            val detail = first.base.copy(buses = listOf(LiveBus("http", "http", 5, segmentProgress = 0.4f)))
            first.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, detail))
            waitUntil("部分线路更新") { trackingRepository.session.value?.watchedLines?.any { it.busOrder == 5 } == true }
            assertTrue(requireNotNull(trackingRepository.session.value).watchedLines.all { it.hasData })
            assertEquals(0, transit.realtime.stopDetailCallCount)
        } finally { controller.destroy() }
    }

    @Test
    fun officialWatchTogether_boardChoiceSurvivesSingleLinePushFrames() {
        val lines = listOf(WatchLine("ms:1_1_0", "1路", 7, source = DataSourceType.MEISHAN), WatchLine("ms:1_2_0", "2路", 7, source = DataSourceType.MEISHAN))
        for (line in lines) transit.realtime.lineDetailsMap[line.lineId] = officialDetail().let { it.copy(line = it.line.copy(lineId = line.lineId)) }
        val target = watchTarget().copy(lines = lines)
        val session = requireNotNull(trackingRepository.start(target))
        val controller = startService(session.sessionId)
        try {
            waitUntil("两线订阅建立") { push.subscriptions.size == 2 }
            val sub1 = push.subscriptions.first { it.base.line.lineId == "ms:1_1_0" }
            val sub2 = push.subscriptions.first { it.base.line.lineId == "ms:1_2_0" }
            // 两线的车都进缓存（真实路径：逐线帧 recordLineBuses）
            sub1.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, sub1.base.copy(buses = listOf(LiveBus("a", "a", 7, segmentProgress = 1f, isArrived = true)))))
            sub2.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, sub2.base.copy(buses = listOf(LiveBus("b", "b", 7, segmentProgress = 1f, isArrived = true)))))
            waitUntil("两线在站数据入缓存") {
                trackingRepository.session.value?.watchedLines?.all { it.busOrder == 7 } == true
            }
            // 用户按「我上车了」，此刻两条线的车都在站：两车歧义 → 待选
            trackingRepository.confirmBoarded()
            waitUntil("两车歧义待选") { trackingRepository.session.value?.boardChoice != null }
            assertEquals(setOf("a", "b"), requireNotNull(trackingRepository.session.value).boardChoice?.options?.map { it.busId }?.toSet())
            // 之后 L1 的单线帧继续到达（L2 在本轮缺席）：不得把歧义误判成单车自动上车
            sub1.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, sub1.base.copy(buses = listOf(LiveBus("a", "a", 7, segmentProgress = 1f, isArrived = true)))))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            val sessionNow = requireNotNull(trackingRepository.session.value)
            assertNotNull(sessionNow.boardChoice)
            assertEquals(LegPhase.WAITING_BOARD, sessionNow.state.legPhase)
        } finally { controller.destroy() }
    }

    @Test
    fun officialPush_autoStopAfterBoarding_endsTrackingWhenUserRidesAway() {
        // 初始 HTTP 快照就用 b1：推送帧全程跟同一辆车（否则换车判定会把帧当信号丢失）
        transit.realtime.lineDetailResult = Result.success(officialDetail(id = "b1", order = 3))
        val session = requireNotNull(trackingRepository.start(officialTarget()))
        val controller = startService(session.sessionId)
        val service = controller.get()
        try {
            waitUntil("订阅建立") { push.subscriptions.size == 1 }
            val sub = push.subscriptions.single()
            val stopLat = 25.0 + 7 * 0.001 // s7：上车站（targetOrder 7）
            fun fixAt(metersNorth: Double, ageMillis: Long) = UserFix(
                lat = stopLat + metersNorth / 111_000.0,
                lng = 119.0,
                accuracyMeters = 10f,
                // 服务用 AndroidMonotonicClock（SystemClock.elapsedRealtime）判定位新鲜度，测试对齐同一时基
                elapsedMillis = android.os.SystemClock.elapsedRealtime() - ageMillis
            )
            fun atStopBus() = sub.base.copy(buses = listOf(LiveBus("b1", "b1", 7, segmentProgress = 1f, isArrived = true, wgsLat = stopLat, wgsLng = 119.0)))
            fun approachingBus() = sub.base.copy(buses = listOf(LiveBus("b1", "b1", 7, segmentProgress = 0.6f, isArrived = false, wgsLat = stopLat - 30.0 / 111_000.0, wgsLng = 119.0)))
            // 车临近上车站（第一帧后引擎开定位、定位收集器开始收）
            sub.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, approachingBus()))
            waitUntil("推送帧写入状态") { trackingRepository.legState.value.currentBusId == "b1" }
            waitUntil("定位收集启动") { location.activeCollectors > 0 }
            // 车到站（带着定位发生到站转变）、人在站台 20 米：进入待确认（「上车后自动结束追踪」默认开）
            location.emit(fixAt(20.0, 0))
            sub.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, atStopBus()))
            waitUntil("进入待确认") { trackingRepository.legState.value.boardingCandidateBusId == "b1" }
            // 人跟着车走两次 → 引擎自动判上车并结束追踪（参数链 P1-1：推送入口必须透传开关）
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            location.emit(fixAt(150.0, 4_000))
            sub.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, sub.base.copy(buses = listOf(LiveBus("b1", "b1", 8, segmentProgress = 0.2f, wgsLat = stopLat + 150.0 / 111_000.0, wgsLng = 119.0)))))
            waitUntil("跟车确认") { trackingRepository.legState.value.boardingConfirmStreak >= 1 }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            location.emit(fixAt(320.0, 4_000))
            sub.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, sub.base.copy(buses = listOf(LiveBus("b1", "b1", 8, segmentProgress = 0.4f, wgsLat = stopLat + 320.0 / 111_000.0, wgsLng = 119.0)))))
            waitUntil("自动结束追踪") { trackingRepository.session.value == null }
        } finally { controller.destroy() }
    }

    @Test
    fun officialTracking_lateFallbackHttpDoesNotOverwriteRecoveredTcp() {
        transit.realtime.lineDetailResult = Result.success(officialDetail())
        val session = requireNotNull(trackingRepository.start(officialTarget()))
        val controller = startService(session.sessionId)
        val releaseHttp = CompletableDeferred<Unit>()
        try {
            waitUntil("订阅建立") { push.subscriptions.size == 1 }
            val subscription = push.subscriptions.single()
            subscription.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, officialDetail("http", 5)))
            waitUntil("TCP首帧") { trackingRepository.legState.value.currentBusOrder == 5 }
            var fallbackStarted = false
            transit.realtime.lineDetailHook = { fallbackStarted = true; releaseHttp.await() }
            subscription.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.DISCONNECTED))
            waitUntil("降级HTTP挂起") { fallbackStarted }
            subscription.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, officialDetail("http", 6)))
            waitUntil("TCP先恢复") { trackingRepository.legState.value.currentBusOrder == 6 }
            releaseHttp.complete(Unit)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
            assertEquals(6, trackingRepository.legState.value.currentBusOrder)
        } finally { releaseHttp.complete(Unit); controller.destroy() }
    }

    @Test
    fun officialWatchTogether_partialDisconnectRefreshesOnlyOfflineLine() {
        val lines = listOf(WatchLine("ms:1_1_0", "1路", 7, source = DataSourceType.MEISHAN), WatchLine("ms:1_2_0", "2路", 7, source = DataSourceType.MEISHAN))
        for (line in lines) transit.realtime.lineDetailsMap[line.lineId] = officialDetail().let { it.copy(line = it.line.copy(lineId = line.lineId)) }
        val session = requireNotNull(trackingRepository.start(watchTarget().copy(lines = lines)))
        val controller = startService(session.sessionId)
        try {
            waitUntil("双线订阅") { push.subscriptions.size == 2 }
            push.subscriptions.forEach { it.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, it.base.copy(buses = listOf(LiveBus("http", "http", 5, segmentProgress = 0.4f))))) }
            waitUntil("双线在线") { trackingRepository.session.value?.watchedLines?.all { it.busOrder == 5 } == true }
            val failed = push.subscriptions.first()
            val healthy = push.subscriptions.last()
            val before = transit.realtime.lineDetailCalls.size
            failed.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.DISCONNECTED))
            waitUntil("离线线路HTTP补刷") { transit.realtime.lineDetailCalls.size > before }
            assertEquals(listOf(failed.base.line.lineId), transit.realtime.lineDetailCalls.drop(before))
            assertFalse(push.released.contains(healthy))
            assertTrue(requireNotNull(trackingRepository.session.value).watchedLines.all { it.hasData })
        } finally { controller.destroy() }
    }

    @Test
    fun officialSessions_sameLineDifferentStops_stopOnlyReleasesSelectedSession() {
        transit.realtime.lineDetailResult = Result.success(officialDetail())
        val first = requireNotNull(trackingRepository.start(officialTarget()))
        val controller = startService(first.sessionId)
        try {
            waitUntil("首项订阅") { push.subscriptions.size == 1 }
            val firstPush = push.subscriptions.single()
            val second = requireNotNull(trackingRepository.start(officialTarget().copy(
                targetStopId = "s8", targetStopName = "站8", targetOrder = 8
            )))
            waitUntil("同线不同站订阅") { push.subscriptions.size == 2 }
            val secondPush = push.subscriptions.last()
            assertEquals(7, firstPush.order)
            assertEquals(8, secondPush.order)
            assertTrue(trackingRepository.pushOnlineLines.value.isEmpty())
            listOf(firstPush, secondPush).forEach { subscription ->
                subscription.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, officialDetail(order = 5)))
            }
            waitUntil("两项 TCP 在线") { trackingRepository.pushOnlineLines.value.keys == setOf(first.sessionId, second.sessionId) }
            trackingRepository.stop(first.sessionId)
            waitUntil("只释放首项订阅") { push.released.contains(firstPush) }
            assertFalse(push.released.contains(secondPush))
            assertTrue(controller.get().isTrackingWakeLockHeld)
            assertEquals(setOf(second.sessionId), trackingRepository.pushOnlineLines.value.keys)
            secondPush.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, officialDetail(order = 6)))
            waitUntil("剩余项继续收 TCP") {
                trackingRepository.sessions.value.single().state.currentBusOrder == 6
            }
            trackingRepository.stop(second.sessionId)
            waitUntil("末项释放订阅和锁") {
                push.released.contains(secondPush) && !controller.get().isTrackingWakeLockHeld
            }
            assertTrue(trackingRepository.pushOnlineLines.value.isEmpty())
        } finally { controller.destroy() }
    }

    @Test
    fun officialWatchTogether_removeLine_releasesOnlyRemovedSubscription() {
        val lines = listOf(
            WatchLine("ms:1_1_0", "1路", 7, source = DataSourceType.MEISHAN),
            WatchLine("ms:1_2_0", "2路", 7, source = DataSourceType.MEISHAN)
        )
        lines.forEach { line ->
            transit.realtime.lineDetailsMap[line.lineId] = officialDetail().let {
                it.copy(line = it.line.copy(lineId = line.lineId))
            }
        }
        val session = requireNotNull(trackingRepository.start(watchTarget().copy(lines = lines)))
        val controller = startService(session.sessionId)
        try {
            waitUntil("两条线路订阅") { push.subscriptions.size == 2 }
            val removed = push.subscriptions.first()
            val kept = push.subscriptions.last()
            push.subscriptions.forEach { subscription ->
                subscription.updates.tryEmit(MeishanPushUpdate(MeishanPushAvailability.ONLINE, subscription.base))
            }
            waitUntil("两条线路在线") { trackingRepository.pushOnlineLines.value[session.sessionId]?.size == 2 }
            trackingRepository.removeWatchLine(session.sessionId, removed.base.line.lineId)
            waitUntil("删除线路释放订阅") { push.released.contains(removed) }
            assertFalse(push.released.contains(kept))
            assertEquals(setOf(kept.base.line.lineId), trackingRepository.pushOnlineLines.value[session.sessionId])
            assertTrue(controller.get().isTrackingWakeLockHeld)
        } finally { controller.destroy() }
    }

    // ---------------------------------------------------------------- 样例数据

    private fun lineDetail(lineId: String, buses: List<LiveBus>) = BusLineDetail(
        line = BusLineDirection(
            lineId = lineId,
            name = "59路",
            lineNo = "59",
            direction = 0,
            startStop = "始发",
            endStop = "终点",
            firstTime = "06:00",
            lastTime = "22:00"
        ),
        stops = (1..10).map { BusStop(stopId = "s$it", name = "站$it", order = it, wgsLat = 25.0 + it * 0.001, wgsLng = 119.0) },
        buses = buses
    )

    private fun routeTarget() = TrackingTarget.Route(
        group = TransitGroup(
            id = "g1",
            name = "上班",
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "站3",
                    toStopName = "站7",
                    toStopId = "s7",
                    candidateLines = listOf(
                        CandidateLine(cityId = "034", lineId = "L59", lineName = "59路", fromStopOrder = 3, toStopOrder = 7)
                    )
                )
            )
        )
    )

    private fun singleTarget() = TrackingTarget.SingleLine(
        cityId = "034",
        lineId = "L59",
        lineName = "59路",
        targetStopId = "s7",
        targetStopName = "站7",
        targetOrder = 7,
        totalStops = 10
    )

    private fun startService(sessionId: String): ServiceController<BusTrackingService> =
        Robolectric.buildService(BusTrackingService::class.java, BusTrackingService.startIntent(app, sessionId))
            .create()
            .startCommand(0, 1)

    /** 主线程消息跑完，等 IO 线程的网络回调回到主线程 */
    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > deadline) fail("等待超时：$message")
            Thread.sleep(20)
        }
    }

    // ---------------------------------------------------------------- 用例

    @Test
    fun route_withPrecisePermission_usesLocationTypeAndTurnsLocationOnNearBoardStop() {
        // 车在第 2 站、离上车站 A（第 3 站）1 站：等车近站档位要开定位
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(routeTarget()))
        val service = startService(session.sessionId).get()

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.lastForegroundServiceType
        )
        assertNotNull(shadowOf(service).lastForegroundNotification)

        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }
        assertEquals(LegPhase.WAITING_BOARD, trackingRepository.legState.value.legPhase)
        assertEquals(LocationCadence.Tier.WAITING_NEAR, service.currentLocationTier)
        assertEquals(listOf(LocationCadence.Tier.WAITING_NEAR), location.requestedTiers)
        assertEquals(1, location.activeCollectors)
    }

    @Test
    fun route_withoutPrecisePermission_fallsBackToSpecialUseAndNoLocation() {
        location.precisePermission = false
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(routeTarget()))
        val service = startService(session.sessionId).get()

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.lastForegroundServiceType)
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }
        assertEquals(LocationCadence.Tier.OFF, service.currentLocationTier)
        assertTrue(location.requestedTiers.isEmpty())
        assertEquals(0, location.activeCollectors)
    }

    @Test
    fun singleLine_takesLocationType_withAutoStopAfterBoarding() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        // 默认开着「上车后自动结束追踪」：单线路也要定位（判人有没有跟着车走），前台就得带 location 类型
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        val service = startService(session.sessionId).get()

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.lastForegroundServiceType
        )
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }
        assertEquals(LegPhase.SINGLE, trackingRepository.legState.value.legPhase)
        // 车还有 1 站：这时候开始取定位，车到站后才判得出人有没有跟着走
        assertEquals(LocationCadence.Tier.WAITING_NEAR, service.currentLocationTier)
        assertEquals(listOf(LocationCadence.Tier.WAITING_NEAR), location.requestedTiers)
    }

    @Test
    fun singleLine_autoStopOff_neverAsksForLocationType() {
        transit.settings.setAutoStopAfterBoarding(false)
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        val service = startService(session.sessionId).get()

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.lastForegroundServiceType)
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }
        assertEquals(LegPhase.SINGLE, trackingRepository.legState.value.legPhase)
        assertEquals(LocationCadence.Tier.OFF, service.currentLocationTier)
        assertTrue(location.requestedTiers.isEmpty())
    }

    @Test
    fun stop_turnsLocationOffAndStopsService() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(routeTarget()))
        val service = startService(session.sessionId).get()
        waitUntil("定位打开") { location.activeCollectors == 1 }

        // 通知「结束」按钮 / 首页结束追踪都是清掉仓库里的会话
        trackingRepository.stop()
        waitUntil("服务停下") { shadowOf(service).isStoppedBySelf }

        assertEquals(LocationCadence.Tier.OFF, service.currentLocationTier)
        assertEquals(0, location.activeCollectors)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertNull(trackingRepository.session.value)
    }

    /**
     * #28：结束追踪以后，这一项留下的到站提醒也要跟着消失，不能继续挂在通知栏和锁屏上等用户手动点掉。
     */
    @Test
    fun stop_cancelsArrivalAlertOfThatSession() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(routeTarget()))
        val service = startService(session.sessionId).get()
        waitUntil("定位打开") { location.activeCollectors == 1 }

        // 到站那一刻服务会发这条；这里直接替它发出来，然后结束追踪
        val alerts = GlobalContext.get().get<ArrivalAlertNotifier>()
        alerts.triggerArrivedAlert(
            app,
            trackingRepository.legState.value,
            forceStandalone = true,
            sessionId = session.sessionId
        )
        val arrivedId = ArrivalAlertNotifier.alertNotificationId(
            ArrivalAlertNotifier.NOTIFICATION_ID_ARRIVED,
            session.sessionId
        )
        assertNotNull("到站提醒应先发出来", shadowOf(notificationManager).getNotification(arrivedId))

        trackingRepository.stop()
        waitUntil("服务停下") { shadowOf(service).isStoppedBySelf }

        assertNull(
            "追踪结束后提醒不该留着",
            shadowOf(notificationManager).getNotification(arrivedId)
        )
    }

    @Test
    fun staleStartRequest_entersForegroundThenStops() {
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        // 启动请求还没到，用户又点了结束
        trackingRepository.stop()
        val service = startService(session.sessionId).get()

        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    // ---------------------------------------------------------------- 一起等（第 15 步）

    private fun watchTarget() = TrackingTarget.WatchTogether(
        cityId = "034",
        stopId = "s5",
        stopName = "站5",
        lines = listOf(
            WatchLine(lineId = "L59", lineName = "59路", stopOrder = 5),
            WatchLine(lineId = "L60", lineName = "60路", stopOrder = 5),
            WatchLine(lineId = "L61", lineName = "61路", stopOrder = 5)
        )
    )

    private fun stopStatus(lineId: String, buses: List<LiveBus>) = NearbyStopLineStatus(
        line = BusLineDirection(lineId = lineId, name = lineId, startStop = "始发", endStop = "终点"),
        targetOrder = 5,
        targetStopId = "s5",
        targetStopName = "站5",
        approachingBuses = buses
    )

    private fun stopWithLines() = NearbyStop(
        stopId = "s5",
        name = "站5",
        wgsLat = 25.005,
        wgsLng = 119.0,
        lines = listOf(
            stopStatus("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 2, etaSeconds = 600))),
            stopStatus("L60", listOf(LiveBus(busId = "b2", plateOrFleetNo = "闽B2", order = 4, etaSeconds = 120))),
            stopStatus("L61", listOf(LiveBus(busId = "b3", plateOrFleetNo = "闽B3", order = 3, etaSeconds = 300)))
        )
    )

    @Test
    fun watchTogether_onePollIsOneStopRequest_fastestShown_usesLocationType() {
        transit.realtime.stopDetailResult = Result.success(listOf(stopWithLines()))
        val session = requireNotNull(trackingRepository.start(watchTarget()))
        val service = startService(session.sessionId).get()

        // Q17：一起等也要 location 类型
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.lastForegroundServiceType
        )
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b2" }

        val polled = requireNotNull(trackingRepository.session.value)
        assertEquals("L60", polled.leg.lineId)
        assertEquals(listOf("L60", "L61", "L59"), polled.watchedLines.map { it.line.lineId })
        // 3 条线只发 1 次站点请求，不请求线路接口
        assertEquals(1, transit.realtime.stopDetailCallCount)
        assertTrue(transit.realtime.lineDetailCalls.isEmpty())
        // 最先到的离站 1 站：开等车近站档定位
        assertEquals(LocationCadence.Tier.WAITING_NEAR, service.currentLocationTier)
    }

    @Test
    fun watchTogether_eachLineHasItsOwnNotification_andStopRemovesOnlyThatLine() {
        transit.realtime.stopDetailResult = Result.success(listOf(stopWithLines()))
        val session = requireNotNull(trackingRepository.start(watchTarget()))
        startService(session.sessionId).get()
        waitUntil("三条线都写回") { trackingRepository.session.value?.watchedLines?.size == 3 }

        // 一起等不是合成一条：每条线一条常驻通知，各自有进度
        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        val ids = listOf("L59", "L60", "L61").map {
            notifier.notificationIdOf(LiveProgressNotifier.watchLineKey(session.sessionId, it))
        }
        ids.forEach { id ->
            assertNotNull("每条线一条通知", shadowOf(notificationManager).getNotification(id))
        }
        assertNull(
            "不能又合成一条总通知",
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(session.sessionId))
        )
        // 每条通知写的是自己那条线
        assertEquals(
            "59路",
            requireNotNull(shadowOf(notificationManager).getNotification(ids[0]))
                .extras.getCharSequence("android.title").toString().substringBefore(" "),
        )

        // 点 59 路那条的「结束」：只去掉这条线，另外两条的通知还在
        val n59 = requireNotNull(shadowOf(notificationManager).getNotification(ids[0]))
        val stopButton = n59.actions.first { it.title.toString().contains("结束") }
        app.sendBroadcast(shadowOf(stopButton.actionIntent).savedIntent)

        waitUntil("59 路被去掉") {
            trackingRepository.session.value?.watchedLines?.none { it.line.lineId == "L59" } == true
        }
        waitUntil("59 路通知撤掉") { shadowOf(notificationManager).getNotification(ids[0]) == null }
        assertNotNull(shadowOf(notificationManager).getNotification(ids[1]))
        assertNotNull(shadowOf(notificationManager).getNotification(ids[2]))
        assertEquals(listOf("L60", "L61"), trackingRepository.session.value?.watchedLines?.map { it.line.lineId })
    }

    @Test
    fun watchTogether_afterBoarding_pollsOnlyThatLine() {
        transit.realtime.stopDetailResult = Result.success(listOf(stopWithLines()))
        transit.realtime.lineDetailsMap["L60"] = lineDetail(
            "L60",
            listOf(LiveBus(busId = "b2", plateOrFleetNo = "闽B2", order = 6, segmentProgress = 0.2f, wgsLat = 25.006, wgsLng = 119.0))
        )
        val session = requireNotNull(trackingRepository.start(watchTarget()))
        startService(session.sessionId).get()
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b2" }

        // 通知「我上车了」：没有车在站，按排第一的 60 路上车，先不锁车；服务马上只请求 60 路
        trackingRepository.confirmBoarded()
        waitUntil("上车后按 60 路轮询并补锁") { trackingRepository.legState.value.lockedBusId == "b2" }

        val riding = trackingRepository.legState.value
        assertEquals(LegPhase.ON_BOARD, riding.legPhase)
        assertEquals("L60", riding.lineId)
        assertEquals(10, riding.targetOrder) // 没有下车站：目标是终点站
        assertEquals(listOf("L60"), transit.realtime.lineDetailCalls)
        assertEquals(1, transit.realtime.stopDetailCallCount)
    }

    // ---------------------------------------------------------------- 起步失败（进不了前台）

    @Test
    fun startFailure_normalSession_endsItAndNotifiesUser() {
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        val service = Robolectric.buildService(BusTrackingService::class.java).create().get()

        // start() 已经返回 true，服务却起不来：没人轮询，会话留着只会让界面一直显示「追踪中」
        service.endStartFailure(session, SecurityException("fgs denied"))

        assertNull("正常会话起不了服务就该结束，不能留在仓库里", trackingRepository.session.value)
        assertFalse(trackingRepository.legState.value.isActive)
        assertNotNull(
            "要告诉用户追踪没起来",
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_INTERRUPTED)
        )
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun startFailure_interruptedSession_keepsItForNextOpen() {
        // 进程重建读回的中断会话：用户还没决定要不要继续，不能替他结束。
        // 仓库里的会话仍是活动状态，所以这里断言的是「没被 stop 掉」。
        val stored = requireNotNull(trackingRepository.start(singleTarget()))
        val service = Robolectric.buildService(BusTrackingService::class.java).create().get()

        service.endStartFailure(stored.copy(interrupted = true), SecurityException("fgs denied"))

        assertEquals(
            "中断会话要留给下次打开 App 再接续，不能在这里结束",
            stored.sessionId,
            trackingRepository.session.value?.sessionId
        )
        assertNotNull(
            shadowOf(notificationManager).getNotification(ArrivalAlertNotifier.NOTIFICATION_ID_INTERRUPTED)
        )
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun destroy_endsTrackedSession() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val session = requireNotNull(trackingRepository.start(singleTarget()))
        val controller = startService(session.sessionId)
        waitUntil("第一次轮询写回仓库") { trackingRepository.legState.value.currentBusId == "b1" }

        controller.destroy()

        assertNull(trackingRepository.session.value)
        assertFalse(trackingRepository.legState.value.isActive)
    }

    // ---------------------------------------------------------------- 同时追多项（第 36 步）

    @Test
    fun twoLines_arePolledTogether_eachHasItsOwnNotification() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val first = requireNotNull(trackingRepository.start(singleTarget()))
        val service = startService(first.sessionId).get()
        waitUntil("第一项写回") { trackingRepository.sessionById(first.sessionId)?.state?.currentBusId == "b1" }

        // 同一个站再追另一条线：不覆盖前一项，两路各poll各的
        val second = requireNotNull(
            trackingRepository.start(singleTarget().copy(lineId = "L71", lineName = "71路"))
        )
        waitUntil("第二项写回") { trackingRepository.sessionById(second.sessionId)?.state?.currentBusId == "b1" }

        assertEquals(listOf(first.sessionId, second.sessionId), trackingRepository.sessions.value.map { it.sessionId })
        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        assertNotNull(
            "每项一条常驻通知",
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId))
        )
        assertNotNull(
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(second.sessionId))
        )

        // 应用里结束第一项：只撤它那条通知，第二项还在追、通知也在，服务不退出
        trackingRepository.stop(first.sessionId)
        waitUntil("第一项通知撤掉") {
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId)) == null
        }
        assertEquals(listOf(second.sessionId), trackingRepository.sessions.value.map { it.sessionId })
        assertNotNull(
            "第二项的通知不能跟着消失",
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(second.sessionId))
        )
        assertFalse("还有一项在追，服务不能停", shadowOf(service).isStoppedBySelf)

        // 全结束才退出前台，通知一条不剩
        trackingRepository.stop()
        waitUntil("服务停下") { shadowOf(service).isStoppedBySelf }
        assertTrue(shadowOf(service).isForegroundStopped)
        waitUntil("两条通知都撤掉") {
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId)) == null &&
                shadowOf(notificationManager).getNotification(notifier.notificationIdOf(second.sessionId)) == null
        }
    }

    @Test
    fun stopFromNotification_onlyEndsThatLine() {
        transit.realtime.lineDetailResult = Result.success(
            lineDetail("L59", listOf(LiveBus(busId = "b1", plateOrFleetNo = "闽B1", order = 6, segmentProgress = 0.5f)))
        )
        val first = requireNotNull(trackingRepository.start(singleTarget()))
        startService(first.sessionId)
        waitUntil("第一项写回") { trackingRepository.sessionById(first.sessionId)?.state?.currentBusId == "b1" }
        val second = requireNotNull(
            trackingRepository.start(singleTarget().copy(lineId = "L71", lineName = "71路"))
        )
        waitUntil("第二项写回") { trackingRepository.sessionById(second.sessionId)?.state?.currentBusId == "b1" }

        val notifier: LiveProgressNotifier = GlobalContext.get().get()
        val notification = requireNotNull(
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId))
        )
        val stopButton = notification.actions.first { it.title.toString().contains("结束") }
        app.sendBroadcast(shadowOf(stopButton.actionIntent).savedIntent)

        // 只结束点的那一项（以前会一起结束，剩下那条点「结束」又什么都做不了，通知也关不掉）
        waitUntil("只结束第一项") {
            trackingRepository.sessions.value.map { it.sessionId } == listOf(second.sessionId)
        }
        waitUntil("第一项通知撤掉") {
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(first.sessionId)) == null
        }
        assertNotNull(
            shadowOf(notificationManager).getNotification(notifier.notificationIdOf(second.sessionId))
        )
    }

    @Test
    fun stopFromLegacyNotification_withoutSessionId_onlyEndsPrimary() {
        val first = requireNotNull(trackingRepository.start(singleTarget()))
        startService(first.sessionId)
        val second = requireNotNull(
            trackingRepository.start(singleTarget().copy(lineId = "L71", lineName = "71路"))
        )
        waitUntil("两项都在") { trackingRepository.sessions.value.size == 2 }

        // 升级前发出去的通知按钮不带 sessionId：只作用在主会话，不能把全部追踪停掉
        app.sendBroadcast(
            android.content.Intent(app, TrackingActionReceiver::class.java)
                .setAction(TrackingActionReceiver.ACTION_STOP_TRACKING)
        )
        waitUntil("只结束主会话") {
            trackingRepository.sessions.value.map { it.sessionId } == listOf(second.sessionId)
        }
    }
}
