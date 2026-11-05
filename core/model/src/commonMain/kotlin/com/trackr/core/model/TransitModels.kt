package com.trackr.core.model

import kotlinx.serialization.Serializable

/**
 * 数据源标识：全国车来了 / 全国掌上公交 / 眉山官方（眉山特化）
 */
@Serializable
enum class DataSourceType(val displayName: String) {
    CHELAILE("车来了"),
    MYGOLBS("掌上公交"),
    MEISHAN("眉山官方")
}

/**
 * 引擎运行模式：车来了 / 掌上公交 / 眉山官方（独立单源模式）。
 * 官方源只覆盖眉山 / 仁寿，设置页只在选了这两个城市时提供该选项。
 */
@Serializable
enum class EngineMode(val displayName: String, val shortLabel: String) {
    CHELAILE("车来了", "车来了"),
    MYGOLBS("掌上公交", "掌上公交"),
    MEISHAN("官方", "官方")
}

/**
 * 城市实体（聚合车来了 cityId 与掌上公交 cityCode）
 */
@Serializable
data class TransitCity(
    val cityId: String,
    val cityName: String,
    val pinyin: String = "",
    val supportSubway: Boolean = false,
    val isHot: Boolean = false,
    val mygolbsCityCode: String? = null,
    val province: String = ""
) {
    /**
     * 城市全局唯一标识（用于 Compose 列表 key，防止仅支持单数据源时 cityId 为空导致 key 重复崩溃）
     */
    val uniqueKey: String
        get() = when {
            cityId.isNotBlank() -> "cl_${cityId}_$cityName"
            !mygolbsCityCode.isNullOrBlank() -> "mg_${mygolbsCityCode}_$cityName"
            else -> "name_$cityName"
        }

    /**
     * 判断是否为同一个城市（支持车来了/掌上公交跨数据源聚合匹配）
     */
    fun matchesCity(other: TransitCity?): Boolean {
        if (other == null) return false
        if (this === other) return true

        // 1. 若双方均具备非空的车来了 cityId，优先以严格 cityId 对比
        if (cityId.isNotBlank() && other.cityId.isNotBlank()) {
            return cityId == other.cityId
        }

        // 2. 至少一方缺失 cityId（如仅掌上公交支持的城市、或 GPS 逆地理推断出的城市对象）
        // 核心依据：城市名称必须一致（归一化对比，如 "莆田" 与 "莆田市"）
        val selfClean = ProvinceCityCatalog.cleanCityName(cityName)
        val otherClean = ProvinceCityCatalog.cleanCityName(other.cityName)
        if (selfClean.isBlank() || selfClean != otherClean) {
            return false
        }

        // 3. 若名称归一化一致，且双方均有省份信息，则省份不能冲突（防止跨省同名县区误匹配）
        val selfProv = province.ifBlank { ProvinceCityCatalog.inferProvince(cityName) }
        val otherProv = other.province.ifBlank { ProvinceCityCatalog.inferProvince(other.cityName) }
        if (selfProv.isNotBlank() && otherProv.isNotBlank() && selfProv != "其他" && otherProv != "其他") {
            return selfProv == otherProv
        }

        return true
    }
}

/**
 * 线路运营状态
 */
@Serializable
enum class LineOperatingState(val code: Int, val defaultLabel: String) {
    RUNNING(0, "运营中"),
    WAITING(-1, "等待发车"),
    SUSPENDED(-2, "临时停运"),
    ENDED(-3, "末班已过"),
    NO_REALTIME(1, "未开通实时")
}

/**
 * 公交线路方向摘要
 */
@Serializable
data class BusLineDirection(
    val lineId: String,
    val name: String,
    val lineNo: String = "",
    val direction: Int = 0,
    val startStop: String,
    val endStop: String,
    val firstTime: String = "",
    val lastTime: String = "",
    val price: String = "",
    val source: DataSourceType = DataSourceType.CHELAILE,
    val mygolbsCityCode: String = "",
    val mygolbsCityName: String = ""
)

/**
 * 聚合了正反两个方向的公交线路（用于搜索结果或列表展示）
 */
@Serializable
data class BusLineSummary(
    val name: String,
    val lineNo: String,
    val isSubway: Boolean = false,
    val directions: List<BusLineDirection>,
    val source: DataSourceType = DataSourceType.CHELAILE
) {
    val primaryDirection: BusLineDirection?
        get() = directions.firstOrNull { it.direction == 0 } ?: directions.firstOrNull()
}

/**
 * 线路上的站点实体
 */
@Serializable
data class BusStop(
    val stopId: String,
    val name: String,
    val order: Int, // 1-based 站序
    val wgsLat: Double = 0.0,
    val wgsLng: Double = 0.0,
    val physicalStId: String? = null,
    val namesakeStId: String? = null,
    val statusNote: String? = null, // 如掌上公交“响应式停靠”、“临时不停靠”
    val metroTransfers: List<String> = emptyList()
)

/**
 * 实时在途公交车实体
 * @param order 正在接近（或已停靠）的目标站点 1-based 站序
 * @param segmentProgress 车辆在 (order-1) -> order 之间的插值比例 [0.0, 1.0]；已到站时恒为 1.0
 * @param isArrived true = 已到站停靠 (state == 1)，false = 在途行驶 (state == 0)
 * @param etaSeconds 距离当前查询目标站（或终点站）的剩余秒数，<=0 表示无预测或已过站
 */
@Serializable
data class LiveBus(
    val busId: String,
    val plateOrFleetNo: String,
    val order: Int,
    val segmentProgress: Float = 1.0f,
    val isArrived: Boolean = false,
    val wgsLat: Double = 0.0,
    val wgsLng: Double = 0.0,
    val speedKmh: Double = 0.0,
    val capacityLevel: Int = 0, // 0=舒适, 1=适中, 2=拥挤, -1=未知
    val distanceToTargetMeters: Int? = null,
    val etaSeconds: Int = -1,
    val expectedArrivalTimeText: String? = null, // 如 "21:03"
    val nextStopName: String? = null,
    val stopOrders: List<Int> = emptyList(), // 有完整站表时按位置计算站数，兼容站序跳号
    val hasAuthoritativeArrival: Boolean = false // 上游明确提供停靠状态，不用 GPS 投影推断停靠
) {
    fun stopsTo(targetOrder: Int): Int {
        val targetPos = stopOrders.indexOf(targetOrder)
        val busPos = stopOrders.indexOf(order)
        return if (targetPos >= 0 && busPos >= 0) targetPos - busPos else targetOrder - order
    }

    /**
     * 过滤系统内部机器哈希（如 40 位 SHA-1）后的友好展示名称
     */
    val friendlyPlate: String
        get() = sanitizePlateOrFleetNo(plateOrFleetNo).ifBlank {
            if (isArrived) "已到站车辆" else "在途车辆"
        }

    /**
     * [segmentProgress] 是否可信：上游已到站，或车辆坐标有效。
     * 车辆坐标缺失时上游投影默认返回 1.0，只能用于 UI 展示，不能作为到站判定依据。
     */
    val hasValidProgress: Boolean
        get() = isArrived || (wgsLat != 0.0 && wgsLng != 0.0)
}

/**
 * 车牌/自编号有效性过滤与清洗：
 * 过滤掉系统内部生成的机器标识（如车来了的 32 位 MD5、40 位 SHA-1 内部 busId、UUID 等），
 * 仅保留人类可读的真实车牌（如“沪A·D8866”）或车辆自编号（如“417”、“K102”）。
 */
fun sanitizePlateOrFleetNo(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val trimmed = raw.trim()
    val hasChinese = trimmed.any { it in '\u4e00'..'\u9fa5' }
    if (hasChinese) {
        return if (trimmed.length <= 12) trimmed else ""
    }
    // 无汉字时：判定是否为长机器标识（如 40 位 SHA-1、32 位 MD5、UUID、或长数字串）
    val isMachineId = trimmed.length >= 10 || (trimmed.contains('-') && trimmed.length >= 8)
    if (isMachineId) {
        return ""
    }
    // 纯英文/数字/常见符号的自编号，控制在合理长度内
    return if (trimmed.all { it.isLetterOrDigit() || it in "·- " }) trimmed else ""
}

/**
 * 完整线路详情（含站点序列、全线车辆、反向线路信息、运营状态）
 */
@Serializable
data class BusLineDetail(
    val line: BusLineDirection,
    val reverseDirection: BusLineDirection? = null,
    val stops: List<BusStop>,
    val buses: List<LiveBus>,
    val operatingState: LineOperatingState = LineOperatingState.RUNNING,
    val statusDescription: String = "",
    val nextDepartureFromStart: String? = null,
    val targetOrder: Int = 0,
    val updatedAtMillis: Long = defaultNowMillis()
)

/**
 * 附近站点卡片中的单条途经线路实时状态
 */
@Serializable
data class NearbyStopLineStatus(
    val line: BusLineDirection,
    val targetOrder: Int,
    val targetStopId: String,
    val targetStopName: String,
    val statusText: String = "",
    val preArrivalTime: String? = null,
    val approachingBuses: List<LiveBus> = emptyList()
)

/**
 * 附近公交站实体
 */
@Serializable
data class NearbyStop(
    val stopId: String,
    val name: String,
    val distanceMeters: Int? = null,
    val wgsLat: Double = 0.0,
    val wgsLng: Double = 0.0,
    val physicalStId: String? = null,
    val namesakeStId: String? = null,
    val isSubway: Boolean = false,
    val lines: List<NearbyStopLineStatus> = emptyList(),
    val subwayLineNames: List<String> = emptyList(),
    val source: DataSourceType = DataSourceType.CHELAILE
)

/**
 * 判断是否为纯地铁站点（仅用于剔除无法提供地面实时公交追踪的独立地铁站，绝不误伤同名地面公交站）。
 *
 * 识别规则：
 * 1. 结构化标记：[NearbyStop.isSubway] 为 true。
 * 2. 车来了地铁站标记：站点名称以 "（M）" 或 "(M)" 结尾（如 "湖滨中路站（M）"）。
 * 3. 掌上公交地铁站标记：站点名称包含 "（地铁站）" 或 "(地铁站)"（如 "湖滨中路（地铁站）"）。
 *
 * 注意：不使用模糊的 "地铁" 匹配或 "endsWith(\"地铁站\")"，以防误伤名称以地铁站命名的普通地面公交站（如 "高新园地铁站" 公交停靠站）。
 */
val NearbyStop.isPureSubwayStation: Boolean
    get() {
        if (isSubway) return true
        val trimmed = name.trim()
        return trimmed.endsWith("（M）", ignoreCase = true) ||
                trimmed.endsWith("(M)", ignoreCase = true) ||
                trimmed.contains("（地铁站）") ||
                trimmed.contains("(地铁站)")
    }

/**
 * 混合搜索结果
 */
@Serializable
data class TransitSearchResult(
    val keyword: String,
    val lines: List<BusLineSummary> = emptyList(),
    val stops: List<NearbyStop> = emptyList()
)

/**
 * 发车时刻表时段分块
 */
@Serializable
data class TimetableSegment(
    val fromTime: String,
    val toTime: String,
    val intervalMinutes: Int = -1,
    val intervalTips: String = "",
    val departures: List<String> = emptyList()
)

/**
 * 始发站发车时刻表（含今日预测与昨日实际班次回落）
 */
@Serializable
data class LineTimetable(
    val lineId: String,
    val lineName: String,
    val nextDeparture: String? = null,
    val nextDepartureSource: String? = null, // "今日发车预测" | "昨日实际班次"
    val todayDepartures: List<String> = emptyList(),
    val yesterdayDepartures: List<String> = emptyList(),
    val segments: List<TimetableSegment> = emptyList(),
    val depDesc: String = ""
)

/**
 * 收藏的「线路 + 候车站」组合（用于首页通勤卡、桌面小组件、一键通知栏追踪）
 */
@Serializable
data class FavoriteStationLine(
    val id: String, // "${cityId}_${lineId}_${targetOrder}"
    val cityId: String,
    val cityName: String,
    val lineId: String,
    val lineName: String,
    val lineNo: String = "",
    val direction: Int = 0,
    val startStop: String,
    val endStop: String,
    val targetStopId: String,
    val targetStopName: String,
    val targetOrder: Int,
    val totalStops: Int = 0,
    val wgsLat: Double = 0.0,
    val wgsLng: Double = 0.0,
    val source: DataSourceType = DataSourceType.CHELAILE,
    val mygolbsCityCode: String = "",
    val addedAtMillis: Long = defaultNowMillis()
)

/**
 * 分组换乘单程的追踪阶段：
 * - [WAITING_BOARD] 等车阶段，目标是上车站（fromStopOrder），只考虑未过上车站的车
 * - [ON_BOARD] 已上车阶段，锁定具体车辆，目标是下车站（toStopOrder）
 * - [SINGLE] 单线路（非分组）追踪，保留原有“车到我所在站”语义
 */
@Serializable
enum class LegPhase {
    SINGLE,
    WAITING_BOARD,
    ON_BOARD
}

/**
 * 实时乘车/候车通知栏追踪会话状态
 */
@Serializable
data class TrackingSessionState(
    val isActive: Boolean = false,
    val cityId: String = "",
    val lineId: String = "",
    val lineName: String = "",
    val endStopName: String = "",
    val targetStopId: String = "",
    val targetStopName: String = "",
    val targetOrder: Int = 0,
    val totalStops: Int = 0,
    val startTrackingOrder: Int = 1,
    val currentBusId: String? = null,
    val currentBusOrder: Int = 0,
    val currentBusProgress: Float = 0f,
    val isBusArrivedAtStop: Boolean = false,
    val stopsRemaining: Int = -1,
    val etaSeconds: Int = -1,
    val expectedArrivalTime: String? = null,
    val alertThresholdStops: Int = 2,
    val hasTriggeredApproachingAlert: Boolean = false,
    val hasTriggeredArrivedAlert: Boolean = false,
    val groupId: String? = null,
    val groupName: String? = null,
    val currentLegIndex: Int = 0,
    val totalLegs: Int = 1,
    val statusSummary: String = "正在连接实时公交数据…",
    val updatedAtMillis: Long = defaultNowMillis(),
    /** 整次追踪开始时间；用于限制网络异常或停运时的最长追踪时长。 */
    val startedAtMillis: Long = updatedAtMillis,
    // 分组换乘两阶段追踪字段
    val legPhase: LegPhase = LegPhase.SINGLE,
    /** 上车站站序（WAITING_BOARD 阶段目标；ON_BOARD 阶段锁定车辆的 fromStopOrder） */
    val boardStopOrder: Int = 0,
    /** 上车站名称 */
    val boardStopName: String = "",
    /** 已上车后锁定的车辆 busId */
    val lockedBusId: String? = null,
    /** 锁定车辆当前是否信号丢失（从实时数据中消失） */
    val isSignalLost: Boolean = false,
    /** 单线路模式：正在追踪的车辆连续从数据里消失的轮询次数（短暂消失时保持原车） */
    val missingBusPolls: Int = 0,
    // 定位上车检测（仅分组换乘、有精确定位时使用）
    /** WAITING_BOARD：车已到上车站、正在用定位确认用户是否上了这辆车；null 表示没有待确认的车 */
    val boardingCandidateBusId: String? = null,
    /** 进入待确认的时刻（墙钟毫秒），用于超时兜底 */
    val boardingCandidateSinceMillis: Long = 0L,
    /** 连续满足“已上车”条件的轮询次数 */
    val boardingConfirmStreak: Int = 0,
    /** 连续满足“没赶上”条件的轮询次数 */
    val boardingMissStreak: Int = 0,
    /** 最近一次用于判上车的定位单调时钟时间；同一定位只计一次 */
    val boardingLastFixElapsedMillis: Long = -1L,
    /** 手动确认上车后尚未锁到车的补查次数 */
    val boardingLockAttempts: Int = 0,
    /** ON_BOARD：疑似锁错车时，离用户更近的那辆车 */
    val relockCandidateBusId: String? = null,
    /** 连续满足“应改锁 [relockCandidateBusId]”条件的轮询次数 */
    val relockStreak: Int = 0
) {
    /** 车已到上车站，正在用定位确认用户是否上车 */
    val isAwaitingBoardingConfirmation: Boolean
        get() = legPhase == LegPhase.WAITING_BOARD && boardingCandidateBusId != null
}
