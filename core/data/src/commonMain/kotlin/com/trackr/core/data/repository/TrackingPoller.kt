package com.trackr.core.data.repository

import com.trackr.core.domain.arrival.StopOrderResolver
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import kotlin.time.Clock

/** One generation of one session. Platforms own jobs, lifecycle, location and presentation. */
class TrackingPoller(
    private val tracking: TrackingRepository,
    private val realtime: RealtimeRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val monotonicClock: MonotonicClock,
    private val locationEnabled: () -> Boolean,
    private val serviceFix: () -> UserFix?,
    private val onEffect: (TrackingEngine.Effect, String) -> Unit,
    private val onExpired: (TrackingSession) -> Unit = {},
    private val requestDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val watchPoller = WatchTogetherPoller(realtime)

    /** Cancellation and generation checks also reject a provider that returns after cancellation. */
    suspend fun run(sessionId: String, generation: Int, onUpdate: () -> Unit = {}) {
        var failures = 0
        var operating = LineOperatingState.RUNNING
        /** 等下一轮时别处（线路页、收藏卡）刚拿到的同一份线路数据，这一轮直接用，不再请求 */
        var shared: LineDetailUpdate? = null
        fun current() = tracking.sessionById(sessionId)?.takeIf {
            it.generation == generation && !it.interrupted && it.state.isActive
        }
        while (currentCoroutineContext().isActive) {
            val session = current() ?: return
            if (TrackingEngine.isTrackingExpired(session, clock.now().toEpochMilliseconds())) {
                onExpired(session)
                tracking.stop(sessionId)
                return
            }
            val target = session.watchTarget
            val plan = TrackingEngine.watchPollPlan(session)
            val result: Result<(TrackingSession) -> TrackingEngine.Result> = try {
                if (target != null && plan != null) {
                    withContext(requestDispatcher) { watchPoller.poll(target, plan, cityFor(target.cityId, target.mygolbsCityCode, target.cityName)) }.map { poll ->
                        currentCoroutineContext().ensureActive()
                        if (current() != null) tracking.recordLineBuses(sessionId, generation, poll.lines.mapValues { it.value.buses })
                        operating = LineOperatingState.RUNNING
                        val reduce: (TrackingSession) -> TrackingEngine.Result = { latest ->
                            TrackingEngine.reduceWatchPoll(latest, poll, locationEnabled(), serviceFix(),
                                clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value, tracking.latestUserFix)
                        }
                        reduce
                    }
                } else {
                    val leg = session.leg
                    val state = session.state
                    val sharedDetail = shared?.takeIf { matchesRequest(it, session) }?.detail
                    shared = null
                    val fetched = if (sharedDetail != null) Result.success(sharedDetail) else withContext(requestDispatcher) { realtime.getLineDetail(
                        city = cityFor(leg.cityId, leg.mygolbsCityCode), lineId = leg.lineId,
                        targetOrder = requestOrder(session),
                        stationId = state.targetStopId.ifBlank { leg.targetStopId }, source = leg.source,
                        lineNameHint = state.lineName, directionHint = leg.direction
                    ) }
                    // 站序是错推的（issue #41「900 站」）：按站名找回真站序，改好会话后马上按新站序重查
                    val corrected = fetched.getOrNull()?.let { correctedTargetOrder(session, it) }
                    if (corrected != null) {
                        currentCoroutineContext().ensureActive()
                        val applied = tracking.update(sessionId, generation) { latest -> latest.reanchoredTo(corrected, fetched.getOrNull()?.stops?.maxOfOrNull { it.order } ?: corrected) }
                        if (!applied) return
                        continue
                    }
                    fetched.map { detail ->
                        currentCoroutineContext().ensureActive()
                        if (current() != null) tracking.recordBuses(sessionId, generation, detail.buses)
                        operating = detail.operatingState
                        val reduce: (TrackingSession) -> TrackingEngine.Result = { latest ->
                            val reduced = TrackingEngine.reducePoll(latest, detail, locationEnabled(), serviceFix(), tracking.latestUserFix,
                                clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value)
                            reduced.copy(session = reduced.session.withTargetStopCoords(detail))
                        }
                        reduce
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            currentCoroutineContext().ensureActive()
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (current() == null) return
            var effects: List<TrackingEngine.Effect> = emptyList()
            val applied = tracking.update(sessionId, generation) { latest ->
                result.fold(onSuccess = { reduce ->
                    failures = 0
                    val reduced = reduce(latest)
                    effects = reduced.effects
                    if (effects.any { it is TrackingEngine.Effect.Finished }) null else reduced.session.copy(lastRealtimeAtMillis = clock.now().toEpochMilliseconds(), refreshFailed = false)
                }, onFailure = {
                    failures++
                    TrackingEngine.reduceFailure(latest, clock.now().toEpochMilliseconds()).copy(refreshFailed = true)
                })
            }
            if (!applied) return
            if (effects.any { it is TrackingEngine.Effect.Finished.Boarded } && settings.autoStopAfterBoarding.value) {
                tracking.sessions.value
                    .filter { sameStopWaiting(session, it) }
                    .forEach { tracking.stop(it.sessionId) }
            }
            effects.forEach { onEffect(it, sessionId) }
            onUpdate()
            val next = current() ?: return
            val interval = TrackingEngine.pollingIntervalMillis(next.state, failures, operating)
            shared = if (next.watchTarget == null) awaitSharedDetail(next, interval) else {
                delay(interval)
                null
            }
        }
    }

    /** 这一轮按哪一站请求：等车时是上车站，其余是目标站 */
    private fun requestOrder(session: TrackingSession): Int {
        val state = session.state
        return when {
            state.legPhase == LegPhase.WAITING_BOARD && state.boardStopOrder > 0 -> state.boardStopOrder
            state.targetOrder > 0 -> state.targetOrder
            else -> session.leg.targetOrder
        }
    }

    private fun matchesRequest(update: LineDetailUpdate, session: TrackingSession): Boolean =
        update.source == session.leg.source &&
            update.lineId == session.leg.lineId &&
            update.targetOrder == requestOrder(session)

    /**
     * 等下一轮（最多 [interval]）。等的时候别处请求了同一条线、同一个候车站（线路页 10 秒一轮、收藏卡 15 秒一轮），
     * 就提前用那一份刷新：通知和页面看到的是同一份数据，也不多打请求（issue #41「通知和应用内数据不一致」）。
     * 离上一轮不到 [MIN_SHARED_GAP_MS] 的不要，免得几个页面一起刷时通知跳得太勤。
     */
    private suspend fun awaitSharedDetail(session: TrackingSession, interval: Long): LineDetailUpdate? {
        val startedAt = monotonicClock.elapsedMillis()
        return withTimeoutOrNull(interval) {
            realtime.lineDetailUpdates.firstOrNull {
                matchesRequest(it, session) && monotonicClock.elapsedMillis() - startedAt >= MIN_SHARED_GAP_MS
            } ?: awaitCancellation() // 没有可共享的数据（流结束了）：照常等满这一轮
        }
    }

    /** 单线路的目标站序对不上站表时，按站名找到的真站序；不用改时为 null */
    private fun correctedTargetOrder(session: TrackingSession, detail: BusLineDetail): Int? {
        val state = session.state
        if (state.legPhase != LegPhase.SINGLE) return null
        return StopOrderResolver.correctedOrder(
            stops = detail.stops,
            order = state.targetOrder,
            stopName = state.targetStopName,
            lat = state.targetStopLat,
            lng = state.targetStopLng,
            nameMismatchIsSuspect = session.leg.source == DataSourceType.MYGOLBS
        )
    }

    private fun TrackingSession.reanchoredTo(order: Int, lastOrder: Int): TrackingSession = copy(
        state = state.copy(
            targetOrder = order,
            // 原来的总站数是按错站序记的（单线路默认等于目标站序），超出站表就按新站序记
            totalStops = state.totalStops.takeIf { it in order..lastOrder } ?: order,
            startTrackingOrder = (order - 1).coerceAtLeast(1),
            currentBusId = null,
            stopsRemaining = -1,
            etaSeconds = -1,
            isBusArrivedAtStop = false,
            hasTriggeredApproachingAlert = false,
            hasTriggeredArrivedAlert = false
        )
    )

    /** 同站同时追多条候车线时，确认上了其中一趟并离开该站后，同站其余候车单线路/一起等一并结束 */
    private fun sameStopWaiting(finished: TrackingSession, other: TrackingSession): Boolean {
        if (other.sessionId == finished.sessionId || other.interrupted || !other.state.isActive) return false
        val isWaitingAtStop = other.state.legPhase == LegPhase.SINGLE ||
            (other.watchTarget != null && other.state.legPhase == LegPhase.WAITING_BOARD)
        if (!isWaitingAtStop) return false
        val finishedCity = finished.state.cityId.ifBlank { finished.leg.cityId }
        val otherCity = other.state.cityId.ifBlank { other.leg.cityId }
        if (finishedCity.isNotBlank() && otherCity.isNotBlank() && finishedCity != otherCity) return false
        val finishedStopId = finished.state.targetStopId.ifBlank { finished.leg.targetStopId }
        val otherStopId = other.state.targetStopId.ifBlank { other.leg.targetStopId }
        val finishedStopName = finished.state.boardStopName.ifBlank { finished.state.targetStopName }
        val otherStopName = other.state.boardStopName.ifBlank { other.state.targetStopName }
        // 掌上公交线路详情的站 id 就是站序，不同站也可能相同：站名不同一定不是同一站
        if (finishedStopName.isNotBlank() && otherStopName.isNotBlank() && finishedStopName != otherStopName) return false
        if (finishedStopId.isNotBlank() && otherStopId.isNotBlank() && finishedStopId == otherStopId) return true
        // 同名不同 id：可能是同一站（两个接口给的 id 不一样），也可能是对向站，看坐标；不知道坐标就不结束
        val (finishedLat, finishedLng) = finished.waitingStopCoords()
        val (otherLat, otherLng) = other.waitingStopCoords()
        val distance = RouteProjectionMath.calculateDistanceMeters(finishedLat, finishedLng, otherLat, otherLng)
        return distance != null && distance <= SAME_STOP_MAX_METERS
    }

    private fun TrackingSession.waitingStopCoords(): Pair<Double, Double> {
        val watch = watchTarget
        return if (watch != null && watch.stopLat > 0.0 && watch.stopLng > 0.0) watch.stopLat to watch.stopLng
        else state.targetStopLat to state.targetStopLng
    }

    private fun TrackingSession.withTargetStopCoords(detail: BusLineDetail): TrackingSession {
        if (state.legPhase != LegPhase.SINGLE) return this
        val stop = detail.stops.firstOrNull { it.order == state.targetOrder } ?: return this
        if (stop.wgsLat == state.targetStopLat && stop.wgsLng == state.targetStopLng) return this
        return copy(state = state.copy(targetStopLat = stop.wgsLat, targetStopLng = stop.wgsLng))
    }

    private fun cityFor(id: String, code: String, name: String = ""): TransitCity =
        settings.selectedCity.value.takeIf { it.cityId == id }
            ?: TransitCity(cityId = id, cityName = name, mygolbsCityCode = code.takeIf { it.isNotEmpty() })

    private companion object {
        /** 同名站相距不超过这么远才算同一站；对向站一般隔着马路，比这远 */
        const val SAME_STOP_MAX_METERS = 30

        /** 用别处数据提前刷新时，离上一轮至少隔这么久 */
        const val MIN_SHARED_GAP_MS = 5_000L
    }
}
