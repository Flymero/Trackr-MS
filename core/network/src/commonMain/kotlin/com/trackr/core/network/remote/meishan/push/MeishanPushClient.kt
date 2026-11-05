package com.trackr.core.network.remote.meishan.push

import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** 服务端要求重建通道（msgType=5）：立即重握手，不走退避 */
internal class MeishanPushRebuildSignal : Exception("server requested channel rebuild")

/**
 * 眉山官方 TCP 实时推送客户端（doc/news 04 文档协议）。
 *
 * 连接：busapp.superms.cn:3213，帧 = JSON 文本 + `$_$`。
 * 状态机：connect(3) → ack(4) → 订阅(2)；心跳 25 秒；msgType5 重握手；
 * 断线指数退避（2s→30s）重连后补发全部订阅。
 *
 * 订阅是**引用计数**的（原 App 的 clear() 有残留订阅问题）：
 * `acquire(tag)` +1、`release(tag)` -1，归零立即断开；首条订阅触发建连。
 * 推送帧先做 pushTime 300 秒时效过滤再吐出。
 *
 * 降级：连接失败/断开时 [state] 为 [MeishanPushState.DISCONNECTED]，
 * 调用方（仓库层）据此回退 HTTP 轮询。
 */
@OptIn(ExperimentalUuidApi::class)
class MeishanPushClient(
    private val socketFactory: () -> MeishanPushSocket = ::platformPushSocket,
    private val wallClock: Clock = Clock.System,
    externalScope: CoroutineScope? = null,
) : MeishanPushClientApi {

    companion object {
        internal const val RECONNECT_MIN_MS = 2_000L
        internal const val RECONNECT_MAX_MS = 30_000L
        private const val HEARTBEAT_TICK_MS = 5_000L
        private const val HEARTBEAT_AFTER_MS = 28_000L
        private const val STALE_AFTER_MS = 300_000L
        private val BEIJING = TimeZone.of("Asia/Shanghai")
    }

    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 进程内不变的客户端标识（04 文档 §1） */
    private val clientId: String = Uuid.random().toString()

    private val _state = MutableStateFlow(MeishanPushState.IDLE)
    override val state: StateFlow<MeishanPushState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<MeishanPushFrame>(
        replay = 16,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: SharedFlow<MeishanPushFrame> = _frames.asSharedFlow()

    /** tag -> 引用计数（>0 = 想订阅） */
    private val tagRefs = HashMap<String, Int>()
    private val refsLock = Any()

    /** 无订阅时唤醒主循环（CONFLATED：多次唤醒合并） */
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var currentSocket: MeishanPushSocket? = null

    private var loop: Job? = null

    /** 写串行化（心跳协程与主循环都写 socket） */
    private val writeMutex = Mutex()
    private var lastSendMs: Long = 0L

    override suspend fun acquire(tag: String) {
        synchronized(refsLock) {
            tagRefs[tag] = (tagRefs[tag] ?: 0) + 1
        }
        startLoop()
        wakeup.trySend(Unit)
    }

    override suspend fun release(tag: String) {
        val becameEmpty = synchronized(refsLock) {
            val next = (tagRefs[tag] ?: 0) - 1
            if (next <= 0) tagRefs.remove(tag) else tagRefs[tag] = next
            tagRefs.isEmpty()
        }
        if (becameEmpty) {
            // 归零立即断开（对齐原 App 页面离开清订阅的行为），读循环随 socket 关闭结束
            currentSocket?.close()
        }
    }

    private fun startLoop() {
        synchronized(refsLock) {
            if (loop?.isActive == true) return
            loop = scope.launch { runForever() }
        }
    }

    private fun activeTags(): Set<String> = synchronized(refsLock) { tagRefs.keys.toSet() }

    /**
     * 主循环：无订阅时挂起等待；有订阅时 建连→握手→订阅→在线读；
     * 断开后退避重连（msgType5 立即重连）。
     */
    private suspend fun runForever() {
        var backoffMs = RECONNECT_MIN_MS
        while (true) {
            if (activeTags().isEmpty()) {
                _state.value = MeishanPushState.IDLE
                wakeup.receive()
                continue
            }
            _state.value = MeishanPushState.CONNECTING
            val socket = socketFactory()
            currentSocket = socket
            var rebuildImmediately = false
            try {
                socket.connect(MeishanPushProtocol.HOST, MeishanPushProtocol.PORT, MeishanPushProtocol.CONNECT_TIMEOUT_MS)
                socket.setReadTimeoutMs(MeishanPushProtocol.HANDSHAKE_TIMEOUT_MS)
                write(socket, connectFrame())
                awaitChannelAck(socket)
                socket.setReadTimeoutMs(0)
                write(socket, subscribeFrame(activeTags()))
                _state.value = MeishanPushState.ONLINE
                backoffMs = RECONNECT_MIN_MS
                runOnline(socket)
            } catch (ce: CancellationException) {
                socket.close()
                throw ce
            } catch (r: MeishanPushRebuildSignal) {
                socket.close()
                rebuildImmediately = true
            } catch (_: Exception) {
                socket.close()
            }
            if (activeTags().isEmpty()) continue // 引用归零，主动断开
            _state.value = MeishanPushState.DISCONNECTED
            if (!rebuildImmediately) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
        }
    }

    /**
     * 在线读循环。返回 true = 连接断开需要重连；false = 订阅归零主动断开。
     * 订阅集合变化在帧间检测（服务端 3 秒一帧，变更最迟 3 秒内生效）。
     */
    private suspend fun runOnline(socket: MeishanPushSocket): Boolean = coroutineScope {
        // 心跳挂在本连接协程下（不是独立 scope）：任何出口都先取消它，
        // coroutineScope 才能返回（否则会一直等这个无限循环）
        val heartbeat = launch { heartbeatLoop(socket) }
        try {
            while (true) {
                if (activeTags().isEmpty()) return@coroutineScope false
                val text = socket.read() ?: return@coroutineScope true
                if (text.isBlank()) continue
                processFrame(text)
                val want = activeTags()
                if (want != subscribedSnapshot) {
                    write(socket, subscribeFrame(want))
                }
                if (want.isEmpty()) return@coroutineScope false
            }
        } finally {
            heartbeat.cancel()
        }
        error("读循环不可达出口")
    }

    /** 当前连接上最近一次已发送的订阅集合（变更检测用） */
    private var subscribedSnapshot: Set<String> = emptySet()

    private suspend fun heartbeatLoop(socket: MeishanPushSocket) {
        while (true) {
            delay(HEARTBEAT_TICK_MS)
            if (wallClock.now().toEpochMilliseconds() - lastSendMs >= HEARTBEAT_AFTER_MS) {
                runCatching { write(socket, heartbeatFrame()) }
            }
        }
    }

    private suspend fun write(socket: MeishanPushSocket, frame: String) {
        writeMutex.withLock {
            socket.write(frame)
            lastSendMs = wallClock.now().toEpochMilliseconds()
        }
    }

    /** 等通道建立确认（msgType=4）；读超时/断开抛 IOException */
    private suspend fun awaitChannelAck(socket: MeishanPushSocket) {
        val deadline = wallClock.now().toEpochMilliseconds() + MeishanPushProtocol.HANDSHAKE_TIMEOUT_MS
        while (wallClock.now().toEpochMilliseconds() < deadline) {
            val text = socket.read() ?: throw IOException("握手期连接断开")
            if (text.isBlank()) continue
            val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
            when (json.optInt("msgType", -1)) {
                MeishanPushProtocol.MSG_CHANNEL_ACK -> return
                MeishanPushProtocol.MSG_REBUILD -> throw MeishanPushRebuildSignal()
                // 握手期不该来心跳/推送，忽略
            }
        }
        throw IOException("握手超时")
    }

    private fun processFrame(text: String) {
        val outer = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (outer.optInt("msgType", -1)) {
            MeishanPushProtocol.MSG_SERVER_PUSH -> {
                val contentText = outer.optString("content")
                if (contentText.isBlank()) return
                val content = runCatching { JSONObject(contentText) }.getOrNull() ?: return
                val tag = content.optString("tag")
                if (tag.isBlank()) return
                val pushTime = content.optString("pushTime")
                // 时效过滤（04 文档 §5）：断线重连补发的旧包直接丢弃
                if (isStale(pushTime)) return
                val busInfos: JSONArray = content.optJSONArray("busInfos") ?: JSONArray()
                _frames.tryEmit(MeishanPushFrame(tag, pushTime, busInfos))
            }
            MeishanPushProtocol.MSG_HEARTBEAT,
            MeishanPushProtocol.MSG_CHANNEL_ACK -> Unit // 心跳回包 / 在线期重复 ack
            MeishanPushProtocol.MSG_REBUILD -> throw MeishanPushRebuildSignal()
        }
    }

    private fun isStale(pushTime: String): Boolean {
        val parsed = runCatching {
            LocalDateTime.parse(pushTime.trim().replace(' ', 'T')).toInstant(BEIJING)
        }.getOrNull() ?: return false // 解析不了宁可放行
        return wallClock.now().toEpochMilliseconds() - parsed.toEpochMilliseconds() > STALE_AFTER_MS
    }

    // ---- 帧构造（04 文档 §2/§3 的实测报文格式）----

    private fun connectFrame(): String = buildJsonObject {
        put("clientId", JsonPrimitive(clientId))
        put("msgType", JsonPrimitive(MeishanPushProtocol.MSG_CONNECT))
        put("sourceType", JsonPrimitive(1))
    }.toString() + MeishanPushProtocol.DELIMITER

    private fun heartbeatFrame(): String = buildJsonObject {
        put("clientId", JsonPrimitive(clientId))
        put("msgType", JsonPrimitive(MeishanPushProtocol.MSG_HEARTBEAT))
        put("sourceType", JsonPrimitive(1))
    }.toString() + MeishanPushProtocol.DELIMITER

    private fun subscribeFrame(tags: Set<String>): String {
        subscribedSnapshot = tags
        val data = buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } }.toString()
        return buildJsonObject {
            put("clientId", JsonPrimitive(clientId))
            put("msgType", JsonPrimitive(MeishanPushProtocol.MSG_SUBSCRIBE))
            put("sourceType", JsonPrimitive(1))
            put("data", JsonPrimitive(data))
        }.toString() + MeishanPushProtocol.DELIMITER
    }

    /** 测试 / 进程收尾：停掉主循环并断开当前连接 */
    fun cancel() {
        loop?.cancel()
        currentSocket?.close()
    }
}
