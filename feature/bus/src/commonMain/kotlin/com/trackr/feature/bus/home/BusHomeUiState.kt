package com.trackr.feature.bus.home

import com.trackr.core.model.DataSourceType
import com.trackr.core.model.EngineMode
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.GroupColor
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TransitCity
import com.trackr.ui.components.arrival.ArrivalUi

/**
 * 定位失败的原因（issue #34：小米 14 上权限给了还是提示「定位不可用」，用户不知道该去哪开）。
 *
 * 三个原因对应三句文案、两个去处：
 * - [PermissionMissing]：应用没有定位权限 → 去应用详情页授权
 * - [ServiceDisabled]：权限有，但系统「位置信息」总开关关着 → 去系统位置信息页开
 * - [Unavailable]：权限和开关都正常，这一轮没取到（室内、冷启动、GPS 没星） → 再试一次
 */
enum class LocationIssue {
    PermissionMissing,
    ServiceDisabled,
    Unavailable
}

/**
 * 公交首页的状态（v3「公交」页）。
 *
 * 上半部分是数据（ViewModel 自己用、测试断言用），下半部分 [trackingCards]、[favoriteRows]、[nearbyCards]
 * 是给页面画的扁平 UI 模型（重构计划 §4.1）：每次数据变了由 [BusHomeMapper] 重新算，没变的行沿用旧实例。
 */
data class BusHomeUiState(
    val currentCity: TransitCity = DEFAULT_CITY,
    val selectedProvince: String = "直辖市",
    val locatedCity: TransitCity? = null,
    val cities: List<TransitCity> = emptyList(),
    val citySearchQuery: String = "",
    val isCityPickerOpen: Boolean = false,
    val currentLat: Double = 31.2304,
    val currentLng: Double = 121.4737,
    val isLocating: Boolean = false,
    /** 定位失败的原因；null = 正常或还没定位过。文案和按钮由页面按它决定（[LocationIssue]） */
    val locationIssue: LocationIssue? = null,
    val nearbyStops: List<NearbyStop> = emptyList(),
    val isLoadingNearby: Boolean = false,
    /** 收藏的车（站 + 线），小组件 / 快捷方式也用这份 */
    val favorites: List<FavoriteStationLine> = emptyList(),
    /** 收藏的车的实时到站，key 是 [FavoriteStationLine.id] */
    val favoritesRealtime: Map<String, NearbyStop> = emptyMap(),
    /** 收藏合一列表（车和路线排在一起，第 14 步） */
    val favoriteItems: List<FavoriteItem> = emptyList(),
    val favoriteGroups: List<FavoriteGroup> = emptyList(),
    /** 分组 chips 选中的分组；null =「全部」 */
    val selectedGroupId: String? = null,
    /** 当前这一程的追踪状态（主会话：单线路、路线、一起等排第一的那条） */
    val activeTracking: TrackingSessionState = TrackingSessionState(),
    /** 全部追踪会话（可以同时追多项）；没在追踪时为空 */
    val trackingSessions: List<TrackingSession> = emptyList(),
    val isRefreshing: Boolean = false,
    val engineMode: EngineMode = EngineMode.CHELAILE,
    /** 「每次询问」时点乘车码弹出的平台选择 */
    val isTransitCodeSheetOpen: Boolean = false,
    /** 右下角「乘车码」悬浮按钮显不显示（「我的」里可以关掉，用 NFC 的嫌它挡列表） */
    val showTransitCodeButton: Boolean = true,
    /** 一次性消息（重构计划 §2.1 UDF）：页面显示后回调 [BusHomeEvent.MessageShown] */
    val userMessages: List<UserMessage> = emptyList(),

    // ---- 给页面画的 UI 模型 ----
    val trackingCards: List<TrackingCardUi> = emptyList(),
    val favoriteRows: List<FavoriteRowUi> = emptyList(),
    val nearbyCards: List<NearbyStopUi> = emptyList()
) {
    val filteredCities: List<TransitCity>
        get() {
            val baseList = when (engineMode) {
                EngineMode.MYGOLBS -> cities.filter { !it.mygolbsCityCode.isNullOrBlank() }
                EngineMode.CHELAILE -> cities.filter { it.cityId.isNotBlank() }
            }
            if (citySearchQuery.isBlank()) return baseList
            val q = citySearchQuery.trim().lowercase()
            return baseList.filter {
                it.cityName.lowercase().contains(q) ||
                    it.pinyin.lowercase().contains(q) ||
                    (it.province.ifBlank { ProvinceCityCatalog.inferProvince(it.cityName) }).lowercase().contains(q)
            }
        }

    /** 定位 / 下拉刷新 / 首次加载附近站中，任何一个在进行就显示刷新圈 */
    val isBusy: Boolean get() = isRefreshing || isLocating || isLoadingNearby

    companion object {
        val DEFAULT_CITY = TransitCity(
            cityId = "034",
            cityName = "上海",
            pinyin = "shanghai",
            supportSubway = true,
            isHot = true,
            province = "直辖市"
        )
    }
}

/** 一次性消息。[id] 用来回报「显示过了」 */
data class UserMessage(
    val id: Long,
    val text: String,
    /** 长辈版换一种说法（长辈首页没有下拉刷新）；null 时两版一样 */
    val elderText: String? = null
)

/** 打开线路页要的参数（和 `ScreenKey.LineDetail` 一一对应） */
data class LineTarget(
    val cityId: String,
    val lineId: String,
    val targetOrder: Int,
    val stopId: String,
    val source: DataSourceType,
    val lineName: String
)

/** 打开站点页要的参数（和 `ScreenKey.StopDetail` 一一对应） */
data class StopTarget(
    val cityId: String,
    val stopId: String,
    val stopName: String,
    val physicalStId: String,
    val namesakeStId: String,
    val lat: Double,
    val lng: Double
)

/**
 * 首页顶上的一张追踪卡。同时追多项时每项一张（一起等的几条线也各一张），各自点进自己的线路页。
 *
 * @param key LazyColumn 的 key（一起等按线拆开，所以是「会话 + 线」）
 * @param sessionId 这张卡属于哪一份追踪会话（点「结束」用）
 * @param title 第一行（「追踪 325 · 科技园站」「路线 家 → 公司 · 第 1 程，共 2 程」）
 * @param arrival 大字（这项最先到的那辆）
 * @param detail 大字下面一行（「还有 2 站 · 约 4 分钟」「到站了，快去上车」）
 * @param open 点卡片去哪个线路页
 * @param spoken「读给我听」读的整句（长辈版）；空串 = 不给读（已经在车上）
 * @param watchLineId 一起等里的一条线：点「结束」只去掉这一条；null = 结束整份会话
 */
data class TrackingCardUi(
    val key: String,
    val sessionId: String,
    val title: String,
    val arrival: ArrivalUi,
    val detail: String,
    val open: LineTarget,
    val spoken: String = "",
    val watchLineId: String? = null
)

/** 收藏合一列表里的一行 */
sealed interface FavoriteRowUi {
    /** 合一列表的 key（`stop_x` / `route_x` / `station_x`，类型之间不重复） */
    val key: String

    /** 所在分组的颜色（issue #38），卡片左边画一条色条；不分组或分组没设颜色时为 null */
    val groupColor: GroupColor?

    /**
     * 收藏的车：「325 · 科技园站上车 · → 科技园」+ 到站。
     * @param arrival 还没拿到实时数据时是「正在查车」
     * @param spoken「读给我听」读的整句（长辈版），如「325路，开往科技园，还有 2 站，大约 4 分钟到科技园站」
     */
    data class Stop(
        override val key: String,
        val lineName: String,
        val boardText: String,
        val direction: String,
        val arrival: ArrivalUi,
        val open: LineTarget,
        val spoken: String = "",
        override val groupColor: GroupColor? = null
    ) : FavoriteRowUi

    /**
     * 收藏的站（issue #47）：「科技园站 · 往北」+ 最先到的几条线「19 2 分 · 325 4 分」+ 最先到那辆的大字。
     *
     * @param lines 按到站先后最多 [com.trackr.feature.bus.home.BusHomeMapper.STATION_PREVIEW_LINES] 条
     * @param lineCount 这个站台一共几条线；还没查到时为 0
     * @param status 还在查 / 查到了 / 查不到这个站
     * @param arrival 最先到那辆的大字；查不到时为 null
     * @param spoken「读给我听」读的整句（长辈版）
     */
    data class Station(
        override val key: String,
        val title: String,
        val lines: List<StationLineUi>,
        val lineCount: Int,
        val status: StationStatus,
        val arrival: ArrivalUi?,
        val open: StopTarget,
        val spoken: String = "",
        override val groupColor: GroupColor? = null
    ) : FavoriteRowUi

    /** 收藏的路线：「家 → 公司」+「325 › 19 · 换乘 1 次」 */
    data class Route(
        override val key: String,
        val routeId: String,
        val title: String,
        val linesText: String,
        override val groupColor: GroupColor? = null
    ) : FavoriteRowUi
}

/** 收藏的站有没有拿到数据 */
enum class StationStatus { Loading, Ready, NotFound }

/** 收藏的站里的一条线：「19」「2 分」（[short] 是到站的短写） */
data class StationLineUi(
    val lineName: String,
    val direction: String,
    val short: String,
    val arrival: ArrivalUi
)

/** 附近的一个站 */
data class NearbyStopUi(
    val key: String,
    val name: String,
    /** 「120 米 · 8 条线 · 地铁 1 号线」 */
    val meta: String,
    val lines: List<NearbyLineUi>,
    val open: StopTarget
)

/** 附近站里的一条线：「325 → 科技园 · 4 分钟」 */
data class NearbyLineUi(
    val key: String,
    val lineName: String,
    val direction: String,
    val arrival: ArrivalUi,
    val open: LineTarget
)
