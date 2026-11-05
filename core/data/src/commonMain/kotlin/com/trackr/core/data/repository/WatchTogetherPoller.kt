package com.trackr.core.data.repository

import com.trackr.core.domain.tracking.LinePoll
import com.trackr.core.domain.tracking.WatchPoll
import com.trackr.core.domain.tracking.WatchPollPlan
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.WatchLine
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 一起等的取数（重构计划 §2.5、第 15 步）：按 [WatchPollPlan] 请求，省着用服务器。
 *
 * - 等车：只请求 1 次上车站的站点接口（车来了已带该站每条线的来车），条数不影响请求数（Q8）。
 *   站点结果里缺的线才对那条线单独请求线路接口：结果里没有这条线；有这条线但既没来车也没状态文字；
 *   或数据源的站点接口不带来车（掌上公交）。单独请求最多同时 [maxConcurrent] 个，用户看不到这个限制。
 * - 待确认 / 「上了哪辆？」：只请求计划里那几条线的线路接口（Q16）。
 *
 * Provider 接口不变；线路详情照样走 [RealtimeRepository] 的 6 秒合并缓存。
 *
 * 每轮最多补查 [MAX_LINES_PER_POLL] 条线（[maxConcurrent] 条同时）；超过这个数的线按轮转取，
 * 见 [takeRotating]：列表顺序稳定不变，若每轮都取前几条，第 5 条起的线永远拿不到数据。
 */
class WatchTogetherPoller(
    private val realtime: RealtimeRepository,
    private val maxConcurrent: Int = MAX_CONCURRENT_LINE_REQUESTS
) {

    /** 轮转起点游标。poll() 可能并发调用，读写都在 [cursorLock] 里（见 [takeRotating]） */
    private val cursorLock = SynchronizedObject()
    private var cursorOffset: Int = 0

    /**
     * @param city 请求用的城市（服务按会话里的 cityId 拼）
     * @return 站点请求失败时失败；单条线失败只是这条线没数据。计划里只请求线路且全部失败时也算失败（服务按失败退避）
     */
    suspend fun poll(target: TrackingTarget.WatchTogether, plan: WatchPollPlan, city: TransitCity): Result<WatchPoll> =
        when (plan) {
            is WatchPollPlan.Stop -> pollStop(target, plan.lines, city)
            is WatchPollPlan.Lines -> {
                val requestedLines = takeRotating(plan.lines)
                val lines = fetchLines(target, requestedLines, city)
                if (lines.isEmpty() && requestedLines.isNotEmpty()) {
                    Result.failure(IllegalStateException("一起等：在站车辆所在线路的实时数据都没拿到"))
                } else {
                    Result.success(WatchPoll(requested = requestedLines.map { it.lineId }.toSet(), lines = lines))
                }
            }
        }

    private suspend fun pollStop(
        target: TrackingTarget.WatchTogether,
        lines: List<WatchLine>,
        city: TransitCity
    ): Result<WatchPoll> {
        val stops = realtime.getStopDetail(
            city = city,
            stopId = target.stopId.ifBlank { null },
            stopName = target.stopName.ifBlank { null },
            physicalStId = target.physicalStId,
            namesakeStId = target.namesakeStId,
            stationLat = target.stopLat.takeIf { it > 0.0 },
            stationLng = target.stopLng.takeIf { it > 0.0 }
        ).getOrElse { return Result.failure(it) }

        val stop = pickStop(stops, target)
        val covered = LinkedHashMap<String, LinePoll>()
        val missing = ArrayList<WatchLine>()
        for (line in lines) {
            val status = stop?.lines?.firstOrNull { it.line.lineId == line.lineId }
            if (stop != null && status != null && carriesBuses(stop, status)) {
                covered[line.lineId] = linePollOf(status, line)
            } else {
                missing += line
            }
        }
        // 站点接口通常已覆盖大部分线路；对缺线的线路补查设上限，避免
        // 一起等十几条线时每轮同时打满线路详情请求。缺线按轮转取，否则排在后面的线永远补不到。
        val fetchedLines = takeRotating(missing)
        val fetched = if (fetchedLines.isEmpty()) emptyMap() else fetchLines(target, fetchedLines, city)
        val requested = (covered.keys + fetched.keys).toSet()
        return Result.success(
            WatchPoll(
                // 未补查的缺线不标记为 requested，让状态机保留上一轮数据。
                requested = requested,
                lines = covered + fetched,
                stopLat = stop?.wgsLat ?: 0.0,
                stopLng = stop?.wgsLng ?: 0.0
            )
        )
    }

    /**
     * 从轮转游标处开始取至多 [MAX_LINES_PER_POLL] 条，并把游标推进这么多（取模）。
     *
     * 一起等的 lines 顺序来自会话、稳定不变，每轮都取前 N 条会让第 N+1 条起的线永远不刷新
     * （状态机对没请求过的线保留旧数据，待确认的车也永远清不掉）。轮转保证连续 ⌈len / N⌉ 轮后
     * 每条线都至少被请求过一次。
     *
     * 并发：offset 的「读 -> 算下一轮起点」必须在同一把锁里完成（check-then-act），
     * 否则两个并发的 poll() 会读到同一个 offset、请求同一批线。锁只包住这几次整数运算，
     * 不包网络请求。
     */
    private fun takeRotating(lines: List<WatchLine>): List<WatchLine> {
        if (lines.size <= MAX_LINES_PER_POLL) return lines
        val offset = synchronized(cursorLock) {
            val current = cursorOffset % lines.size
            cursorOffset = (current + MAX_LINES_PER_POLL) % lines.size
            current
        }
        // 从 offset 起绕一圈再取前 N 条，保持会话里的相对顺序
        return List(lines.size) { lines[(offset + it) % lines.size] }.take(MAX_LINES_PER_POLL)
    }

    /**
     * 站点接口可能返回同名的几个站台：找和目标同一个站台的；只有一个站台时就是它。
     * 有好几个站台却对不上时返回 null（不拿对向站台的数据，几条线都按缺线单独请求）。
     */
    private fun pickStop(stops: List<NearbyStop>, target: TrackingTarget.WatchTogether): NearbyStop? {
        if (stops.size == 1) return stops.single()
        val physical = target.physicalStId?.takeIf { it.isNotBlank() }
        return physical?.let { id -> stops.firstOrNull { it.physicalStId == id } }
            ?: stops.firstOrNull { target.stopId.isNotBlank() && it.stopId == target.stopId }
            ?: stops.firstOrNull().takeIf { physical == null && target.stopId.isBlank() }
    }

    /** 站点接口给的这条线能不能直接用：有来车；或没来车但有状态文字（如「等待发车」）。能不能用由数据源自己说 */
    private fun carriesBuses(stop: NearbyStop, status: NearbyStopLineStatus): Boolean {
        if (!realtime.stopDetailIncludesBuses(stop.source)) return false
        if (status.approachingBuses.isNotEmpty()) return true
        return status.statusText.isNotBlank() || !status.preArrivalTime.isNullOrBlank()
    }

    /** 站点接口的来车 `order` 按接口里这条线的站序算；和会话里记的站序不同时平移过去 */
    private fun linePollOf(status: NearbyStopLineStatus, line: WatchLine): LinePoll {
        val shift = if (status.targetOrder > 0) line.stopOrder - status.targetOrder else 0
        val buses = if (shift == 0) {
            status.approachingBuses
        } else {
            status.approachingBuses.map { bus ->
                bus.copy(
                    order = (bus.order + shift).coerceAtLeast(1),
                    stopOrders = bus.stopOrders.map { (it + shift).coerceAtLeast(1) }
                )
            }
        }
        return LinePoll(buses = buses, fromLineDetail = false, statusText = status.statusText)
    }

    /** 逐条请求线路接口，最多同时 [maxConcurrent] 个；失败的线不放进结果 */
    private suspend fun fetchLines(
        target: TrackingTarget.WatchTogether,
        lines: List<WatchLine>,
        city: TransitCity
    ): Map<String, LinePoll> = coroutineScope {
        val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))
        lines.map { line ->
            async {
                semaphore.withPermit {
                    realtime.getLineDetail(
                        city = city,
                        lineId = line.lineId,
                        targetOrder = line.stopOrder,
                        stationId = line.stopIdOnLine.ifBlank { target.stopId }.ifBlank { null },
                        source = line.source,
                        lineNameHint = line.lineName,
                        directionHint = line.direction
                    ).getOrNull()?.let { detail ->
                        line.lineId to LinePoll(
                            buses = detail.buses,
                            fromLineDetail = true,
                            statusText = detail.statusDescription,
                            stops = detail.stops
                        )
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    companion object {
        /** 缺线时单独请求线路接口的并发上限 */
        const val MAX_CONCURRENT_LINE_REQUESTS = 4

        /** 每轮最多补查的缺线数量（站点接口请求本身仍只有 1 次）。 */
        const val MAX_LINES_PER_POLL = 4
    }
}
