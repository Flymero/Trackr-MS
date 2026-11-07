package com.trackr.feature.line.list

import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.MeishanArea
import com.trackr.core.model.MeishanLine
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.testing.FakeMeishanLineRepository
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSettingsRepository
import com.trackr.core.testing.MainDispatcherHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「线路」页签（眉山官方全量线路列表）的 ViewModel 测试：
 * 加载 / 失败重试、区域切换、本地过滤、点击按名称搜当前数据源跳现有详情页。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineListViewModelTest {

    private val dispatcherHolder = MainDispatcherHolder()
    private val city = TransitCity(cityId = "999", cityName = "眉山", pinyin = "meishan", province = "四川")

    private lateinit var meishanLines: FakeMeishanLineRepository
    private lateinit var realtime: FakeRealtimeRepository

    private fun sampleLines(): List<MeishanLine> = listOf(
        MeishanLine(lId = 24, name = "11路", forwardText = "眉山东站:凯旋国际社区", startTime = "06:30", endTime = "22:05"),
        MeishanLine(lId = 1, name = "1路", forwardText = "眉山东站:客运中心", startTime = "06:30", endTime = "21:00"),
        MeishanLine(lId = 2, name = "2路", forwardText = "东坡竹园:眉山东站"),
        MeishanLine(lId = 10063, name = "机动组", forwardText = null, startTime = null, endTime = null)
    )

    @BeforeTest
    fun setUp() {
        dispatcherHolder.install()
        meishanLines = FakeMeishanLineRepository()
        realtime = FakeRealtimeRepository()
        settings = FakeSettingsRepository(city)
    }

    @AfterTest
    fun tearDown() {
        dispatcherHolder.reset()
    }

    private lateinit var settings: FakeSettingsRepository

    private fun createViewModel(): LineListViewModel = LineListViewModel(
        meishanLines = meishanLines,
        settings = settings,
        realtime = realtime
    )

    @Test
    fun `初始加载眉山区域并按名称自然排序`() = runTest(dispatcherHolder.dispatcher) {
        meishanLines.result = Result.success(sampleLines())

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MeishanArea.MEISHAN, state.area)
        assertEquals(listOf("1路", "2路", "11路", "机动组"), state.lines.map { it.name })
        assertTrue(!state.isLoading)
        assertTrue(!state.loadFailed)
        assertEquals(listOf(MeishanArea.MEISHAN), meishanLines.calls)
    }

    @Test
    fun `城市切换后线路页跟随切换区域`() = runTest(dispatcherHolder.dispatcher) {
        // 页签的 ViewModel 常驻：城市从眉山换到仁寿后，列表要跟着换成仁寿
        meishanLines.result = Result.success(sampleLines())
        val viewModel = createViewModel()
        advanceUntilIdle()

        settings.selectCity(TransitCity(cityId = "", cityName = "仁寿", province = "四川"))
        advanceUntilIdle()

        assertEquals(MeishanArea.RENSHOU, viewModel.uiState.value.area)
        assertEquals(listOf(MeishanArea.MEISHAN, MeishanArea.RENSHOU), meishanLines.calls)

        // 换到非眉山系城市（如上海）：线路页保持当前区域不动
        settings.selectCity(TransitCity(cityId = "034", cityName = "上海", province = "直辖市"))
        advanceUntilIdle()
        assertEquals(MeishanArea.RENSHOU, viewModel.uiState.value.area)
    }

    @Test
    fun `切城后旧城市的慢结果不覆盖新城市`() = runTest(dispatcherHolder.dispatcher) {
        // 初始眉山加载挂起（慢）→ 切仁寿 → 仁寿先回 → 眉山的慢结果后回
        val gate = CompletableDeferred<Unit>()
        val renshouLines = listOf(MeishanLine(lId = 10000, name = "仁寿1路", area = MeishanArea.RENSHOU))
        meishanLines.responder = { area ->
            if (area == MeishanArea.MEISHAN) {
                gate.await()
                Result.success(sampleLines())
            } else {
                Result.success(renshouLines)
            }
        }
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(MeishanArea.MEISHAN, viewModel.uiState.value.area)

        settings.selectCity(TransitCity(cityId = "", cityName = "仁寿", province = "四川"))
        advanceUntilIdle()
        assertEquals(MeishanArea.RENSHOU, viewModel.uiState.value.area)
        assertEquals(listOf("仁寿1路"), viewModel.uiState.value.lines.map { it.name })

        // 眉山的旧结果此刻才回来：不得覆盖仁寿（列表既不能变成眉山线，也不能被清成错误态）
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(MeishanArea.RENSHOU, viewModel.uiState.value.area)
        assertEquals(listOf("仁寿1路"), viewModel.uiState.value.lines.map { it.name })
        assertFalse(viewModel.uiState.value.loadFailed)
    }

    @Test
    fun `加载失败展示错误态 重试后恢复`() = runTest(dispatcherHolder.dispatcher) {
        meishanLines.result = Result.failure(Exception("网络错误"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        var state = viewModel.uiState.value
        assertTrue(state.loadFailed)
        assertTrue(state.lines.isEmpty())

        meishanLines.result = Result.success(sampleLines())
        viewModel.onEvent(LineListEvent.Retry)
        advanceUntilIdle()

        state = viewModel.uiState.value
        assertTrue(!state.loadFailed)
        assertEquals(4, state.lines.size)
    }

    @Test
    fun `过滤同时匹配名称和起终点`() = runTest(dispatcherHolder.dispatcher) {
        meishanLines.result = Result.success(sampleLines())
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LineListEvent.QueryChanged("11"))
        assertEquals(listOf("11路"), viewModel.uiState.value.visibleLines.map { it.name })

        viewModel.onEvent(LineListEvent.QueryChanged("凯旋"))
        assertEquals(listOf("11路"), viewModel.uiState.value.visibleLines.map { it.name })

        viewModel.onEvent(LineListEvent.QueryChanged("东坡竹园"))
        assertEquals(listOf("2路"), viewModel.uiState.value.visibleLines.map { it.name })

        viewModel.onEvent(LineListEvent.QueryChanged("不存在的线路"))
        assertTrue(viewModel.uiState.value.visibleLines.isEmpty())
    }

    @Test
    fun `点击命中线路产生待打开的详情并消费后清空`() = runTest(dispatcherHolder.dispatcher) {
        meishanLines.result = Result.success(sampleLines())
        realtime.searchResultsByKeyword["11路"] = Result.success(
            TransitSearchResult(
                keyword = "11路",
                lines = listOf(
                    BusLineSummary(
                        name = "11路",
                        lineNo = "11",
                        directions = listOf(
                            BusLineDirection(
                                lineId = "cl-11-0",
                                name = "11路",
                                direction = 0,
                                startStop = "眉山东站",
                                endStop = "凯旋国际社区",
                                source = DataSourceType.CHELAILE
                            )
                        ),
                        source = DataSourceType.CHELAILE
                    )
                )
            )
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LineListEvent.LineClicked(viewModel.uiState.value.lines.first { it.name == "11路" }))
        advanceUntilIdle()

        val pending = viewModel.uiState.value.pendingLineDetail
        assertNotNull(pending)
        assertEquals("999", pending.cityId)
        assertEquals("cl-11-0", pending.lineId)
        assertEquals("11路", pending.lineName)
        assertNull(viewModel.uiState.value.openingLineId)

        viewModel.onEvent(LineListEvent.PendingNavigationConsumed)
        assertNull(viewModel.uiState.value.pendingLineDetail)
    }

    @Test
    fun `点击未覆盖线路提示且不产生跳转`() = runTest(dispatcherHolder.dispatcher) {
        meishanLines.result = Result.success(sampleLines())
        realtime.searchResult = Result.success(TransitSearchResult(keyword = "11路", lines = emptyList()))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LineListEvent.LineClicked(viewModel.uiState.value.lines.first { it.name == "11路" }))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.pendingLineDetail)
        assertNull(state.openingLineId)
        val message = assertIs<LineListMessage.NotCovered>(state.message)
        assertEquals("11路", message.lineName)

        viewModel.onEvent(LineListEvent.MessageShown(message.id))
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `搜索抛异常按未覆盖处理`() = runTest(dispatcherHolder.dispatcher) {
        meishanLines.result = Result.success(sampleLines())
        realtime.searchResult = Result.failure(Exception("boom"))
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LineListEvent.LineClicked(viewModel.uiState.value.lines.first { it.name == "11路" }))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingLineDetail)
        assertIs<LineListMessage.NotCovered>(viewModel.uiState.value.message)
    }
}
