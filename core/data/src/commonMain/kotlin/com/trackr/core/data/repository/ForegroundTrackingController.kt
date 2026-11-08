package com.trackr.core.data.repository

import com.trackr.core.domain.platform.*
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.time.Clock

/** Main-thread lifecycle owner for platforms that may only poll while the app is active. */
class ForegroundTrackingController(
    private val repository: TrackingRepository,
    realtime: RealtimeRepository,
    private val settings: SettingsRepository,
    private val location: LocationSource,
    private val scope: CoroutineScope,
    private val clock: Clock,
    monotonicClock: MonotonicClock,
    onEffect: (TrackingEngine.Effect, String) -> Unit = { _, _ -> },
    onExpired: (TrackingSession) -> Unit = {},
    requestDispatcher: CoroutineDispatcher = Dispatchers.Default
) : TrackingController {
    private var active = false
    private var lifecycleJob: Job? = null
    private var fix: UserFix? = null
    private val poller = TrackingPoller(repository, realtime, settings, clock, monotonicClock,
        locationEnabled = { active && location.hasPrecisePermission() }, serviceFix = { fix },
        onEffect = onEffect, onExpired = onExpired, requestDispatcher = requestDispatcher)

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        lifecycleJob?.cancel()
        lifecycleJob = null
        fix = null
        if (!value) {
            repository.pauseAll()
            return
        }
        lifecycleJob = scope.launch {
            repository.awaitLoaded()
            expireAndResume()
            coroutineScope {
                val workers = mutableMapOf<String, Pair<Int, Job>>()
                var locationJob: Job? = null
                var tier = LocationCadence.Tier.OFF
                combine(repository.sessions, settings.autoStopAfterBoarding) { sessions, autoStop -> sessions to autoStop }
                    .collect { (sessions, autoStop) ->
                        val live = sessions.filter { !it.interrupted && it.state.isActive }
                        workers.keys.filter { id -> live.none { it.sessionId == id } }.toList().forEach {
                            workers.remove(it)?.second?.cancel()
                        }
                        live.forEach { session ->
                            val previous = workers[session.sessionId]
                            if (previous == null || previous.first != session.generation) {
                                previous?.second?.cancel()
                                fix = null
                                workers[session.sessionId] = session.generation to launch {
                                    poller.run(session.sessionId, session.generation)
                                }
                            }
                        }
                        val nextTier = live.map {
                            TrackingEngine.locationTier(it.state, location.hasPrecisePermission(), autoStop)
                        }.filter { it != LocationCadence.Tier.OFF }.minByOrNull { it.intervalMillis } ?: LocationCadence.Tier.OFF
                        if (tier != nextTier || (nextTier != LocationCadence.Tier.OFF && locationJob?.isActive != true)) {
                            locationJob?.cancel()
                            tier = nextTier
                            locationJob = if (tier == LocationCadence.Tier.OFF) null else launch {
                                location.fixes(nextTier).collect { incoming ->
                                    if (TrackingEngine.shouldAcceptServiceFix(fix, incoming)) {
                                        fix = incoming
                                        repository.updateUserFix(incoming)
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    private fun expireAndResume() {
        repository.sessions.value.filter { TrackingEngine.isTrackingExpired(it, clock.now().toEpochMilliseconds()) }
            .forEach { repository.stop(it.sessionId) }
        repository.resumeAll()
    }

    override fun resumeInterrupted(): Boolean {
        if (!active) return false
        val hadInterrupted = repository.sessions.value.any { it.interrupted }
        expireAndResume()
        return hadInterrupted && repository.sessions.value.any { !it.interrupted }
    }
    override fun start(target: TrackingTarget) = active && repository.start(target) != null
    override fun stopTracking(sessionId: String?) = repository.stop(sessionId)
    override fun confirmBoarded(sessionId: String?) = repository.confirmBoarded(sessionId)
    override fun chooseBoardedBus(busId: String?, sessionId: String?) = repository.chooseBoardedBus(busId, sessionId)
    override fun removeWatchLine(sessionId: String, lineId: String) = repository.removeWatchLine(sessionId, lineId)
    override fun switchToNextLeg(sessionId: String?) = repository.switchToNextLeg(sessionId)
    override fun cycleThreshold(sessionId: String?) = repository.cycleThreshold(sessionId)
    override fun updateUserFix(fix: UserFix) { if (active) repository.updateUserFix(fix) }
    fun close() = setActive(false)
}
