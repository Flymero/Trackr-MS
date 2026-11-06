package com.trackr.ui.screens.stopdetail

import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.InMemoryTrackingSessionStore
import com.trackr.core.model.BoardChoice
import com.trackr.core.model.BoardOption
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.WatchLine
import com.trackr.core.testing.FakeTrackingController
import com.trackr.core.testing.MainDispatcherHolder
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** 「上了哪辆？」导航弹层：只认链接里那次会话的待选，选完 / 没了 / 换会话就关 */
class ChooseBoardedBusViewModelTest {

    private val main = MainDispatcherHolder()

    @BeforeTest
    fun setup() = main.install()

    @AfterTest
    fun tearDown() = main.reset()

    private val target = TrackingTarget.WatchTogether(
        cityId = "034",
        stopId = "stop_001",
        stopName = "人民广场",
        lines = listOf(WatchLine("line_49", "49路", 2, "上海体育馆"), WatchLine("line_71", "71路", 6, "徐家汇"))
    )
    private val choice = BoardChoice(
        options = listOf(
            BoardOption(lineId = "line_49", lineName = "49路", busId = "b49", plate = "沪A·49001"),
            BoardOption(lineId = "line_71", lineName = "71路", busId = "b71")
        ),
        sinceMillis = 1_000L
    )

    private fun TestScope.newRepo() = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope)

    @Test
    fun pendingChoice_isShown_andChoosingForwardsToController() = runTest(main.dispatcher) {
        val repo = newRepo()
        val session = assertNotNull(repo.start(target))
        repo.update(session.sessionId, session.generation) { it.copy(boardChoice = choice) }
        val controller = FakeTrackingController(repo)
        val viewModel = ChooseBoardedBusViewModel(session.sessionId, repo, controller)
        testScheduler.runCurrent()

        val shown = assertIs<ChooseBoardedBusState.Showing>(viewModel.state.value).choice
        assertEquals("两辆车都在 人民广场", shown.subtitle)
        assertEquals(listOf("b49", "b71"), shown.options.map { it.busId })
        assertEquals(listOf("上海体育馆", "徐家汇"), shown.options.map { it.endStop })

        viewModel.choose(null)
        testScheduler.runCurrent()
        assertEquals(listOf<String?>(null), controller.boardChoices)
        // 「还没上」清掉待选：弹层关
        assertEquals(ChooseBoardedBusState.Closed, viewModel.state.value)
    }

    @Test
    fun noPendingChoice_closes() = runTest(main.dispatcher) {
        val repo = newRepo()
        val session = assertNotNull(repo.start(target))
        val viewModel = ChooseBoardedBusViewModel(session.sessionId, repo, FakeTrackingController(repo))
        testScheduler.runCurrent()

        assertEquals(ChooseBoardedBusState.Closed, viewModel.state.value)
    }

    @Test
    fun otherSessionOrNoSession_closes() = runTest(main.dispatcher) {
        val repo = newRepo()
        val session = assertNotNull(repo.start(target))
        repo.update(session.sessionId, session.generation) { it.copy(boardChoice = choice) }
        val stale = ChooseBoardedBusViewModel("s-old", repo, FakeTrackingController(repo))
        testScheduler.runCurrent()
        assertEquals(ChooseBoardedBusState.Closed, stale.state.value)

        val current = ChooseBoardedBusViewModel(session.sessionId, repo, FakeTrackingController(repo))
        testScheduler.runCurrent()
        assertIs<ChooseBoardedBusState.Showing>(current.state.value)

        // 追踪结束了：关
        repo.stop()
        testScheduler.runCurrent()
        assertEquals(ChooseBoardedBusState.Closed, current.state.value)
    }
}
