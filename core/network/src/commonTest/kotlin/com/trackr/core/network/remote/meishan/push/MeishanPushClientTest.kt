package com.trackr.core.network.remote.meishan.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * 推送客户端状态机（doc/news 04）：握手 / 订阅 / 重连补发 / 引用计数断开 / 时效过滤。
 *
 * 测试要点：客户端的 runForever 是无限循环（含 5 秒一跳的心跳），**不能用
 * advanceUntilIdle**——它会把虚拟时间无限推进（心跳永远有下一个排期任务），
 * 也不能挂进 runTest 主作用域 / backgroundScope（本版本 coroutines-test 不驱动
 * backgroundScope）。所以：独立 Job + 同一测试调度器，用 [step]（runCurrent，
 * 不推时间）驱动状态机，只在需要退避语义时用 advanceTimeBy。
 */
class MeishanPushClientTest {

    /** 北京时间 18:00（10:00Z） */
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-11-05T10:00:00Z")
    }

    internal class FakePushSocket : MeishanPushSocket {
        private var channel = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        private val sentLock = Any()

        /** 前 N 次 connect 失败 */
        var connectFailures = 0

        /** 收到握手(msgType3)自动回 msgType4 */
        var autoAck = true

        @Volatile
        var closed = false

        override suspend fun connect(host: String, port: Int, timeoutMs: Long) {
            if (connectFailures > 0) {
                connectFailures--
                throw IOException("connect fail")
            }
            closed = false
            channel = Channel(Channel.UNLIMITED) // 新连接新通道
        }

        override suspend fun read(): String? = channel.receiveCatching().getOrNull()

        override suspend fun write(text: String) {
            synchronized(sentLock) { sent += text }
            if (autoAck && text.contains("\"msgType\":3")) {
                channel.trySend("""{"msgType":4,"sourceType":0,"clientId":"server"}""")
            }
        }

        override fun setReadTimeoutMs(ms: Long) = Unit

        override fun close() {
            closed = true
            channel.close()
        }

        fun serverSend(text: String) {
            channel.trySend(text)
        }

        fun simulateDisconnect() = close()
    }

    /** 服务端推送帧（msgType0，content 内嵌 tag/pushTime/busInfos） */
    private fun serverPush(tag: String, pushTime: String, plate: String = "川Z10091D"): String {
        val content = buildJsonObject {
            put("tag", JsonPrimitive(tag))
            put("pushTime", JsonPrimitive(pushTime))
            put(
                "busInfos",
                buildJsonArray {
                    add(
                        buildJsonObject {
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
                        }
                    )
                }
            )
        }.toString()
        return buildJsonObject {
            put("msgType", JsonPrimitive(MeishanPushProtocol.MSG_SERVER_PUSH))
            put("pushType", JsonPrimitive(1))
            put("content", JsonPrimitive(content))
        }.toString()
    }

    /** 跑当前虚拟时刻已排队的任务（不推进时间，不触发下一轮心跳排期） */
    private fun TestCoroutineScheduler.step(times: Int = 6) {
        repeat(times) { runCurrent() }
    }

    private fun newClient(fake: FakePushSocket, scheduler: TestCoroutineScheduler): MeishanPushClient =
        MeishanPushClient(
            socketFactory = { fake },
            wallClock = fixedClock,
            externalScope = CoroutineScope(Job() + StandardTestDispatcher(scheduler)),
        )

    @Test
    fun acquire_handshakesAndSubscribes() = runTest {
        val fake = FakePushSocket()
        val scheduler = TestCoroutineScheduler()
        val client = newClient(fake, scheduler)

        client.acquire("11_0_1")
        scheduler.step()

        assertEquals(MeishanPushState.ONLINE, client.state.value)
        assertEquals(2, fake.sent.size, "应发握手 + 订阅两帧")
        assertTrue(fake.sent[0].contains("\"msgType\":3"), "第一帧是建通道")
        assertTrue(fake.sent[1].contains("\"msgType\":2") && fake.sent[1].contains("11_0_1"), "第二帧是订阅")
        client.cancel()
        scheduler.step()
    }

    @Test
    fun serverPush_emitted_andStaleFrameDiscarded() = runTest {
        val fake = FakePushSocket()
        val scheduler = TestCoroutineScheduler()
        val client = newClient(fake, scheduler)
        val collected = mutableListOf<MeishanPushFrame>()
        // 收集器也挂在独立测试作用域上（backgroundScope 在本版本不被驱动）
        CoroutineScope(Job() + StandardTestDispatcher(scheduler)).launch {
            client.frames.collect { collected += it }
        }

        client.acquire("11_0_1")
        scheduler.step()
        assertEquals(MeishanPushState.ONLINE, client.state.value)

        // 推送 tag 不带 areaId（04 文档 §4）；过期 600 秒的丢弃、2 秒前的放行
        fake.serverSend(serverPush(tag = "11_0", pushTime = "2026-11-05 17:50:00"))
        fake.serverSend(serverPush(tag = "11_0", pushTime = "2026-11-05 17:59:58"))
        scheduler.step()

        assertEquals(1, collected.size, "过期帧应被丢弃")
        assertEquals("11_0", collected.single().tag)
        assertEquals("川Z10091D", collected.single().busInfos.optJSONObject(0)?.optString("b_plate_num"))
        client.cancel()
        scheduler.step()
    }

    @Test
    fun disconnect_reconnectsWithBackoffAndResendsSubscribe() = runTest {
        val fake = FakePushSocket()
        val scheduler = TestCoroutineScheduler()
        val client = newClient(fake, scheduler)
        try {
            client.acquire("11_0_1")
            scheduler.step()
            assertEquals(MeishanPushState.ONLINE, client.state.value)
            assertEquals(2, fake.sent.size)

            fake.simulateDisconnect()
            scheduler.step()
            assertEquals(MeishanPushState.DISCONNECTED, client.state.value, "断开后应进入重连等待")

            // 退避 2 秒后重连：重握手 + 补发订阅（04 文档 §6.7）
            scheduler.advanceTimeBy(MeishanPushClient.RECONNECT_MIN_MS)
            scheduler.step()
            assertEquals(MeishanPushState.ONLINE, client.state.value)
            assertEquals(4, fake.sent.size, "重连后重发握手+订阅")
            assertTrue(fake.sent[3].contains("11_0_1"), "重连后补发原订阅")
        } finally {
            client.cancel()
            scheduler.step()
        }
    }

    @Test
    fun release_refcount_disconnectsOnlyAtZero() = runTest {
        val fake = FakePushSocket()
        val scheduler = TestCoroutineScheduler()
        val client = newClient(fake, scheduler)

        client.acquire("11_0_1")
        client.acquire("11_0_1")
        scheduler.step()
        assertEquals(MeishanPushState.ONLINE, client.state.value)

        client.release("11_0_1")
        scheduler.step()
        assertEquals(MeishanPushState.ONLINE, client.state.value, "还有一条引用，不应断开")

        client.release("11_0_1")
        scheduler.step()
        assertEquals(MeishanPushState.IDLE, client.state.value, "归零应立即断开")
        assertTrue(fake.closed)
        client.cancel()
        scheduler.step()
    }

    @Test
    fun subscribeChange_resendWithinAFrame() = runTest {
        val fake = FakePushSocket()
        val scheduler = TestCoroutineScheduler()
        val client = newClient(fake, scheduler)

        client.acquire("11_0_1")
        scheduler.step()

        // 在线期新增订阅：服务端 3 秒一帧，帧间检测变更并补发
        client.acquire("19_0_1")
        fake.serverSend(serverPush(tag = "11_0", pushTime = "2026-11-05 17:59:58"))
        scheduler.step()

        val lastSubscribe = fake.sent.last { it.contains("\"msgType\":2") }
        assertTrue(lastSubscribe.contains("11_0_1") && lastSubscribe.contains("19_0_1"), "订阅集变更应补发全量")
        client.cancel()
        scheduler.step()
    }

    @Test
    fun connectFailure_backsOffAndRetries() = runTest {
        val fake = FakePushSocket()
        fake.connectFailures = 1
        val scheduler = TestCoroutineScheduler()
        val client = newClient(fake, scheduler)

        client.acquire("11_0_1")
        scheduler.step()
        assertEquals(MeishanPushState.DISCONNECTED, client.state.value, "首次连接失败")

        scheduler.advanceTimeBy(MeishanPushClient.RECONNECT_MIN_MS)
        scheduler.step()
        assertEquals(MeishanPushState.ONLINE, client.state.value, "退避后重试成功")
        client.cancel()
        scheduler.step()
    }
}
