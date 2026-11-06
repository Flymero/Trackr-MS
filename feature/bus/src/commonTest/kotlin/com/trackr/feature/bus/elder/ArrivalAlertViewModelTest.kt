package com.trackr.feature.bus.elder

import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.model.TrackingLegParams
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.WatchLine
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.FakeTrackingController
import com.trackr.core.testing.FakeTransitCodeLauncher
import com.trackr.core.testing.MainDispatcherHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 长辈版到站整屏提醒：什么时候弹、关掉后同一辆车不再弹、「我上车了」结束追踪、乘车码 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArrivalAlertViewModelTest {

    private val main = MainDispatcherHolder(UnconfinedTestDispatcher())
    private val sessionFlow = MutableStateFlow<List<TrackingSession>>(emptyList())
    private lateinit var controller: FakeTrackingController
    private lateinit var launcher: FakeTransitCodeLauncher
    private lateinit var settings: FakeSettingsRepository

    private val singleTarget = TrackingTarget.SingleLine(
        cityId = "028", lineId = "l325", lineName = "325", endStopName = "科技园", targetStopName = "科技园", targetOrder = 5
    )

    private fun arrivedState(busId: String? = "bus1", arrived: Boolean = true, active: Boolean = true) = TrackingSessionState(
        isActive = active,
        lineId = "l325",
        lineName = "325",
        endStopName = "科技园",
        targetStopName = "科技园",
        targetOrder = 5,
        currentBusId = busId,
        stopsRemaining = if (arrived) 0 else 2,
        isBusArrivedAtStop = arrived,
        hasTriggeredArrivedAlert = arrived
    )

    private fun session(
        state: TrackingSessionState = arrivedState(),
        target: TrackingTarget = singleTarget,
        interrupted: Boolean = false,
        id: String = "s1"
    ) = TrackingSession(
        sessionId = id,
        generation = 1,
        target = target,
        leg = TrackingLegParams(cityId = "028", lineId = "l325", lineName = "325", targetOrder = 5),
        state = state,
        interrupted = interrupted
    )

    @BeforeTest
    fun setup() {
        main.install()
        val real = DefaultTrackingRepository(InMemoryTrackingSessionStore(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        val repository = object : TrackingRepository by real {
            override val sessions: StateFlow<List<TrackingSession>> = sessionFlow
        }
        controller = FakeTrackingController()
        launcher = FakeTransitCodeLauncher()
        settings = FakeSettingsRepository()
        viewModel = ArrivalAlertViewModel(repository, controller, settings, launcher)
    }

    private lateinit var viewModel: ArrivalAlertViewModel

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    // ---- 什么时候弹（纯函数）----

    @Test
    fun alertOf_singleLineArrived() {
        assertEquals(
            ArrivalAlertUi(key = "s1:bus1", sessionId = "s1", lineName = "325", endStop = "科技园", stopName = "科技园"),
            arrivalAlertOf(session())
        )
    }

    @Test
    fun alertOf_notYetArrived_orNoBus_orNotActive_none() {
        assertNull(arrivalAlertOf(null))
        assertNull(arrivalAlertOf(session(arrivedState(arrived = false))))
        assertNull(arrivalAlertOf(session(arrivedState(busId = null))))
        assertNull(arrivalAlertOf(session(arrivedState(active = false))))
        // 进程被杀后读回、服务还没接上
        assertNull(arrivalAlertOf(session(interrupted = true)))
    }

    @Test
    fun alertOf_watchTogether_none() {
        val watch = TrackingTarget.WatchTogether(
            cityId = "028", stopId = "st1", stopName = "科技园", lines = listOf(WatchLine(lineId = "l325", lineName = "325", stopOrder = 5))
        )
        assertNull(arrivalAlertOf(session(target = watch)))
    }

    // ---- ViewModel ----

    @Test
    fun showsWhenArrived_goesWhenBusLeaves() = runTest(main.dispatcher) {
        assertNull(viewModel.alert.value)
        sessionFlow.value = listOf(session(arrivedState(arrived = false)))
        assertNull(viewModel.alert.value)
        sessionFlow.value = listOf(session())
        assertEquals("s1:bus1", viewModel.alert.value?.key)
        // 车开走了，下一辆还没到
        sessionFlow.value = listOf(session(arrivedState(busId = "bus2", arrived = false)))
        assertNull(viewModel.alert.value)
    }

    @Test
    fun dismiss_sameBusStaysHidden_nextBusShowsAgain() = runTest(main.dispatcher) {
        sessionFlow.value = listOf(session())
        viewModel.onEvent(ArrivalAlertEvent.Dismiss)
        assertNull(viewModel.alert.value)
        // 同一辆车下一轮轮询（状态没变）也不再弹
        sessionFlow.value = listOf(session(arrivedState().copy(etaSeconds = 10)))
        assertNull(viewModel.alert.value)
        assertEquals(0, controller.stopCount)

        sessionFlow.value = listOf(session(arrivedState(busId = "bus2")))
        assertEquals("s1:bus2", viewModel.alert.value?.key)
    }

    @Test
    fun boarded_stopsTracking() = runTest(main.dispatcher) {
        sessionFlow.value = listOf(session())
        viewModel.onEvent(ArrivalAlertEvent.Boarded)
        assertEquals(1, controller.stopCount)
        assertNull(viewModel.alert.value)
    }

    @Test
    fun transitCode_defaultOpensDirectly_askAlwaysShowsChooser() = runTest(main.dispatcher) {
        // 默认「每次询问」：弹选择
        viewModel.onEvent(ArrivalAlertEvent.OpenTransitCode)
        assertTrue(viewModel.transitCodeSheetOpen.value)
        assertTrue(launcher.opened.isEmpty())

        viewModel.onEvent(ArrivalAlertEvent.PickTransitCode(TransitCodeProvider.ALIPAY, saveAsDefault = true))
        testScheduler.runCurrent()
        assertEquals(false, viewModel.transitCodeSheetOpen.value)
        assertEquals(listOf(TransitCodeProvider.ALIPAY), launcher.opened)
        assertEquals(TransitCodeProvider.ALIPAY, settings.preferredTransitCodeProvider.value)

        // 记成默认后一点就开
        viewModel.onEvent(ArrivalAlertEvent.OpenTransitCode)
        assertEquals(false, viewModel.transitCodeSheetOpen.value)
        assertEquals(listOf(TransitCodeProvider.ALIPAY, TransitCodeProvider.ALIPAY), launcher.opened)
    }

    @Test
    fun transitCodeSheet_dismiss() = runTest(main.dispatcher) {
        viewModel.onEvent(ArrivalAlertEvent.OpenTransitCode)
        viewModel.onEvent(ArrivalAlertEvent.DismissTransitCodeSheet)
        assertEquals(false, viewModel.transitCodeSheetOpen.value)
        assertTrue(launcher.opened.isEmpty())
    }
}
