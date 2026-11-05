package com.trackr.core.data.repository

import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.provider.meishan.MeishanTransitProvider
import com.trackr.core.network.remote.meishan.MeishanTransitClient
import com.trackr.core.network.remote.meishan.push.MeishanPushClientApi
import com.trackr.core.network.remote.meishan.push.MeishanPushFrame
import com.trackr.core.network.remote.meishan.push.MeishanPushState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], manifest = org.robolectric.annotation.Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class MeishanPushRepositoryTest {
    private class Push : MeishanPushClientApi {
        override val state = MutableStateFlow(MeishanPushState.ONLINE)
        override val frames = MutableSharedFlow<MeishanPushFrame>(replay = 16)
        val acquired = mutableListOf<String>()
        val released = mutableListOf<String>()
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun acquire(tag: String) { acquired += tag; ready.complete(Unit) }
        override suspend fun release(tag: String) { released += tag }
    }

    private fun base() = BusLineDetail(
        line = BusLineDirection(lineId = "ms:1_1_0", name = "1路", direction = 0, startStop = "首站", endStop = "终站", source = DataSourceType.MEISHAN),
        stops = listOf(BusStop("ms-st:1", "首站", 1, 30.05, 103.82)),
        buses = emptyList()
    )

    private fun frame(tag: String = "11_0", area: Int = 1) = MeishanPushFrame(
        tag, "2026-11-05 12:00:00",
        JSONArray("""[{"index":1,"b_plate_num":"川Z12345","b_sd":20,"areaId":$area}]""")
    )

    @Test
    fun copyIdFiltering_timeoutRecovery_andCancellationRelease() = runTest {
        val http = HttpClient(MockEngine {
            respond("""{"code":101,"result":[{"l_id":1,"l_name":"1路","l_copy_id":11,"areaId":1}]}""")
        })
        val push = Push()
        val provider = MeishanTransitProvider(MeishanTransitClient(http))
        // Warm only the recorded list response; collection then uses the session cache.
        assertEquals("11_0_1", provider.pushTag("ms:1_1_0"))
        val repository = DefaultMeishanPushRepository(push, provider, object : MonotonicClock {
            override fun elapsedMillis(): Long = testScheduler.currentTime
        })
        val updates = mutableListOf<MeishanPushUpdate>()
        val job = backgroundScope.launch { repository.stream(base(), 1).collect { updates += it } }
        try {
            push.ready.await()
            runCurrent()
            assertEquals(listOf("11_0_1"), push.acquired)
            assertFalse(updates.any { it.availability == MeishanPushAvailability.ONLINE })
            push.frames.emit(frame("1_0"))
            push.frames.emit(frame(area = 2))
            runCurrent()
            assertFalse(updates.any { it.detail != null })
            push.frames.emit(frame())
            runCurrent()
            assertEquals("川Z12345", updates.last().detail?.buses?.single()?.plateOrFleetNo)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(MeishanPushAvailability.DISCONNECTED, updates.last().availability)
            push.frames.emit(frame())
            runCurrent()
            assertEquals(MeishanPushAvailability.ONLINE, updates.last().availability)
            push.state.value = MeishanPushState.DISCONNECTED
            runCurrent()
            assertEquals(MeishanPushAvailability.DISCONNECTED, updates.last().availability)
        } finally {
            job.cancelAndJoin()
            http.close()
        }
        assertEquals(push.acquired, push.released)
    }

    @Test
    fun replayDoesNotCountAsNewBusinessFrame() = runTest {
        val http = HttpClient(MockEngine {
            respond("""{"code":101,"result":[{"l_id":1,"l_name":"1路","l_copy_id":11}]}""")
        })
        val push = Push()
        push.frames.emit(frame())
        val provider = MeishanTransitProvider(MeishanTransitClient(http))
        provider.pushTag("ms:1_1_0")
        val repository = DefaultMeishanPushRepository(push, provider)
        val updates = mutableListOf<MeishanPushUpdate>()
        val job = backgroundScope.launch { repository.stream(base()).collect { updates += it } }
        try {
            push.ready.await()
            runCurrent()
            assertTrue(updates.none { it.detail != null })
            push.frames.emit(frame())
            runCurrent()
            assertTrue(updates.last().detail != null)
        } finally {
            job.cancelAndJoin()
            http.close()
        }
        assertEquals(listOf("11_0_1"), push.released)
    }
}
