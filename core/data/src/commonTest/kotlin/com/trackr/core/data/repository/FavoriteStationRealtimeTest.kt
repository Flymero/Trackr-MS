package com.trackr.core.data.repository

import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStation
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.core.network.provider.model.LineDetailRequest
import com.trackr.core.network.provider.model.StopDetailRequest
import com.trackr.core.testing.FakeFavoritesRepository
import com.trackr.core.testing.FakeSearchHistoryRepository
import com.trackr.core.testing.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 收藏的站（issue #47）：首页刷新时每个站查一次站点接口、找回默认站台（假 Provider，不发 HTTP）；
 * 以及 [StationPlatformMatcher] 怎么认站台（掌上公交改道后站号会跳，靠站名 + 坐标）。
 */
class FavoriteStationRealtimeTest {

    private class StopProvider(override val sourceType: DataSourceType) : ITransitProvider {
        var platforms: List<NearbyStop> = emptyList()
        var fail = false
        val requests = mutableListOf<StopDetailRequest>()

        override fun supportsLine(lineId: String): Boolean = true
        // 只认车来了的站，免得协调器把请求路由给掌上公交那个空 Provider
        override fun supportsStop(request: StopDetailRequest): Boolean = sourceType == DataSourceType.CHELAILE
        override fun supportsLocationRadar(): Boolean = false
        override suspend fun getSupportedCities(): List<TransitCity> = emptyList()
        override suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): String? = null
        override suspend fun search(city: TransitCity, keyword: String, userLat: Double?, userLng: Double?) =
            TransitSearchResult(keyword)
        override suspend fun getNearbyStops(city: TransitCity, wgsLat: Double, wgsLng: Double, limit: Int) =
            emptyList<NearbyStop>()
        override suspend fun getStopDetail(city: TransitCity, request: StopDetailRequest): List<NearbyStop> {
            requests += request
            if (fail) throw IOException("upstream error")
            return platforms
        }
        override suspend fun getTimetable(city: TransitCity, line: BusLineDirection, atTimeHHMM: String?) =
            LineTimetable(lineId = line.lineId, lineName = line.name)
        override suspend fun getLineDetail(city: TransitCity, request: LineDetailRequest): BusLineDetail =
            error("收藏的站不查线路详情")
    }

    private val station = FavoriteStation(
        id = FavoriteStation.idOf("034", DataSourceType.CHELAILE, "科技园", ""),
        cityId = "034", cityName = "上海", stopName = "科技园", stopId = "s-north", physicalStId = "p-north",
        platformLabel = "往北", wgsLat = 31.20, wgsLng = 121.40
    )

    private fun platform(stopId: String, physical: String?, name: String = "科技园", lat: Double = 0.0, lng: Double = 0.0, lines: Int = 1) =
        NearbyStop(
            stopId = stopId, name = name, physicalStId = physical, wgsLat = lat, wgsLng = lng,
            lines = List(lines) { i ->
                NearbyStopLineStatus(
                    line = BusLineDirection(lineId = "L$i", name = "${i}路", startStop = "A", endStop = "B"),
                    targetOrder = 3,
                    targetStopId = stopId,
                    targetStopName = name
                )
            }
        )

    private fun repositoryWith(provider: StopProvider, favorites: FakeFavoritesRepository) = DefaultRealtimeRepository(
        coordinator = TransitProviderCoordinator(chelaileProvider = provider, mygolbsProvider = StopProvider(DataSourceType.MYGOLBS)),
        settings = FakeSettingsRepository(),
        favoritesRepository = favorites,
        searchHistory = FakeSearchHistoryRepository(),
        userLocation = { null },
        clock = MonotonicClock { 0L },
        fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    @Test
    fun refresh_stationKeyedByItemKey_picksDefaultPlatform_sendsStationInfo() = runBlocking {
        val provider = StopProvider(DataSourceType.CHELAILE).apply {
            platforms = listOf(platform("s-south", "p-south", lines = 2), platform("s-north", "p-north", lines = 3))
        }
        val repo = repositoryWith(provider, FakeFavoritesRepository(initialStations = listOf(station)))

        val result = repo.refreshFavoritesRealtime().getOrThrow()

        val stop = result.getValue(FavoriteItem.stationKey(station.id))
        assertEquals("s-north", stop.stopId)
        assertEquals(3, stop.lines.size)
        val request = provider.requests.single()
        assertEquals("s-north", request.stopId)
        assertEquals("p-north", request.physicalStId)
        assertEquals("科技园", request.stopName)
        assertEquals(31.20, request.stationLat)
    }

    @Test
    fun refresh_noPlatforms_givesEmptyStop_failureGivesNothing() = runBlocking {
        val provider = StopProvider(DataSourceType.CHELAILE)
        val repo = repositoryWith(provider, FakeFavoritesRepository(initialStations = listOf(station)))

        // 查到了但没有站台：一个没有线路的站（首页「暂时查不到」）
        val empty = repo.refreshFavoritesRealtime().getOrThrow().getValue(FavoriteItem.stationKey(station.id))
        assertTrue(empty.lines.isEmpty())

        // 请求失败：不给（首页还是「正在查车」），也不拖累整次刷新
        provider.fail = true
        val failed = repo.refreshFavoritesRealtime()
        assertTrue(failed.isSuccess)
        assertFalse(FavoriteItem.stationKey(station.id) in failed.getOrThrow())
    }

    // ---- StationPlatformMatcher ----

    @Test
    fun matcher_prefersPhysicalId_thenStopId() {
        val north = platform("x", "p-north")
        val south = platform("s-north", "p-south")
        assertEquals(north, StationPlatformMatcher.pick(listOf(south, north), station))
        assertEquals(south, StationPlatformMatcher.pick(listOf(platform("a", "q"), south), station.copy(physicalStId = "")))
    }

    @Test
    fun matcher_idsJumped_fallsBackToSameNameNearest() {
        // 掌上公交临时改道：站号变成 9xx，站台 id 也认不出，按站名 + 离收藏时坐标最近的找回
        val far = platform("901", null, lat = 31.30, lng = 121.50)
        val near = platform("902", null, lat = 31.2001, lng = 121.4001)
        val otherName = platform("903", null, name = "科技园东", lat = 31.20, lng = 121.40)
        assertEquals(near, StationPlatformMatcher.pick(listOf(far, otherName, near), station))
    }

    @Test
    fun matcher_noCoordinates_takesFirstSameName_emptyIsNull() {
        val a = platform("901", null)
        val b = platform("902", null)
        assertEquals(a, StationPlatformMatcher.pick(listOf(platform("x", null, name = "别的站"), a, b), station.copy(wgsLat = 0.0, wgsLng = 0.0)))
        assertNull(StationPlatformMatcher.pick(emptyList(), station))
    }
}
