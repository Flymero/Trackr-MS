package com.trackr.core.network.remote.meishan.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MeishanPushClientTest {
    private class TestClock : Clock {
        private var nowInstant = Instant.parse("2026-11-05T10:00:00Z")

        override fun now(): Instant = nowInstant

        fun advanceBy(millis: Long) {
            nowInstant += millis.milliseconds
        }
    }

    internal class FakePushSocket : MeishanPushSocket {
        private var channel = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        private val sentLock = Any()
        var connectFailures = 0
        var autoAck = true
        var failHeartbeat = false
        val readTimeouts = mutableListOf<Long>()
        @Volatile var closed = false

        override suspend fun connect(host: String, port: Int, timeoutMs: Long) {
            if (connectFailures > 0) {
                connectFailures--
                throw IOException("connect fail")
            }
            closed = false
            channel = Channel(Channel.UNLIMITED)
        }

        override suspend fun read(): String? = channel.receiveCatching().getOrNull()

        override suspend fun write(text: String) {
            if (failHeartbeat && text.contains("\"msgType\":1")) throw IOException("heartbeat failed")
            synchronized(sentLock) { sent += text }
            if (autoAck && text.contains("\"msgType\":3")) {
                channel.trySend("""{"msgType":4,"sourceType":0,"clientId":"server"}""")
            }
        }

        override fun setReadTimeoutMs(ms: Long) { readTimeouts.add(ms) }
        override fun close() {
            closed = true
            channel.close()
        }
        fun serverSend(text: String) { channel.trySend(text) }
        fun simulateDisconnect() = close()
    }

    private fun serverPush(tag: String, pushTime: String, plate: String = "川Z10091D"): String {
        val content = buildJsonObject {
            put("tag", JsonPrimitive(tag))
            put("pushTime", JsonPrimitive(pushTime))
            put("busInfos", buildJsonArray {
                add(buildJsonObject {
                    put("b_id", JsonPrimitive(0))
                    put("l_copy_id", JsonPrimitive(11))
                    put("b_plate_num", JsonPrimitive(plate))
                    put("index", JsonPrimitive(2))
                    put("state", JsonPrimitive(0))
                    put("isArriveSite", JsonPrimitive(0))
                    put("b_latitude", JsonPrimitive(30.065))
                    put("b_longitude", JsonPrimitive(103.825))
                    put("b_sd", JsonPrimitive(25.0))
                    put("areaId", JsonPrimitive(1))
                })
            })
        }.toString()
        return buildJsonObject {
            put("msgType", JsonPrimitive(MeishanPushProtocol.MSG_SERVER_PUSH))
            put("pushType", JsonPrimitive(1))
            put("content", JsonPrimitive(content))
        }.toString()
    }

    private fun TestScope.newClient(
        fake: FakePushSocket,
        testClock: TestClock = TestClock(),
        externalScope: CoroutineScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler)),
    ): Triple<MeishanPushClient, CoroutineScope, TestClock> =
        Triple(
            MeishanPushClient(socketFactory = { fake }, wallClock = testClock, externalScope = externalScope),
            externalScope,
            testClock,
        )

    @Test
    fun acquire_handshakesAndSubscribes() = runTest {
        val fake = FakePushSocket()
        val (client, external, _) = newClient(fake)
        try {
            client.acquire("11_0_1")
            runCurrent()
            assertEquals(MeishanPushState.ONLINE, client.state.value)
            assertEquals(2, fake.sent.size)
            assertTrue(fake.sent[0].contains("\"msgType\":3"))
            assertTrue(fake.sent[1].contains("\"msgType\":2") && fake.sent[1].contains("11_0_1"))
        } finally {
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun serverPush_emittedAndStaleFrameDiscarded() = runTest {
        val fake = FakePushSocket()
        val (client, external, _) = newClient(fake)
        val collected = mutableListOf<MeishanPushFrame>()
        val collector = CoroutineScope(Job() + StandardTestDispatcher(testScheduler)).launch {
            client.frames.collect { collected += it }
        }
        try {
            client.acquire("11_0_1")
            runCurrent()
            fake.serverSend(serverPush("11_0", "2026-11-05 17:50:00"))
            fake.serverSend(serverPush("11_0", "2026-11-05 17:59:58"))
            runCurrent()
            assertEquals(1, collected.size)
            assertEquals("11_0", collected.single().tag)
            assertEquals("川Z10091D", collected.single().busInfos.optJSONObject(0)?.optString("b_plate_num"))
        } finally {
            collector.cancel()
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun smallFutureClockSkew_isAccepted() = runTest {
        val fake = FakePushSocket()
        val (client, external, _) = newClient(fake)
        val collected = mutableListOf<MeishanPushFrame>()
        val collector = CoroutineScope(Job() + StandardTestDispatcher(testScheduler)).launch {
            client.frames.collect { collected += it }
        }
        try {
            client.acquire("11_0_1")
            runCurrent()
            // Beijing 18:00:01 is one second ahead of the test clock at 10:00:00Z.
            fake.serverSend(serverPush("11_0", "2026-11-05 18:00:01"))
            runCurrent()
            assertEquals(1, collected.size)
        } finally {
            collector.cancel()
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun clearlyFutureFrame_isDiscarded() = runTest {
        val fake = FakePushSocket()
        val (client, external, _) = newClient(fake)
        val collected = mutableListOf<MeishanPushFrame>()
        val collector = CoroutineScope(Job() + StandardTestDispatcher(testScheduler)).launch {
            client.frames.collect { collected += it }
        }
        try {
            client.acquire("11_0_1")
            runCurrent()
            // Beijing 18:00:03 is beyond the two-second skew tolerance.
            fake.serverSend(serverPush("11_0", "2026-11-05 18:00:03"))
            runCurrent()
            assertTrue(collected.isEmpty())
        } finally {
            collector.cancel()
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun msg5_rebuildsOnSameSocketWithConnectAckAndSubscribe() = runTest {
        val fake = FakePushSocket()
        val (client, external, _) = newClient(fake)
        try {
            client.acquire("11_0_1")
            runCurrent()
            fake.serverSend("""{"msgType":5}""")
            runCurrent()
            assertEquals(MeishanPushState.ONLINE, client.state.value)
            assertEquals(4, fake.sent.size)
            assertTrue(fake.sent[2].contains("\"msgType\":3"))
            assertTrue(fake.sent[3].contains("\"msgType\":2") && fake.sent[3].contains("11_0_1"))
            assertFalse(fake.closed)
        } finally {
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun heartbeat_isSentAfterWriteIdle() = runTest {
        val fake = FakePushSocket()
        val testClock = TestClock()
        val (client, external, _) = newClient(fake, testClock)
        try {
            client.acquire("11_0_1")
            runCurrent()
            testClock.advanceBy(30_000)
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(fake.sent.any { it.contains("\"msgType\":1") })
        } finally {
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun heartbeatFailure_closesBlockedReaderAndReconnects() = runTest {
        val fake = FakePushSocket().apply { failHeartbeat = true }
        val (client, external, clock) = newClient(fake)
        try {
            client.acquire("11_0_1")
            runCurrent()
            assertEquals(45_000L, fake.readTimeouts.last())
            advanceTimeBy(29_999)
            clock.advanceBy(30_000)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(fake.closed)
            assertEquals(MeishanPushState.DISCONNECTED, client.state.value)
            fake.failHeartbeat = false
            advanceTimeBy(MeishanPushClient.RECONNECT_MIN_MS)
            runCurrent()
            assertEquals(MeishanPushState.ONLINE, client.state.value)
            assertEquals(2, fake.sent.count { it.contains("\"msgType\":2") })
        } finally {
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun release_refcount_disconnectsOnlyAtZero() = runTest {
        val fake = FakePushSocket()
        val (client, external, _) = newClient(fake)
        try {
            client.acquire("11_0_1")
            client.acquire("11_0_1")
            runCurrent()
            client.release("11_0_1")
            runCurrent()
            assertEquals(MeishanPushState.ONLINE, client.state.value)
            client.release("11_0_1")
            runCurrent()
            assertEquals(MeishanPushState.IDLE, client.state.value)
            assertTrue(fake.sent.any { it.contains("\"msgType\":2") && it.contains("\"data\":\"[]\"") })
            assertTrue(fake.closed)
        } finally {
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun disconnect_reconnectsWithBackoffAndResendsSubscribe() = runTest {
        val fake = FakePushSocket()
        val (client, external, _) = newClient(fake)
        try {
            client.acquire("11_0_1")
            runCurrent()
            fake.simulateDisconnect()
            runCurrent()
            assertEquals(MeishanPushState.DISCONNECTED, client.state.value)
            advanceTimeBy(MeishanPushClient.RECONNECT_MIN_MS)
            runCurrent()
            assertEquals(MeishanPushState.ONLINE, client.state.value)
            assertEquals(4, fake.sent.size)
            assertTrue(fake.sent[3].contains("11_0_1"))
        } finally {
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun connectFailure_backsOffAndRetries() = runTest {
        val fake = FakePushSocket().apply { connectFailures = 1 }
        val (client, external, _) = newClient(fake)
        try {
            client.acquire("11_0_1")
            runCurrent()
            assertEquals(MeishanPushState.DISCONNECTED, client.state.value)
            advanceTimeBy(MeishanPushClient.RECONNECT_MIN_MS)
            runCurrent()
            assertEquals(MeishanPushState.ONLINE, client.state.value)
        } finally {
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }

    @Test
    fun cancel_doesNotCancelBorrowedExternalScope() = runTest {
        val fake = FakePushSocket()
        val external = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val client = MeishanPushClient(socketFactory = { fake }, wallClock = TestClock(), externalScope = external)
        try {
            client.acquire("11_0_1")
            runCurrent()
            client.cancel()
            runCurrent()
            assertTrue(external.coroutineContext[Job]?.isActive == true)
        } finally {
            client.cancel()
            external.cancel()
            runCurrent()
        }
    }
}
