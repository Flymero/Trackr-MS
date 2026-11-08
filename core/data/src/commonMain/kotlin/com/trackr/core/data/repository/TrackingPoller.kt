package com.trackr.core.data.repository

import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.domain.tracking.WatchPollPlan
import com.trackr.core.model.*
import kotlinx.coroutines.*
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
    /** 单线路取数钩子（Android 注入：推送在线时恒等 reduce 让位），onOperating 回报运营状态 */
    private val singleLineFetch: (suspend (
        sessionId: String, generation: Int, city: TransitCity,
        leg: TrackingLegParams, state: TrackingSessionState, onOperating: (LineOperatingState) -> Unit
    ) -> Result<(TrackingSession) -> TrackingEngine.Result>)? = null,
    /** 一起等取数钩子（Android 注入：官方线拆分 + HTTP 补漏 + 聚合缓冲） */
    private val watchFetch: (suspend (
        sessionId: String, generation: Int, target: TrackingTarget.WatchTogether, plan: WatchPollPlan,
        onOperating: (LineOperatingState) -> Unit
    ) -> Result<(TrackingSession) -> TrackingEngine.Result>)? = null,
    /** 轮询等待钩子（Android 注入：可被推送事件提前唤醒；非运营时段按 worker 状态拉长等待），null = 纯 delay */
    private val roundWait: (suspend (sessionId: String, millis: Long) -> Unit)? = null
) {
    private val watchPoller = WatchTogetherPoller(realtime)

    /** Cancellation and generation checks also reject a provider that returns after cancellation. */
    suspend fun run(sessionId: String, generation: Int, onUpdate: () -> Unit = {}) {
        var failures = 0
        var operating = LineOperatingState.RUNNING
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
                    (watchFetch?.invoke(sessionId, generation, target, plan) { operating = LineOperatingState.RUNNING }
                        ?: upstreamWatchFetch(sessionId, generation, target, plan) { operating = LineOperatingState.RUNNING })
                } else {
                    val leg = session.leg
                    val state = session.state
                    (singleLineFetch?.invoke(sessionId, generation, cityFor(leg.cityId, leg.mygolbsCityCode), leg, state) { operating = it }
                        ?: upstreamSingleLineFetch(sessionId, generation, leg, state) { operating = it })
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
            effects.forEach { onEffect(it, sessionId) }
            onUpdate()
            val next = current() ?: return
            val intervalMs = TrackingEngine.pollingIntervalMillis(next.state, failures, operating)
            if (roundWait != null) roundWait(sessionId, intervalMs) else delay(intervalMs)
        }
    }

    /** 上游默认的一起等取数：站点接口一轮 + 缺线补查，返回延迟 reduce */
    private suspend fun upstreamWatchFetch(
        sessionId: String, generation: Int,
        target: TrackingTarget.WatchTogether, plan: WatchPollPlan,
        onOperating: (LineOperatingState) -> Unit
    ): Result<(TrackingSession) -> TrackingEngine.Result> =
        withContext(requestDispatcher) { watchPoller.poll(target, plan, cityFor(target.cityId, target.mygolbsCityCode, target.cityName)) }.map { poll ->
            currentCoroutineContext().ensureActive()
            if (tracking.sessionById(sessionId)?.takeIf {
                    it.generation == generation && !it.interrupted && it.state.isActive
                } != null
            ) {
                tracking.recordLineBuses(sessionId, generation, poll.lines.mapValues { it.value.buses })
            }
            onOperating(LineOperatingState.RUNNING)
            val reduce: (TrackingSession) -> TrackingEngine.Result = { latest ->
                TrackingEngine.reduceWatchPoll(latest, poll, locationEnabled(), serviceFix(),
                    clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value)
            }
            reduce
        }

    /** 上游默认的单线路取数：线路详情一查，返回延迟 reduce */
    private suspend fun upstreamSingleLineFetch(
        sessionId: String, generation: Int,
        leg: TrackingLegParams, state: TrackingSessionState,
        onOperating: (LineOperatingState) -> Unit
    ): Result<(TrackingSession) -> TrackingEngine.Result> =
        withContext(requestDispatcher) { realtime.getLineDetail(
            city = cityFor(leg.cityId, leg.mygolbsCityCode), lineId = leg.lineId,
            targetOrder = when {
                state.legPhase == LegPhase.WAITING_BOARD && state.boardStopOrder > 0 -> state.boardStopOrder
                state.targetOrder > 0 -> state.targetOrder
                else -> leg.targetOrder
            },
            stationId = state.targetStopId.ifBlank { leg.targetStopId }, source = leg.source,
            lineNameHint = state.lineName, directionHint = leg.direction
        ) }.map { detail ->
            currentCoroutineContext().ensureActive()
            if (tracking.sessionById(sessionId)?.takeIf {
                    it.generation == generation && !it.interrupted && it.state.isActive
                } != null
            ) {
                tracking.recordBuses(sessionId, generation, detail.buses)
            }
            onOperating(detail.operatingState)
            val reduce: (TrackingSession) -> TrackingEngine.Result = { latest ->
                TrackingEngine.reducePoll(latest, detail, locationEnabled(), serviceFix(), tracking.latestUserFix,
                    clock.now().toEpochMilliseconds(), monotonicClock.elapsedMillis(), settings.autoStopAfterBoarding.value)
            }
            reduce
        }

    private fun cityFor(id: String, code: String, name: String = ""): TransitCity =
        settings.selectedCity.value.takeIf { it.cityId == id }
            ?: TransitCity(cityId = id, cityName = name, mygolbsCityCode = code.takeIf { it.isNotEmpty() })
}
