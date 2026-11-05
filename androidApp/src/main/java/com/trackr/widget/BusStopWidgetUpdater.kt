package com.trackr.widget

import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.domain.arrival.ArrivalState
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.TransitCity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 小组件一次刷新的结果，所有实例共用一份（0353590 ①）。
 *
 * @param favoriteId 这份结果对应的收藏；收藏换了以后旧结果不再显示
 * @param etaText 倒计时大字
 * @param progressPercent 进度条 0..100
 * @param updatedAtMillis 右上角「HH:mm」用的时刻
 */
data class BusStopWidgetEta(
    val favoriteId: String,
    val etaText: String,
    val progressPercent: Int,
    val updatedAtMillis: Long
)

/**
 * 小组件取数（第 26 步从 `BusStopWidgetProvider` 抽出来，行为照 0353590）：
 * - 所有实例显示同一条收藏（第一条），一次刷新只请求一次实时数据再分给各实例；
 * - 城市带上收藏里的掌上公交城市编码；
 * - 最近车按 `order + segmentProgress`（[ArrivalText.nearestBus]）；
 * - 请求限时 [timeoutMillis]，超时或失败显示「点击刷新同步数据」；
 * - 先推一版「查询中…」的基础布局，再推结果。
 *
 * 取数走 [RealtimeRepository]，和首页、详情页同时刷新时命中它的 6 秒合并缓存。
 */
class BusStopWidgetUpdater(
    private val favoritesRepository: FavoritesRepository,
    private val realtime: RealtimeRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = FETCH_TIMEOUT_MS
) {

    /** 收藏列表；小组件显示第一条 */
    val favorites: StateFlow<List<FavoriteStationLine>> get() = favoritesRepository.favorites

    /** 小组件显示的收藏：第一条，没有就是 null（显示空状态） */
    fun currentFavorite(): FavoriteStationLine? = favorites.value.firstOrNull()

    /**
     * 刷新 [instances] 个实例：先 [publish] 一次「查询中…」，取数后再 [publish] 一次结果。
     * 没有实例或没有收藏时不发请求、不 publish（空状态由界面直接按收藏画）。
     */
    suspend fun <T> refresh(
        instances: List<T>,
        publish: suspend (instances: List<T>, eta: BusStopWidgetEta) -> Unit
    ) {
        if (instances.isEmpty()) return
        val favorite = currentFavorite() ?: return

        publish(instances, loading(favorite))

        val (text, progress) = try {
            val detail = withTimeoutOrNull(timeoutMillis) {
                realtime.getLineDetail(
                    city = TransitCity(
                        cityId = favorite.cityId,
                        cityName = favorite.cityName,
                        mygolbsCityCode = favorite.mygolbsCityCode.takeIf { it.isNotBlank() }
                    ),
                    lineId = favorite.lineId,
                    targetOrder = favorite.targetOrder,
                    stationId = favorite.targetStopId,
                    source = favorite.source,
                    lineNameHint = favorite.lineName,
                    directionHint = favorite.direction
                ).getOrNull()
            }
            widgetEtaOf(favorite, detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 原来进度保持基础布局的 0
            ETA_NETWORK_ERROR to 0
        }
        publish(instances, BusStopWidgetEta(favorite.id, text, progress, nowMillis()))
    }

    /** 基础布局：「查询中…」、进度 0 */
    fun loading(favorite: FavoriteStationLine): BusStopWidgetEta =
        BusStopWidgetEta(favorite.id, ETA_LOADING, 0, nowMillis())

    companion object {
        /** 实时请求超时（0353590：原来要在广播 10 秒限制内 finish；迁 Glance 后照旧 7 秒，超时文案不变） */
        const val FETCH_TIMEOUT_MS = 7_000L

        const val ETA_LOADING = "查询中…"
        const val ETA_TIMEOUT = "点击刷新同步数据"
        const val ETA_NETWORK_ERROR = "网络连接异常，点击刷新"
    }
}

/**
 * 根据线路详情算小组件主文案与进度（原 `BusStopWidgetProvider.buildEta`，逻辑不变）。
 * [detail] 为 null（超时 / 失败）时是「点击刷新同步数据」。
 */
internal fun widgetEtaOf(favorite: FavoriteStationLine, detail: BusLineDetail?): Pair<String, Int> {
    if (detail == null) return BusStopWidgetUpdater.ETA_TIMEOUT to 0

    // 到站文字走 ArrivalText（「到站了」按 ArrivalRules 判；车都过了只给下一班始发时间）
    val line = detail.line
    val state = ArrivalText.stateFor(
        targetOrder = favorite.targetOrder,
        buses = detail.buses,
        operatingState = detail.operatingState,
        startStop = line.startStop,
        firstTime = line.firstTime,
        lastTime = line.lastTime,
        nextDeparture = detail.nextDepartureFromStart,
        statusDescription = detail.statusDescription,
        now = ArrivalText.localTimeOf(detail.updatedAtMillis)
    )
    val text = ArrivalText.format(state).short
    val nearestBus = ArrivalText.nearestBus(favorite.targetOrder, detail.buses)
    val progress = when {
        state is ArrivalState.Arriving -> 100
        nearestBus == null -> 0
        nearestBus.stopsTo(favorite.targetOrder) <= 0 -> 92
        else -> {
            val totalToTarget = favorite.targetOrder.coerceAtLeast(1)
            val currentSegment = (nearestBus.order - 1).coerceAtLeast(0) + nearestBus.segmentProgress
            ((currentSegment / totalToTarget) * 100).toInt().coerceIn(5, 95)
        }
    }
    return text to progress
}
