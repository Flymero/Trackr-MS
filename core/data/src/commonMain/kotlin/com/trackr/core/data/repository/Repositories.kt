package com.trackr.core.data.repository

import com.trackr.core.model.ThemeColors
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.AmapTipItem
import com.trackr.core.model.AmapTransitSchemeResult
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.EngineMode
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupColor
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.RailMode
import com.trackr.core.model.ThemeMode
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.model.UiMode
import com.trackr.core.model.UserFix
import com.trackr.core.network.remote.mygolbs.MygolbsIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * 原来的上帝接口 ITransitRepository 按职责拆成下面几个（重构计划 §2.5）。
 *
 * 第 8 步：:androidApp 的 TransitRepository 先同时实现设置 / 收藏 / 实时查询 / 搜索历史四个接口
 * （实时查询那部分委托给 commonMain 的 [DefaultRealtimeRepository]），定位由 [DefaultLocationRepository] 实现；
 * ViewModel 仍然拿 ITransitRepository，第 17 步搬进各 feature 时改成只拿要用的接口。
 * 第 9 步：存储换成 DataStore / Room（`local/` 下的 Store），由运行时开关 USE_NEW_STORE 选新旧实现，仓库逻辑只有一份。
 * 第 14 步：收藏（车 + 路线 + 分组）合一，由 [DefaultFavoritesRepository] 实现；TransitRepository 和 RouteGroupRepository 都转调它。
 */

/**
 * 设置：城市、数据源、乘车码渠道、附近站数量、实时通知开关。
 * 高德 Key 两槽和由它算出的 `hasMapKey` / `canShowMap`（§2.5）在 [AmapKeyRepository]。
 */
interface SettingsRepository {
    val selectedCity: StateFlow<TransitCity>

    /** 用户是否曾经选过/存过城市（首次安装为 false，此时才允许定位自动切换城市）。第 9 步起是 Flow */
    val hasSavedCity: StateFlow<Boolean>
    /** 实时数据源，二选一（D1） */
    val engineMode: StateFlow<EngineMode>
    val preferredTransitCodeProvider: StateFlow<TransitCodeProvider>

    /**
     * 「乘车码」按钮显不显示，默认显示：首页右下角悬浮按钮、线路详情底栏圆钮、站点详情底栏圆钮都听它，
     * 长辈版线路页 / 换站页 / 站点页底部的「出示乘车码」也一样。
     * 用 NFC 刷卡的人用不到它，开着反而挡住列表最后几条，所以可以在「我的」里关掉。
     */
    val showTransitCodeButton: StateFlow<Boolean>

    /**
     * 上车后自动结束追踪（issue #32），默认开：定位判断用户已经坐上这班车，就直接结束本次追踪。
     *
     * 只对**没有下车站**的追踪生效——单线路（线路页「追踪」、定时提醒）和站点页的一起等。
     * 路线（有明确下车站）不受影响：上车后正是它该干活的时候，结束了就收不到下车站提醒。
     * 需要精确定位；没有权限时这项开关不生效（判不出上车，追踪照旧）。
     */
    val autoStopAfterBoarding: StateFlow<Boolean>
    val nearbyStopsCount: StateFlow<Int>
    val isLiveNotificationEnabled: StateFlow<Boolean>

    /** 追踪通知在锁屏显示全部内容（D3），默认开；关了锁屏只显示「追客 · 追踪中」 */
    val showOnLockScreen: StateFlow<Boolean>

    /**
     * 到站前提醒（第 25 步「我的」全局设置）：车离上车站还有几站时提醒，1–3，默认 2。
     * 开始追踪时写进追踪目标（Android：`AndroidTrackingController.start`）；通知上的「切换」只改这一次追踪。
     */
    val alertThresholdStops: StateFlow<Int>

    /** 界面模式（重构计划 §8.2）：没存过或认不出按 [UiMode.Standard] */
    val uiMode: StateFlow<UiMode>

    /** 线路详情走向条样式；没存过或认不出按 [RailMode.Horizontal] */
    val railMode: StateFlow<RailMode>

    val themeMode: StateFlow<ThemeMode>
    val themeColors: StateFlow<ThemeColors>

    /** 系统字体放大时的「试试长辈版」提示已经点过（打开或先不用），之后不再提示 */
    val fontScalePromptDismissed: StateFlow<Boolean>

    fun setEngineMode(mode: EngineMode)
    fun setNearbyStopsCount(count: Int)
    fun setLiveNotificationEnabled(enabled: Boolean)
    fun setShowOnLockScreen(enabled: Boolean)

    /** 超出 1–3 时夹到范围内 */
    fun setAlertThresholdStops(stops: Int)
    fun setUiMode(mode: UiMode)
    fun setRailMode(mode: RailMode)
    fun setThemeMode(mode: ThemeMode)
    fun setThemeColors(colors: ThemeColors)
    fun dismissFontScalePrompt()
    suspend fun selectCity(city: TransitCity)
    suspend fun setPreferredTransitCodeProvider(provider: TransitCodeProvider)
    fun setShowTransitCodeButton(enabled: Boolean)
    fun setAutoStopAfterBoarding(enabled: Boolean)
}

/**
 * 收藏合一（第 14 步，重构计划 §2.5）：收藏的车（站 + 线）和路线排在一个列表里（[favoriteItems]），共用顺序和收藏分组。
 *
 * - 新收藏的车加到最前，新路线加到最后；[move] 拖动排序，[setGroup] 改分组。
 * - 删分组（[deleteGroup]）不删收藏，组里的车和路线都变成不分组，一个事务。
 * - [favorites] / [routes] 是同一份数据按类型分开的视图，顺序和 [favoriteItems] 一致（旧页面用）。
 * - 路线的「场景」`GroupCategory` 不再给用户选，分组只看 groupId。
 */
interface FavoritesRepository {
    /** 收藏的车，按合一顺序 */
    val favorites: StateFlow<List<FavoriteStationLine>>

    /** 合一列表：车 + 路线，按 sortOrder */
    val favoriteItems: StateFlow<List<FavoriteItem>>

    /** 路线，按合一顺序；每条的 `customGroupId` 和它在 [favoriteItems] 里的分组一致（`sortOrder` 是路线之间的旧排序字段） */
    val routes: StateFlow<List<TransitGroup>>

    /** 收藏分组，按 sortOrder */
    val favoriteGroups: StateFlow<List<FavoriteGroup>>

    /** 有就删、没有就加到最前；返回 true 表示这次是加 */
    suspend fun toggleFavorite(item: FavoriteStationLine): Boolean
    suspend fun removeFavorite(id: String)

    /**
     * 拖动排序：[keys]（[FavoriteItem.key]）按给的先后排，占用的还是这些收藏原来的位置，没列出的收藏不动。
     * 所以只给某个分组里的几条也行。认不出的 key 忽略。
     */
    suspend fun move(keys: List<String>)

    /** 改分组；[groupId] 为 null = 不分组。分组不存在或 key 认不出时什么都不做，返回 false */
    suspend fun setGroup(key: String, groupId: String?): Boolean

    /** 新路线加到最后；同 id 的路线已存在就替换内容（位置和分组按传进来的 sortOrder / customGroupId） */
    suspend fun saveRoute(route: TransitGroup)
    suspend fun removeRoute(routeId: String)

    /** 新建分组，排在最后；[color] 为 null 时不设颜色 */
    suspend fun createGroup(name: String, color: GroupColor? = null): FavoriteGroup
    suspend fun renameGroup(groupId: String, newName: String)

    /** 改分组颜色（issue #38），null = 不设颜色；分组不存在时什么都不做 */
    suspend fun setGroupColor(groupId: String, color: GroupColor?)

    /** 删分组：组里的收藏（车和路线）都变不分组，不删 */
    suspend fun deleteGroup(groupId: String)
    suspend fun reorderGroups(groupIds: List<String>)
}

/** 搜索历史：最近的在前，最多 20 条，不分大小写去重 */
interface SearchHistoryRepository {
    val searchHistory: StateFlow<List<String>>

    suspend fun addSearchHistory(query: String)
    suspend fun removeSearchHistory(query: String)
    suspend fun clearSearchHistory()
}

/**
 * 实时查询：城市列表、定位城市、附近、站点、线路、时刻表、搜索；按当前数据源选 Provider。
 * 线路详情按 key 合并并发请求、缓存 6 秒；`getStopDetail` / `getLineDetail` 被取消时照常抛出取消，不包成失败结果。
 */
interface RealtimeRepository {
    suspend fun getSupportedCities(forceRefresh: Boolean = false): Result<List<TransitCity>>
    suspend fun resolveCityByLocation(wgsLat: Double, wgsLng: Double): Result<TransitCity?>

    suspend fun getNearbyStops(
        city: TransitCity,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4
    ): Result<List<NearbyStop>>

    suspend fun getStopDetail(
        city: TransitCity,
        stopId: String? = null,
        stopName: String? = null,
        physicalStId: String? = null,
        namesakeStId: String? = null,
        stationLat: Double? = null,
        stationLng: Double? = null,
        userLat: Double? = null,
        userLng: Double? = null
    ): Result<List<NearbyStop>>

    /** 有结果时把关键字记进搜索历史 */
    suspend fun search(
        city: TransitCity,
        keyword: String,
        userLat: Double? = null,
        userLng: Double? = null
    ): Result<TransitSearchResult>

    suspend fun getLineDetail(
        city: TransitCity,
        lineId: String,
        targetOrder: Int? = null,
        stationId: String? = null,
        wgsLat: Double? = null,
        wgsLng: Double? = null,
        source: DataSourceType = if (MygolbsIds.isMygolbsId(lineId)) DataSourceType.MYGOLBS else DataSourceType.CHELAILE,
        lineNameHint: String = "",
        directionHint: Int = 0
    ): Result<BusLineDetail>

    suspend fun getTimetable(
        city: TransitCity,
        line: BusLineDirection,
        atTimeHHMM: String? = null
    ): Result<LineTimetable>

    /**
     * 站点接口是否直接带回车辆明细（含全线站序）。
     * 掌上公交只给「x 站」文字，一起等要按线路补查。默认 true，Fake 不用改。
     */
    fun stopDetailIncludesBuses(source: DataSourceType): Boolean = true

    /** 收藏的每条线各查一次线路详情（命中 6 秒合并缓存），返回 收藏 id -> 站点 */
    suspend fun refreshFavoritesRealtime(): Result<Map<String, NearbyStop>>

    /**
     * 每一份真正从上游拿到的线路详情（命中缓存的不算）。追踪服务等下一轮时订阅它：
     * 页面刚查过同一条线、同一个候车站，就直接拿这份刷新追踪和通知。默认没有（Fake 不用改）。
     */
    val lineDetailUpdates: Flow<LineDetailUpdate> get() = emptyFlow()
}

/** [RealtimeRepository.lineDetailUpdates] 的一项：哪个数据源、哪条线、按哪个候车站查的 */
data class LineDetailUpdate(
    val source: DataSourceType,
    val lineId: String,
    val targetOrder: Int?,
    val detail: BusLineDetail
)

/**
 * 手机定位（WGS-84），都带精度和单调时刻（[UserFix]）。实现经 `LocationSource`（Android / iOS 各一份）。
 */
interface LocationRepository {

    /** 最近一次拿到的定位（[bestFix] 的结果、[fixes] 收到的每个定位） */
    val latest: StateFlow<UserFix?>

    /**
     * 首页取一次定位：lastKnown 够新（默认 60 秒，[forceFresh] 时 10 秒）且精度 ≤100 m 就直接用；
     * 否则在 4.5 秒窗口内监听，拿到 ≤100 m 的提前结束，窗口结束取最准的；
     * 什么都没拿到时退回 10 分钟内最新的旧定位。没有定位权限时返回 null。
     */
    suspend fun bestFix(forceFresh: Boolean): UserFix?

    /** 系统缓存的定位里 [maxAgeMillis] 内、精度最高的一个（路线规划「我的位置」兜底）；没有权限或没有时返回 null */
    fun lastKnown(maxAgeMillis: Long): UserFix?

    /** 按档位持续定位（追踪用），收到的每个定位都会更新 [latest] */
    fun fixes(tier: LocationCadence.Tier): Flow<UserFix>

    /** 有没有任一定位权限（精确或模糊）。[bestFix] 取不到定位时用它区分「没授权」和「没定位」 */
    fun hasPermission(): Boolean

    /**
     * 系统「位置信息」总开关是否打开。
     *
     * 权限给了但总开关关着时，系统里所有定位来源都不出数据，[bestFix] 必然返回 null——
     * 提示要能说清是哪一层没开（`BusHomeViewModel` 的 `LocationIssue`）。
     */
    fun isSystemLocationEnabled(): Boolean
}

/**
 * 高德输入提示 / 公交算路通道。
 *
 * Android 实现（现在在 `FavoritesGroupViewModel` 里）：SDK 优先，**只有 SDK 调用失败**才回落 Web 服务；
 * SDK 成功但结果为空时不再重复请求 Web。iOS 只走 Web。单元测试可注入替身，避免发起真实请求。
 */
interface AmapRouteGateway {
    /** 是否至少配置了一条可用通道（Android Key 或 Web 服务 Key） */
    fun isConfigured(): Boolean

    /** @param location 当前位置 "lng,lat"（GCJ-02），可为空 */
    suspend fun searchTips(keyword: String, cityName: String, location: String?): Result<List<AmapTipItem>>

    /** @param cityd 跨城时的终点城市 adcode，同城传 null */
    suspend fun planTransit(
        originLngLat: String,
        destinationLngLat: String,
        originName: String,
        destName: String,
        cityName: String,
        cityd: String?
    ): Result<List<AmapTransitSchemeResult>>
}

/**
 * 路线规划：高德输入提示（LRU 64）、公交算路（LRU 16，键 = 城市 + 6 位小数坐标 + 15 分钟时间桶）、
 * 方案与实时线路对齐（命中备忘 24 小时 / 没命中 10 分钟，并发 4），后面是 [AmapRouteGateway]。
 *
 * 第 17 步从 `FavoritesGroupViewModel` 搬来（实现 `DefaultRoutePlanRepository`）；防抖、取消上一次、
 * 「我的位置」解析留在规划页的 ViewModel（重构计划 §2.5、§2.10）。都不抛异常，查不到就是空列表。
 */
interface RoutePlanRepository {
    /** 是否至少配置了一条高德通道（Android Key 或 Web Key） */
    fun isConfigured(): Boolean

    /**
     * 输入提示：先查 LRU，再走高德，都没有结果时降级为本地站点搜索；非空才缓存。
     * @param location 当前位置 "lng,lat"（GCJ-02），可为空
     */
    suspend fun searchTips(keyword: String, city: TransitCity, location: String?): List<AmapTipItem>

    /**
     * 补全缺少经纬度的起 / 终点：[useAmap] 时直接问高德（不经输入提示缓存），再查本地站点；都没有就原样返回。
     */
    suspend fun resolveLocation(tip: AmapTipItem, city: TransitCity, location: String?, useAmap: Boolean): AmapTipItem

    /**
     * 公交算路：缓存 → [useAmap] 时高德算路并与实时源对齐 → 本地直达 / 一次换乘兜底 → 注入起终点坐标；非空才缓存。
     */
    suspend fun planTransit(
        origin: AmapTipItem,
        destination: AmapTipItem,
        city: TransitCity,
        useAmap: Boolean
    ): List<AmapTransitSchemeResult>
}
