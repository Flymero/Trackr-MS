package com.trackr.core.network.remote.meishan.push

import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.json.JSONObject
import com.trackr.core.network.remote.meishan.intOrNull
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
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
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Server requested a channel rebuild (msgType=5). */
internal class MeishanPushRebuildSignal : Exception("server requested channel rebuild")

@OptIn(ExperimentalUuidApi::class)
class MeishanPushClient(
    private val socketFactory: () -> MeishanPushSocket = ::platformPushSocket,
    private val wallClock: Clock = Clock.System,
    externalScope: CoroutineScope? = null,
    /**
     * 重连循环所在调度器：commonMain 不依赖 JVM-only 的 Dispatchers.IO，平台各自注入。
     * 不要传 Unconfined（含测试里的 UnconfinedTestDispatcher）：startLoop 在锁内 launch，
     * 新协程会在持锁线程上同步执行并再次申请 refsLock——JVM 锁可重入不报错，Native 锁不可重入，iOS 上死锁。
     */
    loopDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MeishanPushClientApi {

    companion object {
        internal const val RECONNECT_MIN_MS = 2_000L
        internal const val RECONNECT_MAX_MS = 30_000L
        private const val HEARTBEAT_TICK_MS = 5_000L
        private const val HEARTBEAT_AFTER_MS = 28_000L
        private const val STALE_AFTER_MS = 300_000L
        /** Push timestamps can lead the device wall clock slightly; reject only clear skew. */
        internal const val FUTURE_TOLERANCE_MS = 2_000L
        private val BEIJING = TimeZone.of("Asia/Shanghai")
    }

    private val ownedScope = externalScope == null
    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + loopDispatcher)
    private val clientId: String = Uuid.random().toString()

    private val _state = MutableStateFlow(MeishanPushState.IDLE)
    override val state: StateFlow<MeishanPushState> = _state.asStateFlow()
    private val _frames = MutableSharedFlow<MeishanPushFrame>(
        replay = 16,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: SharedFlow<MeishanPushFrame> = _frames.asSharedFlow()

    private val tagRefs = HashMap<String, Int>()
    /** atomicfu 锁：commonMain 可用（JVM=ReentrantLock 语义，Native=pthread），替代 JVM-only 的 kotlin.synchronized */
    private val refsLock = SynchronizedObject()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var currentSocket: MeishanPushSocket? = null
    private var loop: Job? = null
    private val writeMutex = Mutex()
    private var lastSendMs: Long = 0L
    private var subscribedSnapshot: Set<String> = emptySet()
    private val subscriptionWakeup = Channel<Unit>(Channel.CONFLATED)
    private data class PushFrameSource(val tag: String, val areaId: Int)
    private val latestPushTimeByTag = HashMap<PushFrameSource, Long>()

    override suspend fun acquire(tag: String) {
        synchronized(refsLock) { tagRefs[tag] = (tagRefs[tag] ?: 0) + 1 }
        startLoop()
        wakeup.trySend(Unit)
        subscriptionWakeup.trySend(Unit)
    }

    override suspend fun release(tag: String) {
        val becameEmpty = synchronized(refsLock) {
            val next = (tagRefs[tag] ?: 0) - 1
            if (next <= 0) tagRefs.remove(tag) else tagRefs[tag] = next
            tagRefs.isEmpty()
        }
        // 已建立的连接也立即收到新的完整集合；归零由在线循环先发送 []，再关闭连接。
        subscriptionWakeup.trySend(Unit)
        if (becameEmpty && state.value != MeishanPushState.ONLINE) {
            // 握手或连接阶段没有在线循环负责收尾，关闭以中断当前 socket 操作。
            currentSocket?.close()
        }
    }

    private fun startLoop() {
        synchronized(refsLock) {
            if (loop?.isActive != true) {
                loop = scope.launch { runForever() }
            }
        }
    }

    private fun activeTags(): Set<String> = synchronized(refsLock) { tagRefs.keys.toSet() }

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
            var disconnected = true
            try {
                socket.connect(MeishanPushProtocol.HOST, MeishanPushProtocol.PORT, MeishanPushProtocol.CONNECT_TIMEOUT_MS)
                while (true) {
                    establishChannel(socket)
                    _state.value = MeishanPushState.ONLINE
                    backoffMs = RECONNECT_MIN_MS
                    when (runOnline(socket)) {
                        OnlineExit.REBUILD -> continue
                        OnlineExit.DISCONNECTED -> break
                        OnlineExit.NO_SUBSCRIPTIONS -> {
                            disconnected = false
                            break
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Connection, handshake, and protocol failures all enter the retry path.
            } finally {
                // Always close after connect failure, normal disconnect, rebuild failure, or cancellation.
                runCatching { socket.close() }
                if (currentSocket === socket) currentSocket = null
            }
            if (!disconnected || activeTags().isEmpty()) continue
            _state.value = MeishanPushState.DISCONNECTED
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        }
    }

    private suspend fun establishChannel(socket: MeishanPushSocket) {
        socket.setReadTimeoutMs(MeishanPushProtocol.HANDSHAKE_TIMEOUT_MS)
        write(socket, connectFrame())
        awaitChannelAck(socket)
        socket.setReadTimeoutMs(45_000L)
        // 新通道的服务端订阅状态是空的：即使快照相同也必须重发
        writeSubscribe(socket, force = true)
    }

    private enum class OnlineExit { REBUILD, DISCONNECTED, NO_SUBSCRIPTIONS }

    private suspend fun runOnline(socket: MeishanPushSocket): OnlineExit = kotlinx.coroutines.coroutineScope {
        val heartbeat = launch { heartbeatLoop(socket) }
        val subscriptionWatcher = launch {
            while (true) {
                subscriptionWakeup.receive()
                val want = writeSubscribe(socket)
                if (want.isEmpty()) {
                    socket.close()
                    return@launch
                }
            }
        }
        try {
            while (true) {
                if (activeTags().isEmpty()) {
                    writeSubscribe(socket)
                    return@coroutineScope OnlineExit.NO_SUBSCRIPTIONS
                }
                val text = socket.read() ?: return@coroutineScope OnlineExit.DISCONNECTED
                if (text.isBlank()) continue
                try {
                    processFrame(text)
                } catch (_: MeishanPushRebuildSignal) {
                    return@coroutineScope OnlineExit.REBUILD
                }
                if (activeTags().isEmpty()) return@coroutineScope OnlineExit.NO_SUBSCRIPTIONS
                // Only subscriptionWatcher consumes subscription changes.
            }
        } finally {
            heartbeat.cancel()
            subscriptionWatcher.cancel()
        }
        error("online loop exited unexpectedly")
    }

    private suspend fun heartbeatLoop(socket: MeishanPushSocket) {
        while (true) {
            delay(HEARTBEAT_TICK_MS)
            if (wallClock.now().toEpochMilliseconds() - lastSendMs >= HEARTBEAT_AFTER_MS) {
                try {
                    write(socket, heartbeatFrame())
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    socket.close()
                    return
                }
            }
        }
    }

    private suspend fun write(socket: MeishanPushSocket, frame: String) {
        writeMutex.withLock {
            socket.write(frame)
            lastSendMs = wallClock.now().toEpochMilliseconds()
        }
    }

    private suspend fun awaitChannelAck(socket: MeishanPushSocket) {
        val deadline = wallClock.now().toEpochMilliseconds() + MeishanPushProtocol.HANDSHAKE_TIMEOUT_MS
        while (wallClock.now().toEpochMilliseconds() < deadline) {
            val text = socket.read() ?: throw IOException("握手期连接断开")
            if (text.isBlank()) continue
            val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
            when (json.optInt("msgType", -1)) {
                MeishanPushProtocol.MSG_CHANNEL_ACK -> return
                MeishanPushProtocol.MSG_REBUILD -> throw MeishanPushRebuildSignal()
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
                val pushTime = content.optString("pushTime").trim()
                val parsedTime = parsePushTime(pushTime) ?: return
                if (isStale(parsedTime)) return
                // 新文档未确认增量协议，缺少 busInfos 的帧不是“空车全量帧”，直接隔离。
                val busInfos: JSONArray = content.optJSONArray("busInfos") ?: return
                val areaId = frameArea(tag, busInfos) ?: return
                val source = PushFrameSource(tag, areaId)
                // 时间新鲜度去重（锁内只做判断与登记，重复帧由锁外返回丢弃）
                val duplicated = synchronized(refsLock) {
                    val previous = latestPushTimeByTag[source]
                    val isDuplicate = previous != null && parsedTime <= previous
                    if (!isDuplicate) latestPushTimeByTag[source] = parsedTime
                    isDuplicate
                }
                if (duplicated) return
                _frames.tryEmit(MeishanPushFrame(tag, pushTime, busInfos, areaId))
            }
            MeishanPushProtocol.MSG_HEARTBEAT,
            MeishanPushProtocol.MSG_CHANNEL_ACK -> Unit
            MeishanPushProtocol.MSG_REBUILD -> throw MeishanPushRebuildSignal()
        }
    }

    private fun frameArea(tag: String, buses: JSONArray): Int? {
        val subscribedAreas = activeTags()
            .filter { it.substringBeforeLast('_') == tag }
            .mapNotNull { it.substringAfterLast('_').toIntOrNull() }
            .toSet()
        if (subscribedAreas.isEmpty()) return null
        val explicitAreas = mutableSetOf<Int>()
        for (index in 0 until buses.length()) {
            val bus = buses.optJSONObject(index) ?: return null
            bus.intOrNull("areaId")?.let { explicitAreas += it }
        }
        val area = when {
            explicitAreas.size == 1 -> explicitAreas.single()
            explicitAreas.isEmpty() -> subscribedAreas.singleOrNull()
            else -> null
        }
        // Empty/missing-area frames for a colliding tag cannot be assigned safely.
        // Ignore them; business freshness times out and existing HTTP polling takes over.
        return area?.takeIf { it in subscribedAreas }
    }

    private fun parsePushTime(pushTime: String): Long? = runCatching {
        LocalDateTime.parse(pushTime.replace(' ', 'T')).toInstant(BEIJING).toEpochMilliseconds()
    }.getOrNull()

    private fun isStale(parsedMillis: Long): Boolean {
        val age = wallClock.now().toEpochMilliseconds() - parsedMillis
        // 未来时间和超过 5 分钟的旧帧都隔离；pushTime 不是 GPS 采样时间，只用于新鲜度门槛。
        return age < -FUTURE_TOLERANCE_MS || age > STALE_AFTER_MS
    }

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

    /**
     * 订阅写入的唯一入口：比对、构造、更新快照、写 socket 收进同一把写锁。
     * 旧实现先在锁外改快照再落写，watcher 与主读循环交错时会把滞留的旧集合
     * 覆盖到新集合之后，服务端订阅集合与客户端快照永久背离（直到重连）。
     */
    private suspend fun writeSubscribe(socket: MeishanPushSocket, force: Boolean = false): Set<String> =
        writeMutex.withLock {
            // Read references after obtaining the write lock, so queued writers cannot restore an old set.
            val tags = activeTags()
            if (force || tags != subscribedSnapshot) {
                socket.write(buildSubscribeFrame(tags))
                subscribedSnapshot = tags
            }
            tags
        }

    private fun buildSubscribeFrame(tags: Set<String>): String {
        val data = buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } }.toString()
        return buildJsonObject {
            put("clientId", JsonPrimitive(clientId))
            put("msgType", JsonPrimitive(MeishanPushProtocol.MSG_SUBSCRIBE))
            put("sourceType", JsonPrimitive(1))
            put("data", JsonPrimitive(data))
        }.toString() + MeishanPushProtocol.DELIMITER
    }

    fun cancel() {
        loop?.cancel()
        currentSocket?.close()
        if (ownedScope) scope.cancel()
    }
}
