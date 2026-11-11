package com.trackr.core.data.repository

import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.domain.tracking.WatchPollPlan
import com.trackr.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.selects.select
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
    private val requestDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Online TCP can ignore shared HTTP snapshots; offline hooks may reuse them without another request. */
    private val singleLineFetch: (suspend (
        sessionId: String, generation: Int, city: TransitCity,
        leg: TrackingLegParams, state: TrackingSessionState, sharedDetail: BusLineDetail?,
        onOperating: (LineOperatingState) -> Unit
    ) -> Result<(TrackingSession) -> TrackingEngine.Result>)? = null,
    private val watchFetch: (suspend (
        sessionId: String, generation: Int, target: TrackingTarget.WatchTogether, plan: WatchPollPlan,
        onOperating: (LineOperatingState) -> Unit
    ) -> Result<(TrackingSession) -> TrackingEngine.Result>)? = null,
    /** Platform wakeups and shared-data wakeups are awaited together, not substituted for each other. */
    private val roundWait: (suspend (sessionId: String, millis: Long) -> Unit)? = null
) {
    private val watchPoller = WatchTogetherPoller(realtime)

    suspend fun run(sessionId: String, generation: Int, onUpdate: () -> Unit = {}) {
        var failures = 0
        var operating = LineOperatingState.RUNNING
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
                    watchFetch?.invoke(sessionId, generation, target, plan) { operating = LineOperatingState.RUNNING }
                        ?: upstreamWatchFetch(sessionId, generation, target, plan) { operating = LineOperatingState.RUNNING }
                } else {
                    val detail = shared?.takeIf { matchesRequest(it, session) }?.detail
                    shared = null
                    singleLineFetch?.invoke(sessionId, generation, cityFor(session.leg.cityId, session.leg.mygolbsCityCode),
                        session.leg, session.state, detail) { operating = it }
                        ?: upstreamSingleLineFetch(sessionId, generation, session, detail) { operating = it }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            currentCoroutineContext().ensureActive()
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (current() == null) return
            val applied = tracking.applyTrackingResult(sessionId, generation, settings.autoStopAfterBoarding.value) { latest ->
                result.fold(onSuccess = { reduce ->
                    failures = 0
                    val reduced = reduce(latest)
                    reduced.copy(session = reduced.session.copy(lastRealtimeAtMillis = clock.now().toEpochMilliseconds(), refreshFailed = false))
                }, onFailure = {
                    failures++
                    TrackingEngine.Result(TrackingEngine.reduceFailure(latest, clock.now().toEpochMilliseconds()).copy(refreshFailed = true))
                })
            }
            if (!applied.applied) return
            applied.effects.forEach { onEffect(it, sessionId) }
            onUpdate()
            val next = current() ?: return
            if (session.state.legPhase == LegPhase.SINGLE && next.state.targetOrder != session.state.targetOrder) continue
            val interval = TrackingEngine.pollingIntervalMillis(next.state, failures, operating)
            shared = awaitNextRound(next, interval)
        }
    }

    private suspend fun upstreamWatchFetch(
        sessionId: String, generation: Int, target: TrackingTarget.WatchTogether, plan: WatchPollPlan,
        onOperating: (LineOperatingState) -> Unit
    ): Result<(TrackingSession) -> TrackingEngine.Result> =
        withContext(requestDispatcher) { watchPoller.poll(target, plan, cityFor(target.cityId, target.mygolbsCityCode, target.cityName)) }.map { poll ->
            currentCoroutineContext().ensureActive()
            if (isCurrent(sessionId, generation)) tracking.recordLineBuses(sessionId, generation, poll.lines.mapValues { it.value.buses })
            onOperating(LineOperatingState.RUNNING)
            val reduce: (TrackingSession) -> TrackingEngine.Result = { latest ->
                TrackingEngine.reduceWatchPoll(latest, poll, locationEnabled(), serviceFix(),
                    clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value, tracking.latestUserFix)
            }
            reduce
        }

    private suspend fun upstreamSingleLineFetch(
        sessionId: String, generation: Int, session: TrackingSession, sharedDetail: BusLineDetail?,
        onOperating: (LineOperatingState) -> Unit
    ): Result<(TrackingSession) -> TrackingEngine.Result> {
        val fetched = if (sharedDetail != null) Result.success(sharedDetail) else withContext(requestDispatcher) {
            realtime.getLineDetail(city = cityFor(session.leg.cityId, session.leg.mygolbsCityCode), lineId = session.leg.lineId,
                targetOrder = requestOrder(session), stationId = session.state.targetStopId.ifBlank { session.leg.targetStopId },
                source = session.leg.source, lineNameHint = session.state.lineName, directionHint = session.leg.direction)
        }
        return fetched.map { detail ->
            currentCoroutineContext().ensureActive()
            if (isCurrent(sessionId, generation)) tracking.recordBuses(sessionId, generation, detail.buses)
            onOperating(detail.operatingState)
            val reduce: (TrackingSession) -> TrackingEngine.Result = { latest ->
                reduceTrackingLine(latest, detail, locationEnabled(), serviceFix(), tracking.latestUserFix,
                    clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value)
            }
            reduce
        }
    }

    private fun isCurrent(id: String, generation: Int): Boolean = tracking.sessionById(id)?.let {
        it.generation == generation && !it.interrupted && it.state.isActive
    } == true

    private fun requestOrder(session: TrackingSession): Int = when {
        session.state.legPhase == LegPhase.WAITING_BOARD && session.state.boardStopOrder > 0 -> session.state.boardStopOrder
        session.state.targetOrder > 0 -> session.state.targetOrder
        else -> session.leg.targetOrder
    }

    private fun matchesRequest(update: LineDetailUpdate, session: TrackingSession): Boolean =
        update.source == session.leg.source && update.lineId == session.leg.lineId && update.targetOrder == requestOrder(session) &&
            (update.cityId.isBlank() || update.cityId == session.leg.cityId) &&
            (update.stationId.isNullOrBlank() || update.stationId == session.state.targetStopId.ifBlank { session.leg.targetStopId })

    private suspend fun awaitNextRound(session: TrackingSession, interval: Long): LineDetailUpdate? = coroutineScope {
        val platform = async<LineDetailUpdate?> {
            if (roundWait != null) roundWait(session.sessionId, interval) else delay(interval)
            null
        }
        val shared = async {
            if (session.watchTarget != null) awaitCancellation()
            val started = monotonicClock.elapsedMillis()
            withTimeoutOrNull(interval) {
                realtime.lineDetailUpdates.firstOrNull {
                    matchesRequest(it, session) && monotonicClock.elapsedMillis() - started >= 5_000L
                } ?: awaitCancellation()
            }
        }
        try {
            select { shared.onAwait { it }; platform.onAwait { it } }
        } finally {
            platform.cancel()
            shared.cancel()
        }
    }

    private fun cityFor(id: String, code: String, name: String = ""): TransitCity =
        settings.selectedCity.value.takeIf { it.cityId == id }
            ?: TransitCity(cityId = id, cityName = name, mygolbsCityCode = code.takeIf { it.isNotEmpty() })
}
