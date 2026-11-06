package com.trackr.core.data.repository

import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.model.BusLineDetail
import com.trackr.core.network.provider.meishan.MeishanTransitProvider
import com.trackr.core.network.remote.meishan.MeishanIds
import com.trackr.core.network.remote.meishan.push.MeishanPushClientApi
import com.trackr.core.network.remote.meishan.push.MeishanPushFrame
import com.trackr.core.network.remote.meishan.push.MeishanPushState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TCP 推送对线路详情的可用性。不可用时调用方继续 HTTP 轮询。 */
enum class MeishanPushAvailability {
    ONLINE,
    DISCONNECTED
}

data class MeishanPushUpdate(
    val availability: MeishanPushAvailability,
    val detail: BusLineDetail? = null
)

data class MeishanPushTarget(
    val order: Int?,
    val stationId: String?
)

/**
 * 眉山线路详情的实时推送桥接。
 * 原始 TCP 帧和 JSONArray 只停留在 network/provider 层，feature 只收到领域详情。
 */
interface MeishanPushRepository {
    fun stream(
        base: BusLineDetail,
        targetOrder: Int? = null,
        stationId: String? = null
    ): Flow<MeishanPushUpdate>

    fun stream(
        base: BusLineDetail,
        target: StateFlow<MeishanPushTarget>
    ): Flow<MeishanPushUpdate> = stream(
        base = base,
        targetOrder = target.value.order,
        stationId = target.value.stationId
    )
}

private sealed interface MeishanPushEvent {
    data class State(val value: MeishanPushState) : MeishanPushEvent
    data class Frame(val value: MeishanPushFrame) : MeishanPushEvent
}

class DefaultMeishanPushRepository(
    private val client: MeishanPushClientApi,
    private val provider: MeishanTransitProvider,
    private val monotonicClock: MonotonicClock = ProcessMonotonicClock,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) : MeishanPushRepository {

    init {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
    }

    override fun stream(
        base: BusLineDetail,
        targetOrder: Int?,
        stationId: String?
    ): Flow<MeishanPushUpdate> = stream(
        base = base,
        target = MutableStateFlow(MeishanPushTarget(targetOrder, stationId))
    )

    override fun stream(
        base: BusLineDetail,
        target: StateFlow<MeishanPushTarget>
    ): Flow<MeishanPushUpdate> = channelFlow {
        val tag = try {
            provider.pushTag(base.line.lineId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        if (tag == null) {
            trySend(MeishanPushUpdate(MeishanPushAvailability.DISCONNECTED))
            return@channelFlow
        }
        val expectedTag = tag.substringBeforeLast('_')
        if (expectedTag.isBlank() || expectedTag == tag) {
            trySend(MeishanPushUpdate(MeishanPushAvailability.DISCONNECTED))
            return@channelFlow
        }

        val areaId = MeishanIds.parse(base.line.lineId)?.areaId ?: return@channelFlow
        trySend(MeishanPushUpdate(MeishanPushAvailability.DISCONNECTED))
        val events = Channel<MeishanPushEvent>(Channel.BUFFERED)
        val replayFrames = client.frames.replayCache
        val stateJob = launch(start = CoroutineStart.UNDISPATCHED) {
            var initial = true
            client.state.collect { state ->
                // StateFlow's immediate value describes the previous subscription.
                if (initial) {
                    initial = false
                } else {
                    events.send(MeishanPushEvent.State(state))
                }
            }
        }
        val frameJob = launch(start = CoroutineStart.UNDISPATCHED) {
            client.frames.collect { frame ->
                // A replayed frame is never a new business frame. Referential comparison
                // keeps a newly emitted, equal-valued frame valid.
                if (replayFrames.any { it === frame }) return@collect
                events.send(MeishanPushEvent.Frame(frame))
            }
        }

        var acquired = false
        try {
            // Collectors are already attached, so a synchronous first frame from acquire
            // cannot be missed. Processing starts only after acquire and drains queued events.
            client.acquire(tag)
            acquired = true
            var businessOnline = false
            var lastValidAt = monotonicClock.elapsedMillis()
            while (true) {
                val elapsed = (monotonicClock.elapsedMillis() - lastValidAt).coerceAtLeast(0L)
                val waitMillis = (timeoutMillis - elapsed).coerceAtLeast(1L)
                val event = kotlinx.coroutines.withTimeoutOrNull(waitMillis) { events.receive() }
                if (event == null) {
                    if (businessOnline) {
                        businessOnline = false
                        trySend(MeishanPushUpdate(MeishanPushAvailability.DISCONNECTED))
                    }
                    lastValidAt = monotonicClock.elapsedMillis()
                    continue
                }
                when (event) {
                    is MeishanPushEvent.State -> if (event.value != MeishanPushState.ONLINE) {
                        if (businessOnline) businessOnline = false
                        trySend(MeishanPushUpdate(MeishanPushAvailability.DISCONNECTED))
                    }
                    is MeishanPushEvent.Frame -> {
                        if (event.value.tag != expectedTag || client.state.value != MeishanPushState.ONLINE) continue
                        if ((0 until event.value.busInfos.length()).any { index ->
                            event.value.busInfos.optJSONObject(index)?.optInt("areaId", areaId) != areaId
                        }) continue
                        val detail = try {
                            provider.applyPushFrame(
                                base = base,
                                frame = event.value,
                                targetOrder = target.value.order,
                                stationId = target.value.stationId,
                                expectedTag = expectedTag
                            )
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            null
                        }
                        if (detail != null) {
                            lastValidAt = monotonicClock.elapsedMillis()
                            businessOnline = true
                            trySend(MeishanPushUpdate(MeishanPushAvailability.ONLINE, detail))
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                stateJob.cancelAndJoin()
                frameJob.cancelAndJoin()
                if (acquired) client.release(tag)
            }
            events.close()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}
