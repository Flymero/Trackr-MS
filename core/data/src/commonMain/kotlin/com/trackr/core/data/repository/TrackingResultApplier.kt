package com.trackr.core.data.repository

import com.trackr.core.domain.arrival.StopOrderResolver
import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.*

/** HTTP, shared page data and TCP use the same station anchoring and result application. */
fun TrackingSession.withResolvedWaitingStop(detail: BusLineDetail): TrackingSession {
    if (state.legPhase != LegPhase.SINGLE) return this
    val source = leg.source
    val id = state.targetStopId.ifBlank { leg.targetStopId }
    val order = if (source == DataSourceType.MYGOLBS) {
        StopOrderResolver.correctedOrder(detail.stops, state.targetOrder, state.targetStopName,
            state.targetStopLat, state.targetStopLng, nameMismatchIsSuspect = true) ?: state.targetOrder
    } else {
        detail.stops.findStop(state.targetOrder, id)?.order ?: state.targetOrder
    }
    val stop = detail.stops.findStop(order, id.takeUnless { source == DataSourceType.MYGOLBS }) ?: return this
    val moved = stop.order != state.targetOrder
    val hasCoords = stop.wgsLat > 0.0 && stop.wgsLng > 0.0
    val anchored = state.copy(
        targetOrder = stop.order,
        // 站序未变（含按站序兜底命中的情形）时不改写 targetStopId：命中站可能与原目标 id 不同，
        // 改写会让 TCP 订阅/后续锚定悄悄切到别的物理站；等按 id 找回（moved）时才跟随
        targetStopId = if (moved) stop.stopId else state.targetStopId,
        targetStopLat = if (hasCoords) stop.wgsLat else state.targetStopLat,
        targetStopLng = if (hasCoords) stop.wgsLng else state.targetStopLng
    )
    return copy(state = if (!moved) anchored else anchored.copy(
        totalStops = state.totalStops.takeIf { it in stop.order..(detail.stops.maxOfOrNull { s -> s.order } ?: stop.order) } ?: stop.order,
        startTrackingOrder = (stop.order - 1).coerceAtLeast(1),
        currentBusId = null, stopsRemaining = -1, etaSeconds = -1,
        isBusArrivedAtStop = false, hasTriggeredApproachingAlert = false, hasTriggeredArrivedAlert = false,
        boardingCandidateBusId = null, boardingCandidateSinceMillis = 0L, boardingConfirmStreak = 0,
        boardingMissStreak = 0, boardingLastFixElapsedMillis = -1L, recentDepartedBusId = null, recentDepartedAtMillis = 0L
    ))
}

/** Re-query after reanchoring: ETA and authoritative arrival may still describe the old request station. */
fun reduceTrackingLine(
    session: TrackingSession,
    detail: BusLineDetail,
    locationEnabled: Boolean,
    serviceFix: UserFix?,
    dropFix: UserFix?,
    nowMillis: Long,
    nowElapsedMillis: Long,
    stopAfterBoarding: Boolean
): TrackingEngine.Result {
    val anchored = session.withResolvedWaitingStop(detail)
    if (anchored.state.targetOrder != session.state.targetOrder) return TrackingEngine.Result(anchored)
    return TrackingEngine.reducePoll(anchored, detail, locationEnabled, serviceFix, dropFix,
        nowMillis, nowElapsedMillis, stopAfterBoarding)
}

data class AppliedTrackingResult(val applied: Boolean, val effects: List<TrackingEngine.Effect>)

/** Apply under generation guard, then finish related waiting sessions outside the update lock. */
fun TrackingRepository.applyTrackingResult(
    sessionId: String,
    generation: Int,
    stopAfterBoarding: Boolean,
    reduce: (TrackingSession) -> TrackingEngine.Result
): AppliedTrackingResult {
    var result: TrackingEngine.Result? = null
    val applied = update(sessionId, generation) { current ->
        val reduced = reduce(current)
        result = reduced
        if (reduced.effects.any { it is TrackingEngine.Effect.Finished }) null else reduced.session
    }
    if (!applied) return AppliedTrackingResult(false, emptyList())
    val reduced = checkNotNull(result)
    if (stopAfterBoarding && reduced.effects.any { it is TrackingEngine.Effect.Finished.Boarded }) {
        sessions.value.filter { sameWaitingStop(reduced.session, it) }.forEach { other ->
            // A replacement arriving after the snapshot must not be stopped by this older result.
            update(other.sessionId, other.generation) { latest ->
                if (sameWaitingStop(reduced.session, latest)) null else latest
            }
        }
    }
    return AppliedTrackingResult(true, reduced.effects)
}

/** IDs from different namespaces, especially Mygolbs order IDs, are not physical station identity. */
internal fun sameWaitingStop(finished: TrackingSession, other: TrackingSession): Boolean {
    if (finished.sessionId == other.sessionId || other.interrupted || !other.state.isActive) return false
    if (other.state.legPhase != LegPhase.SINGLE &&
        !(other.watchTarget != null && other.state.legPhase == LegPhase.WAITING_BOARD)) return false
    val city = finished.state.cityId.ifBlank { finished.leg.cityId }
    val otherCity = other.state.cityId.ifBlank { other.leg.cityId }
    if (city.isBlank() || city != otherCity) return false
    val name = finished.state.boardStopName.ifBlank { finished.state.targetStopName }.trim()
    val otherName = other.state.boardStopName.ifBlank { other.state.targetStopName }.trim()
    if (name.isNotEmpty() && otherName.isNotEmpty() && name != otherName) return false
    fun TrackingSession.coords(): Pair<Double, Double> = watchTarget?.let {
        if (it.stopLat > 0.0 && it.stopLng > 0.0) it.stopLat to it.stopLng else null
    } ?: (state.targetStopLat to state.targetStopLng)
    val (lat, lng) = finished.coords()
    val (otherLat, otherLng) = other.coords()
    val distance = RouteProjectionMath.calculateDistanceMeters(lat, lng, otherLat, otherLng)
    if (distance != null) return name.isNotEmpty() && name == otherName && distance <= 30
    val id = finished.state.targetStopId.ifBlank { finished.leg.targetStopId }
    val otherId = other.state.targetStopId.ifBlank { other.leg.targetStopId }
    return finished.leg.source == other.leg.source && finished.leg.source != DataSourceType.MYGOLBS &&
        id.isNotBlank() && id == otherId
}

/** Final platform wait must not turn pending boarding back into the five-minute idle cadence. */
fun trackingRoundInterval(state: TrackingSessionState, interval: Long, operating: LineOperatingState): Long =
    maxOf(interval, TrackingEngine.pollingIntervalMillis(state, 0, operating))
