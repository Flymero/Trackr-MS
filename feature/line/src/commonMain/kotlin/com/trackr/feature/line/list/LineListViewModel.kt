package com.trackr.feature.line.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.MeishanLineRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.MeishanArea
import com.trackr.core.model.MeishanLine
import com.trackr.core.navigation.ScreenKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一次性提示。文案在 UI 层用 stringResource 生成（ViewModel 不持有资源）。
 * [id] 用来回报「显示过了」。
 */
sealed interface LineListMessage {
    val id: Long

    /** 当前数据源（车来了 / 掌上公交）没有这条线路 */
    data class NotCovered(override val id: Long, val lineName: String) : LineListMessage
}

/** 「线路」页签的状态（眉山特化：官方 /line/list 全量线路列表） */
data class LineListUiState(
    /** 当前区域（眉山 / 仁寿），默认眉山 */
    val area: MeishanArea = MeishanArea.MEISHAN,
    /** 当前区域的全部线路（已按名称自然排序） */
    val lines: List<MeishanLine> = emptyList(),
    /** 名称 / 起终点过滤框内容 */
    val query: String = "",
    /** 正在拉取线路列表（首次或切换区域） */
    val isLoading: Boolean = false,
    /** 列表加载失败；非 null 时展示错误态（可重试） */
    val loadFailed: Boolean = false,
    /** 点击后正在按名称搜当前数据源（防连点） */
    val openingLineId: Int? = null,
    /** 搜索命中、待 Route 打开的第三方线路详情；消费后发 [LineListEvent.PendingNavigationConsumed] */
    val pendingLineDetail: ScreenKey.LineDetail? = null,
    /** 一次性提示 */
    val message: LineListMessage? = null
) {
    /** 按名称 / 起终点过滤后的列表（重名线路靠起终点也能搜到） */
    val visibleLines: List<MeishanLine>
        get() {
            val q = query.trim()
            if (q.isBlank()) return lines
            return lines.filter {
                it.name.contains(q, ignoreCase = true) || it.primaryText.contains(q, ignoreCase = true)
            }
        }
}

/** 「线路」页签的事件（区域不再手动切换，严格跟随当前城市） */
sealed interface LineListEvent {
    data class QueryChanged(val query: String) : LineListEvent
    data object Retry : LineListEvent
    data class LineClicked(val line: MeishanLine) : LineListEvent
    data object PendingNavigationConsumed : LineListEvent
    data class MessageShown(val id: Long) : LineListEvent
}

/**
 * 「线路」页签的 ViewModel：官方 `/line/list` 全量线路 + 区域切换 + 本地过滤。
 * 点击线路按名称搜当前数据源（车来了 / 掌上公交），命中交给 Route 打开现有线路详情页。
 */
class LineListViewModel(
    private val meishanLines: MeishanLineRepository,
    private val settings: SettingsRepository,
    private val realtime: RealtimeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LineListUiState(isLoading = true))
    val uiState: StateFlow<LineListUiState> = _uiState.asStateFlow()

    private var nextMessageId = 0L

    init {
        // 默认区域跟着当前城市走：选仁寿就默认仁寿，否则默认眉山
        val initialArea = MeishanArea.forCityName(settings.selectedCity.value.cityName) ?: MeishanArea.MEISHAN
        loadLines(initialArea)
        // 页签的 ViewModel 会一直活着（entry 常驻返回栈），城市切换后必须跟着换区域，
        // 否则切到仁寿后进「线路」页还是上一次的眉山列表
        viewModelScope.launch {
            settings.selectedCity.collect { city ->
                val mapped = MeishanArea.forCityName(city.cityName) ?: return@collect
                if (mapped != _uiState.value.area) loadLines(mapped)
            }
        }
    }

    fun onEvent(event: LineListEvent) {
        when (event) {
            is LineListEvent.QueryChanged -> _uiState.update { it.copy(query = event.query) }
            LineListEvent.Retry -> loadLines(_uiState.value.area, forceRefresh = true)
            is LineListEvent.LineClicked -> openLine(event.line)
            LineListEvent.PendingNavigationConsumed ->
                _uiState.update { it.copy(pendingLineDetail = null) }
            is LineListEvent.MessageShown ->
                _uiState.update { state ->
                    if (state.message?.id == event.id) state.copy(message = null) else state
                }
        }
    }

    private fun loadLines(area: MeishanArea, forceRefresh: Boolean = false) {
        _uiState.update { it.copy(area = area, isLoading = true, loadFailed = false) }
        viewModelScope.launch {
            val result = meishanLines.getLines(area, forceRefresh)
            result.fold(
                onSuccess = { lines ->
                    val sorted = lines.sortedWith { a, b -> RouteProjectionMath.compareLineNames(a.name, b.name) }
                    _uiState.update { it.copy(lines = sorted, isLoading = false, loadFailed = false) }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    _uiState.update { it.copy(lines = emptyList(), isLoading = false, loadFailed = true) }
                }
            )
        }
    }

    /**
     * 按名称搜当前数据源，命中把 [ScreenKey.LineDetail] 交给 Route 打开现有详情页
     * （实时到站、收藏、追踪都复用）；未命中提示，不静默失败。
     */
    private fun openLine(line: MeishanLine) {
        val state = _uiState.value
        if (state.openingLineId != null || state.pendingLineDetail != null) return
        _uiState.update { it.copy(openingLineId = line.lId) }
        viewModelScope.launch {
            val city = settings.selectedCity.value
            // search 本身返回 Result；这里再兜一层非取消异常，失败按「未覆盖」提示
            val searchResult = try {
                realtime.search(city, line.name)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            val summary = searchResult?.getOrNull()?.lines
                ?.firstOrNull { it.name == line.name || it.name.contains(line.name) || line.name.contains(it.name) }
            val direction = summary?.primaryDirection
            if (summary != null && direction != null) {
                _uiState.update {
                    it.copy(
                        openingLineId = null,
                        pendingLineDetail = ScreenKey.LineDetail(
                            cityId = city.cityId,
                            lineId = direction.lineId,
                            source = summary.source,
                            lineName = line.name
                        )
                    )
                }
            } else {
                showMessage(LineListMessage.NotCovered(nextMessageId, line.name))
                _uiState.update { it.copy(openingLineId = null) }
            }
        }
    }

    private fun showMessage(message: LineListMessage) {
        nextMessageId++
        _uiState.update { it.copy(message = message) }
    }
}
