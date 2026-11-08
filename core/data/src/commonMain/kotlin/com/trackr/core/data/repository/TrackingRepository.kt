package com.trackr.core.data.repository

import androidx.datastore.core.DataStore
import com.trackr.core.data.local.SerialWriter
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.GroupTrackingSessionState
import com.trackr.core.model.LegPhase
import com.trackr.core.model.LiveBus
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.UserFix
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 追踪会话的唯一数据源（重构计划 §2.6）。
 *
 * UI、通知动作、追踪服务都读写这里：UI 和通知动作发命令（开始 / 我上车了 / 下一程 / 改提醒站数 / 结束），
 * 追踪服务轮询后用 [update] 写回（先核对 sessionId + 代际号，旧的结果直接丢）。会话落盘，进程被杀后读回来继续。
 *
 * 第 36 步起同时追多项：仓库里是一份**会话列表**（按开始先后排），每份各自有代际号、互不影响 ——
 * 再追踪同站的另一条线、或者另一个站的另一条线，都只是往列表里加一份，不会覆盖前面的。
 * [session]、[legState]、[groupSession] 是列表第一份（主会话）的视图，留给只关心一项的地方；
 * 页面按 lineId / sessionId 找自己那一份用 [sessions] / [sessionById]。
 */
interface TrackingRepository {

    /** 所有会话，按开始先后排（第一份是主会话）；没有在追踪时为空 */
    val sessions: StateFlow<List<TrackingSession>>

    /** 主会话（列表第一份没中断的）；没在追踪时为 null */
    val session: StateFlow<TrackingSession?>

    /** 主会话当前这一程的状态（还没按线路拆开的老地方读它）；没在追踪、或会话中断待恢复时 `isActive = false` */
    val legState: StateFlow<TrackingSessionState>

    /** 主会话若是路线追踪，给收藏页、规划页的换乘组视图；否则为 null */
    val groupSession: StateFlow<GroupTrackingSessionState?>

    /**
     * 当前业务有效的 TCP 推送线路，key 是 sessionId，value 是线路 id 集合；不落盘。
     * 只有追踪服务用当前 session/generation 成功写入的线路才会出现在这里。
     */
    val pushOnlineLines: StateFlow<Map<String, Set<String>>>

    /**
     * 更新一条线路的 TCP 业务在线状态。session 不存在、已中断、generation 过期，
     * 或线路已不属于该会话时忽略，避免旧订阅把首页重新标成在线。
     */
    fun setPushOnline(sessionId: String, generation: Int, lineId: String, online: Boolean)

    /** 下车站辅助判定用的最近一次手机定位（服务定位和 App 前台定位里最后来的那个，所有会话共用） */
    val latestUserFix: UserFix?

    /** 一份会话的当前快照；没有这一份时返回 null */
    fun sessionById(sessionId: String): TrackingSession?

    /** 开始 [target] 时会被替换掉的那一份（同一个目标）；没有时返回 null */
    fun sessionReplacedBy(target: TrackingTarget): TrackingSession?

    /**
     * 开始一次新追踪：往列表里加一份，**不影响**正在追的其他项。
     * 同一个目标的旧会话（同一条线同一个站、同一个站同一批线、同一条路线）会先被替换掉。
     * 超过 [MAX_SESSIONS] 项时挤掉最早开始的那一份。
     * @return 新会话；目标无效（路线没有可坐的线）时返回 null
     */
    fun start(target: TrackingTarget): TrackingSession?

    /**
     * 结束追踪。
     * @param sessionId 结束哪一份；null = 结束全部
     */
    fun stop(sessionId: String? = null)

    /**
     * 开始失败（后台不允许启动前台服务）时收拾现场：去掉 [failed] 那一份，
     * 并把它替换掉的旧会话 [previous] 放回原来的位置（[previous] 为 null 时什么都不放回）。
     * 只在会话里仍有 [failed] 时生效。
     */
    fun restore(failed: TrackingSession, previous: TrackingSession?)

    /**
     * 手动「我已上车」；不在等车阶段时什么都不做。
     * 一起等：不同线的车同时在站、定位分不清时写 `boardChoice`（等 [chooseBoardedBus]），否则上那辆、会话切到那条线。
     *
     * @param sessionId 哪一份会话；null = 主会话
     */
    fun confirmBoarded(sessionId: String? = null)

    /** 一起等「上了哪辆？」：选了 [busId]，或 null =「还没上」（清掉待选接着等）；没有待选时什么都不做 */
    fun chooseBoardedBus(busId: String?, sessionId: String? = null)

    /**
     * 一起等里去掉一条线（首页那一张卡点「结束」）：只停这一条，其他线接着等。
     * 去掉后一条都不剩、或这条线是已经上车追的那条时，整份会话结束。
     */
    fun removeWatchLine(sessionId: String, lineId: String)

    /** 手动切到下一程；不是路线或已是最后一程时什么都不做 */
    fun switchToNextLeg(sessionId: String? = null)

    /** 切换提前几站提醒（1 -> 2 -> 3 -> 1） */
    fun cycleThreshold(sessionId: String? = null)

    /** 记下一次手机定位（下车站辅助判定用） */
    fun updateUserFix(fix: UserFix)

    /**
     * 追踪服务写回轮询结果：只在会话 [sessionId] 仍是第 [generation] 代时才应用 [transform]
     * （在当前值上算，期间用户点过「我上车了」等也不会被覆盖）。[transform] 返回 null 表示结束这一份会话。
     * @return 是否应用了（false = 结果已过期，丢弃）
     */
    fun update(sessionId: String, generation: Int, transform: (TrackingSession) -> TrackingSession?): Boolean

    /** 追踪服务记下某份会话最近一次轮询拿到的车（「我上车了」选车用，不落盘）；代际号不对时忽略 */
    fun recordBuses(sessionId: String, generation: Int, buses: List<LiveBus>)

    /**
     * 一起等：追踪服务记下某份会话最近一次轮询每条线的车（lineId -> 车，「我上车了」选车用，不落盘）。
     * 只更新这次拿到的线，没请求的线保留上次的；代际号不对时忽略。
     */
    fun recordLineBuses(sessionId: String, generation: Int, lineBuses: Map<String, List<LiveBus>>)

    /**
     * 追踪服务接上了一份从盘里读回的（中断的）会话：清掉中断标记，代际号 +1。
     * @return 接上后的会话；没有这一份时为 null
     */
    fun resume(sessionId: String): TrackingSession?

    /** 接续所有中断的会话（进程重建后服务起来时用）；按开始先后返回 */
    fun resumeAll(): List<TrackingSession>

    /** Suspend work without losing targets; invalidate every in-flight result and cached fix. */
    fun pauseAll()

    /** 等盘里的会话读完（服务被系统重建时先等它） */
    suspend fun awaitLoaded()

    companion object {
        /** 最多同时追几项：每项一条常驻通知，再多状态栏就放不下了 */
        const val MAX_SESSIONS: Int = 4
    }
}

/** 会话怎么落盘（新存储：DataStore `tracking_session.json`；旧存储：只在内存里） */
interface TrackingSessionStore {
    suspend fun read(): List<TrackingSession>
    suspend fun write(sessions: List<TrackingSession>)
}

/** 不落盘的会话存储（旧存储 USE_NEW_STORE=old 时用，和第 12 步之前一样：进程没了会话就没了） */
class InMemoryTrackingSessionStore : TrackingSessionStore {
    private var value: List<TrackingSession> = emptyList()
    override suspend fun read(): List<TrackingSession> = value
    override suspend fun write(sessions: List<TrackingSession>) {
        value = sessions
    }
}

/** DataStore 版会话存储 */
class DataStoreTrackingSessionStore(
    private val dataStore: DataStore<List<TrackingSession>>
) : TrackingSessionStore {
    override suspend fun read(): List<TrackingSession> = dataStore.data.first()
    override suspend fun write(sessions: List<TrackingSession>) {
        dataStore.updateData { sessions }
    }
}

/**
 * [TrackingRepository] 的实现：内存里一份列表（加锁），每次变化排队写盘。
 *
 * @param scope 读盘、写盘用的作用域（应用级）
 * @param clock 墙钟（写进 `updatedAtMillis`、判断读回的会话是否太旧）
 * @param monotonicClock 和定位同一时基的单调时钟（一起等「我上车了」用定位分辨上了哪辆）；null = 不用定位分辨
 * @param newSessionId 新会话的标识
 */
class DefaultTrackingRepository @OptIn(ExperimentalUuidApi::class) constructor(
    private val store: TrackingSessionStore,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val monotonicClock: MonotonicClock? = null,
    private val newSessionId: () -> String = { Uuid.random().toString() }
) : TrackingRepository {

    private val lock = SynchronizedObject()
    private val writer = SerialWriter(scope)

    private val _sessions = MutableStateFlow<List<TrackingSession>>(emptyList())
    override val sessions: StateFlow<List<TrackingSession>> = _sessions.asStateFlow()

    private val _session = MutableStateFlow<TrackingSession?>(null)
    override val session: StateFlow<TrackingSession?> = _session.asStateFlow()

    private val _legState = MutableStateFlow(TrackingSessionState())
    override val legState: StateFlow<TrackingSessionState> = _legState.asStateFlow()

    private val _groupSession = MutableStateFlow<GroupTrackingSessionState?>(null)
    override val groupSession: StateFlow<GroupTrackingSessionState?> = _groupSession.asStateFlow()

    private val _pushOnlineLines = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    override val pushOnlineLines: StateFlow<Map<String, Set<String>>> = _pushOnlineLines.asStateFlow()

    override fun setPushOnline(sessionId: String, generation: Int, lineId: String, online: Boolean) {
        synchronized(lock) {
            val current = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
            if (current.interrupted || !current.state.isActive || current.generation != generation ||
                lineId !in current.pushLineIds()
            ) return
            val lines = _pushOnlineLines.value[sessionId].orEmpty()
            val next = if (online) lines + lineId else lines - lineId
            _pushOnlineLines.value = if (next.isEmpty()) {
                _pushOnlineLines.value - sessionId
            } else {
                _pushOnlineLines.value + (sessionId to next)
            }
        }
    }

    /** 已经用过的最大代际号（停止后也不回退，旧结果永远比不上） */
    private var lastGeneration = 0

    /** 读盘完成前就有人开始 / 结束过：读回来的会话作废 */
    private var touched = false

    /** 每份会话最近一次轮询拿到的车（「我上车了」用，不落盘） */
    private val lastBuses = mutableMapOf<String, List<LiveBus>>()

    /** 一起等：每份会话最近一次轮询每条线的车 */
    private val lastLineBuses = mutableMapOf<String, Map<String, List<LiveBus>>>()

    private var userFix: UserFix? = null
    override val latestUserFix: UserFix?
        get() = synchronized(lock) { userFix }

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            val stored = try {
                store.read()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
            synchronized(lock) {
                stored.forEach { lastGeneration = maxOf(lastGeneration, it.generation) }
                if (stored.isNotEmpty() && !touched && _sessions.value.isEmpty()) {
                    // 进程被杀后读回：先标成中断，服务接上后再清掉；太久没更新的直接扔
                    val fresh = stored.filter { session ->
                        val age = nowMillis() - session.state.updatedAtMillis
                        // 年龄为负 = 用户把系统时间往回调了，照样接着追
                        age <= MAX_RESUME_AGE_MILLIS
                    }
                    if (fresh.isNotEmpty()) {
                        publish(fresh.map { it.copy(interrupted = true) }, persist = false)
                    } else {
                        writer.enqueue { store.write(emptyList()) }
                    }
                }
            }
            loaded.complete(Unit)
        }
    }

    override suspend fun awaitLoaded() {
        loaded.await()
    }

    override fun sessionById(sessionId: String): TrackingSession? =
        synchronized(lock) { _sessions.value.firstOrNull { it.sessionId == sessionId } }

    override fun sessionReplacedBy(target: TrackingTarget): TrackingSession? = synchronized(lock) {
        val key = target.trackingKey()
        _sessions.value.firstOrNull { it.target.trackingKey() == key }
    }

    override fun start(target: TrackingTarget): TrackingSession? = synchronized(lock) {
        // 这条线已经在同一个站的一起等里了：不新建一份，免得首页出现两张一样的卡
        if (target is TrackingTarget.SingleLine) {
            _sessions.value.firstOrNull { it.coversSingle(target) }?.takeIf { !it.interrupted }
                ?.let { return@synchronized it }
        }
        // 目标无效时什么都不动（不占代际号，也不作废盘里读回的会话）
        val generation = maxOf(lastGeneration, _sessions.value.maxOfOrNull { it.generation } ?: 0) + 1
        val created = TrackingEngine.begin(newSessionId(), generation, target, nowMillis()) ?: return@synchronized null
        lastGeneration = generation
        touched = true
        // 同一个目标再点一次：换掉原来那一份（重新计时），不叠两份一样的；
        // 一起等还要把同站单独追的那条线收进来（不然那条线会有两张卡）
        val key = target.trackingKey()
        val kept = _sessions.value.filterNot { it.target.trackingKey() == key || it.absorbedBy(target) }
        lastBuses.keys.retainAll(kept.mapTo(mutableSetOf()) { it.sessionId })
        lastLineBuses.keys.retainAll(kept.mapTo(mutableSetOf()) { it.sessionId })
        // 太多项了：挤掉最早开始的那一份
        val room = (TrackingRepository.MAX_SESSIONS - 1).coerceAtLeast(0)
        val trimmed = if (kept.size > room) kept.drop(kept.size - room) else kept
        (kept - trimmed.toSet()).forEach { dropped ->
            lastBuses.remove(dropped.sessionId)
            lastLineBuses.remove(dropped.sessionId)
        }
        publish(trimmed + created)
        created
    }

    override fun stop(sessionId: String?) {
        synchronized(lock) {
            touched = true
            val current = _sessions.value
            val remaining = if (sessionId == null) emptyList() else current.filterNot { it.sessionId == sessionId }
            if (remaining.size == current.size) return
            nextGeneration()
            (current - remaining.toSet()).forEach { gone ->
                lastBuses.remove(gone.sessionId)
                lastLineBuses.remove(gone.sessionId)
            }
            if (remaining.isEmpty()) userFix = null
            publish(remaining)
        }
    }

    override fun restore(failed: TrackingSession, previous: TrackingSession?) {
        synchronized(lock) {
            val current = _sessions.value
            val index = current.indexOfFirst { it.sessionId == failed.sessionId }
            if (index < 0) return
            lastBuses.remove(failed.sessionId)
            lastLineBuses.remove(failed.sessionId)
            // 去掉失败的那一份；放回上一份时先把可能还在列表里的同一个会话去掉，免得多出一份
            val without = current.filterNot {
                it.sessionId == failed.sessionId || (previous != null && it.sessionId == previous.sessionId)
            }
            if (previous == null) {
                nextGeneration()
                publish(without)
            } else {
                val restored = previous.copy(generation = nextGeneration())
                val insertAt = index.coerceIn(0, without.size)
                publish(without.toMutableList().apply { add(insertAt, restored) })
            }
        }
    }

    override fun confirmBoarded(sessionId: String?) {
        synchronized(lock) {
            val current = targetOf(sessionId) ?: return
            if (current.interrupted) return
            val fixForBoarding = monotonicClock?.let { userFix }
            val boarded = TrackingEngine.confirmBoarded(
                session = current,
                lastBuses = lastBuses[current.sessionId].orEmpty(),
                nowMillis = nowMillis(),
                lineBuses = lastLineBuses[current.sessionId].orEmpty(),
                fix = fixForBoarding,
                nowElapsedMillis = monotonicClock?.elapsedMillis() ?: 0L
            ) ?: return
            publishMaybeNewGeneration(current, boarded)
        }
    }

    override fun chooseBoardedBus(busId: String?, sessionId: String?) {
        synchronized(lock) {
            val current = targetOf(sessionId) ?: return
            if (current.interrupted) return
            val chosen = TrackingEngine.chooseBoardedBus(current, busId, nowMillis()) ?: return
            publishMaybeNewGeneration(current, chosen)
        }
    }

    override fun removeWatchLine(sessionId: String, lineId: String) {
        synchronized(lock) {
            val current = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
            if (current.interrupted) return
            val next = TrackingEngine.removeWatchLine(current, lineId, nowMillis())
            if (next == null) {
                lastBuses.remove(sessionId)
                lastLineBuses.remove(sessionId)
                nextGeneration()
                publish(_sessions.value.filterNot { it.sessionId == sessionId })
            } else {
                publishMaybeNewGeneration(current, next)
            }
        }
    }

    /** 调用方已持锁：一起等上车会切到那条线（代际号 +1），上一阶段的车不再用 */
    private fun publishMaybeNewGeneration(current: TrackingSession, next: TrackingSession) {
        if (next.generation != current.generation) {
            lastGeneration = maxOf(lastGeneration, next.generation)
            lastBuses.remove(current.sessionId)
            lastLineBuses.remove(current.sessionId)
        }
        publish(_sessions.value.map { if (it.sessionId == current.sessionId) next else it })
    }

    override fun switchToNextLeg(sessionId: String?) {
        synchronized(lock) {
            val current = targetOf(sessionId) ?: return
            if (current.interrupted) return
            val next = TrackingEngine.nextLeg(current, nowMillis()) ?: return
            lastGeneration = maxOf(lastGeneration, next.generation)
            lastBuses.remove(current.sessionId)
            lastLineBuses.remove(current.sessionId)
            userFix = null
            publish(_sessions.value.map { if (it.sessionId == current.sessionId) next else it })
        }
    }

    override fun cycleThreshold(sessionId: String?) {
        synchronized(lock) {
            val current = targetOf(sessionId) ?: return
            if (current.interrupted || !current.state.isActive) return
            val next = TrackingEngine.cycleThreshold(current, nowMillis())
            publish(_sessions.value.map { if (it.sessionId == current.sessionId) next else it })
        }
    }

    override fun updateUserFix(fix: UserFix) {
        synchronized(lock) { userFix = fix }
    }

    override fun update(sessionId: String, generation: Int, transform: (TrackingSession) -> TrackingSession?): Boolean =
        synchronized(lock) {
            val current = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return@synchronized false
            if (current.interrupted || current.generation != generation) return@synchronized false
            val next = transform(current)
            if (next == null) {
                lastGeneration = maxOf(lastGeneration, current.generation + 1)
                lastBuses.remove(sessionId)
                lastLineBuses.remove(sessionId)
                userFix = null
                publish(_sessions.value.filterNot { it.sessionId == sessionId })
            } else {
                if (next.generation != current.generation) {
                    // 引擎切了程：上一程的车和定位都不再用
                    lastGeneration = maxOf(lastGeneration, next.generation)
                    lastBuses.remove(sessionId)
                    lastLineBuses.remove(sessionId)
                    userFix = null
                }
                publish(_sessions.value.map { if (it.sessionId == sessionId) next else it })
            }
            true
        }

    override fun recordBuses(sessionId: String, generation: Int, buses: List<LiveBus>) {
        synchronized(lock) {
            val current = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
            if (current.generation == generation) lastBuses[sessionId] = buses
        }
    }

    override fun recordLineBuses(sessionId: String, generation: Int, lineBuses: Map<String, List<LiveBus>>) {
        synchronized(lock) {
            val current = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return
            if (current.generation == generation) {
                lastLineBuses[sessionId] = lastLineBuses[sessionId].orEmpty() + lineBuses
            }
        }
    }

    override fun pauseAll() = synchronized(lock) {
        userFix = null
        lastBuses.clear()
        lastLineBuses.clear()
        val paused = _sessions.value.map {
            if (it.interrupted) it else it.copy(interrupted = true, generation = nextGeneration())
        }
        if (paused != _sessions.value) publish(paused)
    }

    override fun resume(sessionId: String): TrackingSession? = synchronized(lock) {
        val current = _sessions.value.firstOrNull { it.sessionId == sessionId } ?: return@synchronized null
        if (!current.interrupted) return@synchronized current
        val resumed = current.copy(interrupted = false, generation = nextGeneration())
        lastBuses.remove(sessionId)
        lastLineBuses.remove(sessionId)
        publish(_sessions.value.map { if (it.sessionId == sessionId) resumed else it })
        resumed
    }

    override fun resumeAll(): List<TrackingSession> = synchronized(lock) {
        val interrupted = _sessions.value.filter { it.interrupted && it.state.isActive }
        if (interrupted.isEmpty()) return@synchronized emptyList()
        val resumed = interrupted.map { session ->
            lastBuses.remove(session.sessionId)
            lastLineBuses.remove(session.sessionId)
            session.copy(interrupted = false, generation = nextGeneration())
        }
        val byId = resumed.associateBy { it.sessionId }
        publish(_sessions.value.map { byId[it.sessionId] ?: it })
        resumed
    }

    /** 调用方已持锁：取要操作的那一份（[sessionId] 为 null 时取主会话） */
    private fun targetOf(sessionId: String?): TrackingSession? {
        val list = _sessions.value
        return if (sessionId == null) {
            list.firstOrNull { !it.interrupted } ?: list.firstOrNull()
        } else {
            list.firstOrNull { it.sessionId == sessionId }
        }
    }

    /** 调用方已持锁 */
    private fun nextGeneration(): Int {
        val current = _sessions.value.maxOfOrNull { it.generation } ?: 0
        lastGeneration = maxOf(lastGeneration, current) + 1
        return lastGeneration
    }

    /** 调用方已持锁：更新几个 StateFlow，并排队写盘 */
    private fun publish(value: List<TrackingSession>, persist: Boolean = true) {
        val previous = _sessions.value.associateBy { it.sessionId }
        _pushOnlineLines.value = _pushOnlineLines.value.mapNotNull { (id, lines) ->
            val current = value.firstOrNull { it.sessionId == id }
            if (current == null || current.interrupted || !current.state.isActive ||
                previous[id]?.generation != current.generation
            ) return@mapNotNull null
            val kept = lines.intersect(current.pushLineIds())
            if (kept.isEmpty()) null else id to kept
        }.toMap()
        _sessions.value = value
        val live = value.firstOrNull { !it.interrupted }
        _session.value = live ?: value.firstOrNull()
        _legState.value = live?.state ?: TrackingSessionState(isActive = false)
        _groupSession.value = live?.toGroupSessionState()
        if (persist) writer.enqueue { store.write(value) }
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    /** 等写盘排队的都做完（测试用） */
    suspend fun flush() = writer.flush()

    companion object {
        /** 读回的会话超过这么久没更新就不再接着追（多半是关机了一整夜） */
        const val MAX_RESUME_AGE_MILLIS: Long = 2 * 60 * 60 * 1000L
    }
}

/** 当前阶段仍在追的官方线路；一起等上车后只剩坐上的那条线。 */
private fun TrackingSession.pushLineIds(): Set<String> {
    val watch = watchTarget
    return if (watch != null && state.legPhase == LegPhase.WAITING_BOARD) {
        watch.lines.filter { it.source == DataSourceType.MEISHAN || it.lineId.startsWith("ms:") }
            .mapTo(mutableSetOf()) { it.lineId }
    } else {
        if (leg.source == DataSourceType.MEISHAN || leg.lineId.startsWith("ms:")) setOf(leg.lineId) else emptySet()
    }
}

/**
 * 「同一个目标」的判断键：同一个目标再点一次追踪时换掉旧的那一份，而不是叠一份一样的。
 * 一起等按「同一个站 + 同一批线」算。
 */
internal fun TrackingTarget.trackingKey(): String = when (this) {
    is TrackingTarget.SingleLine -> "single|$lineId|$direction|$targetStopId"
    is TrackingTarget.Route -> "route|${group.id}"
    is TrackingTarget.WatchTogether -> "watch|$stopId|" + lines.map { it.lineId }.sorted().joinToString(",")
}

/** 这份会话是「同一个站的一起等，而且已经在等这条线」：单线路追踪不用再新建一份 */
private fun TrackingSession.coversSingle(target: TrackingTarget.SingleLine): Boolean {
    val watch = this.target as? TrackingTarget.WatchTogether ?: return false
    if (!watch.coversStop(target.targetStopId, target.targetStopName)) return false
    return watch.lines.any { it.lineId == target.lineId }
}

/** 新开的一起等把同站单独追的那条线收进来：那份单线路会话该去掉，免得同一条线两张卡 */
private fun TrackingSession.absorbedBy(target: TrackingTarget): Boolean {
    val watch = target as? TrackingTarget.WatchTogether ?: return false
    val single = this.target as? TrackingTarget.SingleLine ?: return false
    return watch.lines.any { it.lineId == single.lineId } &&
        watch.coversStop(single.targetStopId, single.targetStopName)
}

/** 同一个站：站点 id 或站名对得上（两个接口给的站 id 不一定一样） */
private fun TrackingTarget.WatchTogether.coversStop(stopId: String, stopName: String): Boolean =
    (stopId.isNotBlank() && this.stopId == stopId) || (stopName.isNotBlank() && this.stopName == stopName)
