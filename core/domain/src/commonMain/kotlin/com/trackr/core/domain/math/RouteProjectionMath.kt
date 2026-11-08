package com.trackr.core.domain.math

import com.trackr.core.model.BusStop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 车辆在走向条（Route Rail）上的站间正交投影插值与同名反向站匹配工具
 * 算法移植自 misakano7545/yulin-bus-activity
 */
object RouteProjectionMath {

    /**
     * 计算车辆在「上一站 prevStop (order-1) → 正在接近的站 targetStop (order)」段内的比例 [0.0f, 1.0f]。
     * - 1.0f 表示到达 targetStop
     * - 0.0f 表示刚离开 prevStop
     * - 若车辆 state == Arrived (isArrived = true)，恒返回 1.0f
     */
    fun computeSegmentProgress(
        busLat: Double,
        busLng: Double,
        isArrived: Boolean,
        prevStop: BusStop?,
        targetStop: BusStop?
    ): Float {
        if (isArrived || prevStop == null || targetStop == null) return 1.0f
        if (busLat == 0.0 || busLng == 0.0 ||
            prevStop.wgsLat == 0.0 || prevStop.wgsLng == 0.0 ||
            targetStop.wgsLat == 0.0 || targetStop.wgsLng == 0.0
        ) {
            return 1.0f
        }
        val k = cos(busLat * PI / 180.0)
        val ax = prevStop.wgsLng * k
        val ay = prevStop.wgsLat
        val bx = targetStop.wgsLng * k
        val by = targetStop.wgsLat
        val dx = bx - ax
        val dy = by - ay
        val dd = dx * dx + dy * dy
        if (dd <= 1e-12) return 1.0f

        val t = (((busLng * k - ax) * dx) + ((busLat - ay) * dy)) / dd
        return min(1.0, max(0.0, t)).toFloat()
    }

    /**
     * 剥离站名中的中英文括号附注，获取核心站名（例如 "金城商厦（市中医院）" -> "金城商厦"）
     */
    fun coreStopName(rawName: String): String {
        return rawName.replace(Regex("[（(][^）)]*[）)]"), "").trim()
    }

    /**
     * 在反向线路站表中查找与当前选中站对应的候车站：
     * 1. 同一个站 ID（双向共用站点）
     * 2. 全站名相同；多个同名时取离当前站最近的
     * 3. 剥离括号后的核心站名唯一命中
     * 都找不到返回 null，由调用方决定默认站；不兜底首站（换向后选错站比不选更糟）
     */
    fun findOppositeStop(currentStop: BusStop, reverseStops: List<BusStop>): BusStop? {
        if (reverseStops.isEmpty()) return null
        reverseStops.firstOrNull { it.stopId.isNotBlank() && it.stopId == currentStop.stopId }?.let { return it }
        val exact = reverseStops.filter { it.name == currentStop.name }
        if (exact.isNotEmpty()) return exact.nearestTo(currentStop)
        val core = coreStopName(currentStop.name)
        val coreMatches = reverseStops.filter { coreStopName(it.name) == core }
        return coreMatches.singleOrNull()
    }

    /** 多个候选时按与 [stop] 的直线距离取最近；坐标缺失的排后，全缺失时取第一个 */
    private fun List<BusStop>.nearestTo(stop: BusStop): BusStop =
        if (size == 1) first() else minBy {
            calculateDistanceMeters(stop.wgsLat, stop.wgsLng, it.wgsLat, it.wgsLng) ?: Int.MAX_VALUE
        }

    /**
     * 格式化剩余秒数为可读中文文案
     */
    fun formatEtaText(etaSeconds: Int, isArrivedAtTarget: Boolean = false): String {
        if (isArrivedAtTarget) return "已到站"
        if (etaSeconds <= 0) return "暂无预测"
        if (etaSeconds <= 60) return "即将到站"
        val mins = (etaSeconds / 60.0).roundToInt().coerceAtLeast(1)
        return "约 $mins 分钟"
    }

    /**
     * 计算两个经纬度坐标点之间的测地直线距离（米，基于 Haversine 公式）
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Int? {
        if (lat1 <= 0.0 || lng1 <= 0.0 || lat2 <= 0.0 || lng2 <= 0.0) return null
        val earthRadius = 6371000.0 // 地球平均半径（米）
        val dLat = toRadians(lat2 - lat1)
        val dLng = toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
                kotlin.math.cos(toRadians(lat1)) * kotlin.math.cos(toRadians(lat2)) *
                kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return (earthRadius * c).roundToInt()
    }

    /**
     * 格式化距离文本（小于1公里显示米，大于等于1公里显示公里）
     */
    fun formatDistanceText(meters: Int?): String {
        if (meters == null || meters <= 0) return ""
        return if (meters < 1000) {
            "${meters}米"
        } else {
            formatKilometers(meters)
        }
    }

    /**
     * 角度转弧度。和 JDK `Math.toRadians` 乘同一个常数（`Math.DEGREES_TO_RADIANS`），结果逐位相同。
     */
    fun toRadians(degrees: Double): Double = degrees * DEGREES_TO_RADIANS

    /** JDK `Math.DEGREES_TO_RADIANS` 的值 */
    private const val DEGREES_TO_RADIANS = 0.017453292519943295

    /**
     * 米数 → 「1.3公里」：保留 1 位小数，四舍五入（第二位 ≥ 5 进位）。
     * 等同于原来的 `String.format(Locale.CHINA, "%.1f公里", meters / 1000.0)`：
     * 米数是整数，`meters / 1000.0` 的十进制写法最多 3 位小数，(meters + 50) / 100 就是 HALF_UP 取整后的十分位数。
     */
    internal fun formatKilometers(meters: Int): String {
        val tenths = (meters + 50) / 100
        return "${tenths / 10}.${tenths % 10}公里"
    }

    /**
     * 路线名称智能排序（fork 增强：自然分块比较）。
     * 旧实现只认「整串 = 数字 + 路」的名字，带任何后缀（如「3路夜班」「3路(XX)」）都会掉进
     * 尾部字典序桶，把「3路夜班」排到「303路」后面。现在按自然序分块比较：数字段按数值比、
     * 其余按字典序、数字段优先于文字段、前缀短者先——「3路夜班」紧跟「3路」，而不是沉底。
     */
    fun compareLineNames(a: String, b: String): Int {
        val cleanA = a.removeSuffix(" [掌上公交]").removeSuffix("[掌上公交]")
            .removeSuffix(" [车来了]").removeSuffix("[车来了]").trim()
        val cleanB = b.removeSuffix(" [掌上公交]").removeSuffix("[掌上公交]")
            .removeSuffix(" [车来了]").removeSuffix("[车来了]").trim()
        val chunkCmp = compareNaturalChunks(cleanA, cleanB)
        if (chunkCmp != 0) return chunkCmp
        // 清洗后分块同序（如「3路」与「03路」）：基础名短的（无多余前后缀）排前面，再按原文兜底
        val lenCmp = a.length.compareTo(b.length)
        if (lenCmp != 0) return lenCmp
        return a.compareTo(b)
    }

    /** 数字段与文字段的自然序比较：数字段按数值升序，文字段按字典序，数字段优先于文字段 */
    private fun compareNaturalChunks(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val digitA = a[ia] in '0'..'9'
            val digitB = b[ib] in '0'..'9'
            when {
                digitA && digitB -> {
                    val runA = digitRun(a, ia)
                    val runB = digitRun(b, ib)
                    val numA = runA.toLongOrNull()
                    val numB = runB.toLongOrNull()
                    val cmp = when {
                        numA != null && numB != null -> numA.compareTo(numB)
                        // 超长数字串兜底按原段比较
                        else -> runA.compareTo(runB)
                    }
                    if (cmp != 0) return cmp
                    // 数值相同（如「03」与「3」）：段短的在前
                    if (runA.length != runB.length) return runA.length.compareTo(runB.length)
                    ia += runA.length
                    ib += runB.length
                }
                digitA != digitB -> return if (digitA) -1 else 1
                else -> {
                    val cmp = a[ia].compareTo(b[ib])
                    if (cmp != 0) return cmp
                    ia++
                    ib++
                }
            }
        }
        return when {
            ia < a.length -> 1  // a 是 b 的前缀：短者在前
            ib < b.length -> -1
            else -> 0
        }
    }

    private fun digitRun(s: String, start: Int): String {
        var i = start
        while (i < s.length && s[i] in '0'..'9') i++
        return s.substring(start, i)
    }
}
