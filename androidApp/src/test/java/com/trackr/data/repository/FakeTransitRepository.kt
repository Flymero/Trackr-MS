package com.trackr.data.repository

import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SearchHistoryRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.testing.FAKE_DEFAULT_CITY
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeRealtimeRepository
import com.trackr.core.testing.FakeSearchHistoryRepository
import com.trackr.core.testing.FakeSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 可控的内存 Fake TransitRepository，用于 Unit 测试与 Compose UI 行为测试。
 *
 * 第 8 步按接口拆开：设置 / 收藏 / 搜索历史 / 实时查询各用 `:core:testing` 里的一个 Fake，这里只是把它们合起来
 * （ViewModel 仍拿 [ITransitRepository]）。原来的测试钩子（`nearbyStopsResult` 等）转发到对应的 Fake，用法不变。
 */
class FakeTransitRepository private constructor(
    val settings: FakeSettingsRepository,
    val favoritesFake: FakeFavoritesRepository,
    val historyFake: FakeSearchHistoryRepository,
    val realtime: FakeRealtimeRepository
) : ITransitRepository,
    SettingsRepository by settings,
    FavoritesRepository by favoritesFake,
    SearchHistoryRepository by historyFake,
    RealtimeRepository by realtime {

    constructor(
        initialCity: TransitCity = FAKE_DEFAULT_CITY,
        initialFavorites: List<FavoriteStationLine> = emptyList(),
        initialSearchHistory: List<String> = emptyList(),
        initialTransitCodeProvider: TransitCodeProvider = TransitCodeProvider.ASK_ALWAYS
    ) : this(FakeSettingsRepository(initialCity, initialTransitCodeProvider), initialFavorites, initialSearchHistory)

    private constructor(
        settings: FakeSettingsRepository,
        initialFavorites: List<FavoriteStationLine>,
        initialSearchHistory: List<String>
    ) : this(
        settings = settings,
        favoritesFake = FakeFavoritesRepository(initialFavorites),
        historyFake = FakeSearchHistoryRepository(initialSearchHistory),
        realtime = FakeRealtimeRepository(currentCity = { settings.selectedCity.value })
    )

    /** 测试里设为 false 模拟首次安装（从未存过城市） */
    fun setHasSavedCity(saved: Boolean) = settings.setHasSavedCity(saved)

    // 可配置的测试数据钩子（转发到 FakeRealtimeRepository）
    var supportedCitiesResult: Result<List<TransitCity>> by realtime::supportedCitiesResult
    var nearbyStopsResult: Result<List<NearbyStop>> by realtime::nearbyStopsResult
    var stopDetailResult: Result<List<NearbyStop>> by realtime::stopDetailResult
    var searchResult: Result<TransitSearchResult> by realtime::searchResult
    var lineDetailResult: Result<BusLineDetail>? by realtime::lineDetailResult
    val lineDetailsMap: MutableMap<String, BusLineDetail> get() = realtime.lineDetailsMap
    var timetableResult: Result<LineTimetable> by realtime::timetableResult
    var favoritesRealtimeResult: Result<Map<String, NearbyStop>> by realtime::favoritesRealtimeResult

    /** 为 null 时默认返回当前已选城市 */
    var resolveCityResult: Result<TransitCity?>? by realtime::resolveCityResult

    /** 记录每次附近站点查询的 (城市, 纬度, 经度) */
    val nearbyStopsCalls: MutableList<Triple<TransitCity, Double, Double>> get() = realtime.nearbyStopsCalls

    /** 可按查询坐标返回不同结果；为 null 时统一返回 [nearbyStopsResult] */
    var nearbyStopsResultProvider: (suspend (TransitCity, Double, Double) -> Result<List<NearbyStop>>)? by realtime::nearbyStopsResultProvider

    private val _userLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override val userLocation: StateFlow<Pair<Double, Double>?> = _userLocation.asStateFlow()

    override fun updateUserLocation(lat: Double, lng: Double) {
        _userLocation.value = lat to lng
    }
}
