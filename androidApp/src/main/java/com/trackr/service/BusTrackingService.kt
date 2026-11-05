package com.trackr.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.trackr.core.data.repository.MeishanPushRepository
import com.trackr.core.data.repository.MeishanPushAvailability
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.data.repository.WatchTogetherPoller
import com.trackr.core.domain.platform.LocationSource
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.LinePoll
import com.trackr.core.domain.tracking.WatchPoll
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.domain.tracking.WatchPollPlan
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.UserFix
import com.trackr.data.repository.ITransitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.time.Clock

/**
 * 实时公交前台追踪服务：轮询当前这一程的线路实时数据，维持常驻通知与到站提醒。
 *
 * 追踪状态只有一份，在 [TrackingRepository]（重构计划 §2.6）。服务启动时只收 sessionId；
 * 轮询结果交给 [TrackingEngine] 算出新会话和副作用，经 [TrackingRepository.update] 写回（核对代际号，
 * 切程 / 停止后回来的旧结果直接丢）。服务同时收集仓库里的会话：代际号变了（切程、恢复）就重开轮询，
 * 会话没了（用户结束、路线走完）就停；通知动作和页面的命令都只改仓库。
 * START_STICKY：进程被杀后系统重建服务时从仓库读回会话接着追；系统不让重新进前台就结束这次追踪。
 *
 * 一起等（第 15 步）：等车阶段每轮按 [TrackingEngine.watchPollPlan] 请求（1 次站点接口；待确认时只请求在站的线），
 * 结果交给 [TrackingEngine.reduceWatchPoll]；上车后会话切到那条线（代际号 +1），之后和路线乘车一样只请求那条线。
 *
 * 线程约定：都在主线程（lifecycleScope），只有网络请求切到 IO。
 */
class BusTrackingService : LifecycleService() {

    private val pushRepository: MeishanPushRepository by inject()
    private val trackingRepository: TrackingRepository by inject()
    private val repository: ITransitRepository by inject()
    private val liveProgressNotifier: LiveProgressNotifier by inject()
    private val arrivalAlerts: ArrivalAlertNotifier by inject()
    private val locationSource: LocationSource by inject()
    private val clock: Clock by inject()
    private val monotonicClock: MonotonicClock by inject()

    private var pollingJob: Job? = null
    private val pushJobs = mutableMapOf<String, Job>()
    private val pushOnline = mutableSetOf<String>()
    private val pushRevisions = mutableMapOf<String, Long>()
    private val refreshWakeup = Channel<Unit>(Channel.CONFLATED)
    private var pushContext: String? = null
    private var bindingVersion = 0L
    private var trackingWakeLock: PowerManager.WakeLock? = null


    /** 一起等的取数（同一个实时仓库，条数不影响请求数） */
    private val watchPoller by lazy { WatchTogetherPoller(repository) }

    /** 正在轮询的会话；null = 还没接上任何会话 */
    private var attachedSessionId: String? = null

    /** 正在轮询的代际号 */
    private var pollingGeneration: Int = NO_GENERATION

    /** 连续刷新失败次数（失败指数退避用，成功后清零） */
    private var consecutiveFailures: Int = 0

    /** 最近一次成功刷新拿到的线路运营状态（非运营时降频轮询） */
    private var lastOperatingState: LineOperatingState = LineOperatingState.RUNNING

    /** 本次前台服务是否带上了 location 类型（没有就拿不到锁屏后的定位，上车检测关闭） */
    private var locationTrackingAllowed: Boolean = false

    /** 当前定位档位与收集它的协程 */
    private var activeTier: LocationCadence.Tier = LocationCadence.Tier.OFF
    private var locationJob: Job? = null

    /** 服务自己最近一次采用的定位（判上车用） */
    private var serviceFix: UserFix? = null

    /** 上次刷到通知上的内容（相同就不重复刷） */
    private var lastRendered: Pair<TrackingSessionState, LiveProgressNotifier.WatchNotice?>? = null

    /** 最近一次 onStartCommand 的 startId：停止时只停到这一次，之后又来的启动请求不受影响 */
    private var lastStartId: Int = 0

    /** 最近一次 startForeground 用的前台服务类型（测试读） */
    @VisibleForTesting
    internal var lastForegroundServiceType: Int = -1
        private set

    override fun onCreate() {
        super.onCreate()
        // 用 Dispatchers.Main（不是 lifecycleScope 默认的 Main.immediate）：仓库在锁里发出变化，
        // 这里排到下一轮再处理，不在仓库的锁里跑服务的代码
        lifecycleScope.launch(Dispatchers.Main) {
            trackingRepository.session.collect { onSessionChanged(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        return when (intent?.action) {
            ACTION_START_TRACKING -> handleStart(intent.getStringExtra(EXTRA_SESSION_ID))
            // START_STICKY：进程被杀后系统重建服务，intent 为 null，从仓库读回会话继续
            null -> handleRestart()
            else -> START_NOT_STICKY
        }
    }

    private fun handleStart(sessionId: String?): Int {
        val current = trackingRepository.session.value
        val session = current?.takeIf { it.sessionId == sessionId }
        // startForegroundService 要求尽快 startForeground：即使这个会话已经没了，也要先进前台再停。
        // 已经在追别的会话时（启动请求过时了），前台通知仍显示正在追的那个
        val shown = session ?: current?.takeIf { it.sessionId == attachedSessionId }
        val wantLocation = shown != null && TrackingEngine.wantsLocation(shown.target) &&
            locationSource.hasPrecisePermission()
        val notification = liveProgressNotifier.buildNotification(
            this,
            shown?.state ?: TrackingSessionState(isActive = true),
            watch = LiveProgressNotifier.WatchNotice.of(shown)
        )
        locationTrackingAllowed = try {
            enterForeground(notification, wantLocation)
        } catch (e: IllegalStateException) {
            endStartFailure(session, e)
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            endStartFailure(session, e)
            return START_NOT_STICKY
        }
        if (session == null) {
            // 启动请求发出后用户又结束了（或换了新会话，新会话自己会再来一次启动请求）
            if (shown == null) {
                shutdown()
                return START_NOT_STICKY
            }
            render(shown, force = true)
            return START_STICKY
        }
        val resumed = if (session.interrupted) trackingRepository.resume() else session
        if (resumed == null) {
            shutdown()
            return START_NOT_STICKY
        }
        attach(resumed, force = true)
        return START_STICKY
    }

    /**
     * 进不了前台时的收尾。
     *
     * 中断会话（进程重建读回来的）保留下来，发一条「追踪已中断」提醒，等用户打开 App 再接续——
     * 会话本身没进行过，用户主动决定要不要继续。
     *
     * 正常会话是用户刚点下开始追踪的：服务起不来就没人轮询，留着会话只会让界面一直显示「追踪中」，
     * 所以直接结束它（发一条「追踪已中断」说明原因）。不这么做的话，[AndroidTrackingController.start]
     * 已经返回了 true，没有任何别的路径会来收拾这个会话。
     */
    @VisibleForTesting
    internal fun endStartFailure(session: TrackingSession?, error: Exception) {
        if (session == null) {
            Log.w(TAG, "Cannot enter foreground, nothing to track", error)
            shutdown()
            return
        }
        arrivalAlerts.triggerTrackingInterrupted(this, session.state)
        if (!session.interrupted) {
            Log.w(TAG, "Cannot enter foreground, ending session", error)
            runCatching { trackingRepository.stop() }
        } else {
            Log.w(TAG, "Cannot enter foreground while resuming tracking", error)
        }
        shutdown()
    }

    private fun handleRestart(): Int {
        lifecycleScope.launch {
            trackingRepository.awaitLoaded()
            val stored = trackingRepository.session.value
            if (stored == null) {
                shutdown()
                return@launch
            }
            if (attachedSessionId == stored.sessionId) return@launch
            val wantLocation = TrackingEngine.wantsLocation(stored.target) && locationSource.hasPrecisePermission()
            val notification = liveProgressNotifier.buildNotification(
                this@BusTrackingService,
                stored.state,
                watch = LiveProgressNotifier.WatchNotice.of(stored)
            )
            locationTrackingAllowed = try {
                enterForeground(notification, wantLocation)
            } catch (e: IllegalStateException) {
                // 系统不允许从后台重启前台服务：保留中断会话，等用户打开 App 再接续
                Log.w(TAG, "Cannot restart tracking in foreground", e)
                arrivalAlerts.triggerTrackingInterrupted(this@BusTrackingService, stored.state)
                shutdown()
                return@launch
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot restart tracking in foreground", e)
                arrivalAlerts.triggerTrackingInterrupted(this@BusTrackingService, stored.state)
                shutdown()
                return@launch
            }
            val resumed = trackingRepository.resume() ?: run {
                shutdown()
                return@launch
            }
            attach(resumed, force = true)
        }
        return START_STICKY
    }

    /**
     * 进入前台。带 location 类型失败时（如从后台启动、Android 14+ 不允许使用期间定位）退回只用 specialUse。
     * @return 是否成功带上了 location 类型
     */
    private fun enterForeground(notification: Notification, wantLocation: Boolean): Boolean {
        val baseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        if (wantLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val withLocation = baseType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            try {
                ServiceCompat.startForeground(
                    this,
                    LiveProgressNotifier.NOTIFICATION_ID_LIVE_PROGRESS,
                    notification,
                    withLocation
                )
                lastForegroundServiceType = withLocation
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "Location FGS type not allowed, tracking without location", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Location FGS type rejected, tracking without location", e)
            }
        }
        ServiceCompat.startForeground(
            this,
            LiveProgressNotifier.NOTIFICATION_ID_LIVE_PROGRESS,
            notification,
            baseType
        )
        lastForegroundServiceType = baseType
        return false
    }

    /** 仓库里的会话变了（命令、切程、结束）；服务自己写回的结果在 [pollOnce] 里已经处理过，这里是去重后的兜底 */
    private fun onSessionChanged(session: TrackingSession?) {
        val attached = attachedSessionId ?: return
        when {
            session == null -> shutdown()
            session.interrupted -> Unit
            // 换了新会话：等它自己的启动请求（要按新目标决定前台服务类型）
            session.sessionId != attached -> Unit
            session.generation != pollingGeneration -> attach(session)
            contextOf(session) != pushContext -> attach(session, force = true)
            else -> render(session)
        }
    }

    /** 接上一个会话（或同一会话的新一代）：重置轮询状态、刷新通知、重开轮询 */
    private fun attach(session: TrackingSession, force: Boolean = false) {
        if (!force && session.sessionId == attachedSessionId && session.generation == pollingGeneration) return
        val sameGeneration = session.sessionId == attachedSessionId && session.generation == pollingGeneration
        if (sameGeneration && contextOf(session) == pushContext) {
            // 同一代又来一次启动请求（前台服务类型可能变了）：只刷新通知和定位档位
            render(session, force = true)
            return
        }
        pollingJob?.cancel()
        pollingJob = null
        stopPush()
        pushContext = contextOf(session)
        updateWakeLock(session)
        if (session.sessionId != attachedSessionId) {
            // 新会话：上一次的服务定位不再用
            serviceFix = null
        }
        attachedSessionId = session.sessionId
        pollingGeneration = session.generation
        consecutiveFailures = 0
        lastOperatingState = LineOperatingState.RUNNING
        render(session, force = true)
        startPolling(session.generation)
    }

    private fun startPolling(generation: Int) {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            // 首次立即刷新
            pollOnce(generation)
            while (isActive && isCurrent(generation)) {
                val state = trackingRepository.session.value?.state ?: break
                val intervalMs = TrackingEngine.pollingIntervalMillis(
                    state,
                    consecutiveFailures = consecutiveFailures,
                    operatingState = lastOperatingState
                )
                withTimeoutOrNull(intervalMs) { refreshWakeup.receive() }
                val current = trackingRepository.session.value
                if (current != null && TrackingEngine.isTrackingExpired(current, clock.now().toEpochMilliseconds())) {
                    arrivalAlerts.triggerTrackingExpired(this@BusTrackingService, current.state)
                    trackingRepository.stop()
                    break
                }
                pollOnce(generation)
            }
        }
    }

    /** 本次轮询是否仍属于当前会话 */
    private fun isCurrent(generation: Int): Boolean {
        val session = trackingRepository.session.value ?: return false
        return generation == pollingGeneration && session.generation == generation && !session.interrupted &&
            session.sessionId == attachedSessionId && contextOf(session) == pushContext
    }

    private suspend fun pollOnce(generation: Int) {
        if (!isCurrent(generation)) return
        val session = trackingRepository.session.value ?: return
        val watchTarget = session.watchTarget
        val watchPlan = TrackingEngine.watchPollPlan(session)
        if (watchTarget != null && watchPlan != null) {
            pollWatch(generation, watchTarget, watchPlan)
            return
        }
        val state = session.state
        val leg = session.leg
        if (leg.lineId in pushOnline) return
        val version = bindingVersion
        val revision = pushRevisions[leg.lineId] ?: 0L
        val targetCity = cityFor(leg.cityId, leg.mygolbsCityCode)

        val result = withContext(Dispatchers.IO) {
            repository.getLineDetail(
                city = targetCity,
                lineId = leg.lineId,
                // 等车阶段的 ETA 必须针对上车站；上车后才切到下车站。一起等刚上车
                // 还不知道终点站时 targetOrder 为 0，也先按上车站请求，拿到全线站点后补终点。
                targetOrder = targetOrderOf(session),
                stationId = state.targetStopId.ifBlank { leg.targetStopId },
                source = leg.source,
                lineNameHint = state.lineName,
                directionHint = leg.direction
            )
        }

        // 回到主线程：切程 / 停止后返回的旧结果直接丢弃
        if (!isCurrent(generation) || version != bindingVersion || revision != (pushRevisions[leg.lineId] ?: 0L)) return

        result.onSuccess { detail ->
            applyDetail(generation, detail)
            if (version == bindingVersion && isCurrent(generation)) {
                startPush(session, detail, targetOrderOf(session), state.targetStopId.ifBlank { leg.targetStopId }, watch = false)
            }
        }.onFailure { e -> applyFailure(generation, e) }
    }

    private fun applyDetail(generation: Int, detail: BusLineDetail) {
        consecutiveFailures = 0
        lastOperatingState = detail.operatingState
        trackingRepository.recordBuses(generation, detail.buses)

        val locationEnabled = locationTrackingAllowed && locationSource.hasPrecisePermission()
        val dropFix = trackingRepository.latestUserFix
        val nowMillis = clock.now().toEpochMilliseconds()
        val nowElapsed = monotonicClock.elapsedMillis()
        var effects: List<TrackingEngine.Effect> = emptyList()
        val applied = trackingRepository.update(generation) { current ->
            val reduced = TrackingEngine.reducePoll(
                session = current,
                detail = detail,
                locationEnabled = locationEnabled,
                serviceFix = serviceFix,
                dropFix = dropFix,
                nowMillis = nowMillis,
                nowElapsedMillis = nowElapsed
            )
            effects = reduced.effects
            if (reduced.effects.any { it is TrackingEngine.Effect.Finished }) null else reduced.session
        }
        if (!applied) return
        // 提醒先于刷新通知（和原来一样：先发到站 / 换乘提醒，再刷新常驻通知或切到下一程）
        effects.forEach(::runEffect)
        afterUpdate()
    }


    private fun applyFailure(generation: Int, error: Throwable) {
        if (error is CancellationException) throw error
        Log.w(TAG, "Failed to refresh tracking data", error)
        consecutiveFailures++
        val nowMillis = clock.now().toEpochMilliseconds()
        if (trackingRepository.update(generation) { TrackingEngine.reduceFailure(it, nowMillis) }) afterUpdate()
    }

    /** 请求用的城市：和当前选的城市一样就用它（带城市名），否则按会话里的 id 拼一个 */
    private fun cityFor(cityId: String, mygolbsCityCode: String, cityName: String = ""): TransitCity {
        val selectedCity = repository.selectedCity.value
        return if (selectedCity.cityId == cityId) {
            selectedCity
        } else {
            TransitCity(
                cityId = cityId,
                cityName = cityName,
                mygolbsCityCode = mygolbsCityCode.takeIf { it.isNotEmpty() }
            )
        }
    }

    /** 一起等等车阶段的一轮：按计划请求（条数不影响请求数），结果交给引擎 */
    private suspend fun pollWatch(
        generation: Int,
        target: TrackingTarget.WatchTogether,
        plan: WatchPollPlan
    ) {
        val city = cityFor(target.cityId, target.mygolbsCityCode, target.cityName)
        val version = bindingVersion
        val planned = when (plan) {
            is WatchPollPlan.Stop -> plan.lines
            is WatchPollPlan.Lines -> plan.lines
        }
        val official = planned.filter { it.source == DataSourceType.MEISHAN || it.lineId.startsWith("ms:") }
        coroutineScope {
            val semaphore = Semaphore(4)
            official.filter { it.lineId !in pushOnline }.map { line ->
                async {
                    semaphore.withPermit {
                        val revision = pushRevisions[line.lineId] ?: 0L
                        val result = withContext(Dispatchers.IO) {
                            repository.getLineDetail(
                                city = city, lineId = line.lineId, targetOrder = line.stopOrder,
                                stationId = line.stopIdOnLine.ifBlank { target.stopId },
                                source = line.source, lineNameHint = line.lineName, directionHint = line.direction
                            )
                        }
                        if (!isCurrent(generation) || version != bindingVersion || revision != (pushRevisions[line.lineId] ?: 0L)) return@withPermit
                        result.onSuccess { detail ->
                            val session = trackingRepository.session.value ?: return@onSuccess
                            applyWatch(generation, watchPollOf(line.lineId, detail))
                            if (version == bindingVersion && isCurrent(generation)) {
                                startPush(session, detail, line.stopOrder, line.stopIdOnLine.ifBlank { target.stopId }, watch = true)
                            }
                        }.onFailure { applyFailure(generation, it) }
                    }
                }
            }.awaitAll()
        }
        if (!isCurrent(generation) || version != bindingVersion) return
        val others = planned - official.toSet()
        if (others.isEmpty()) return
        val httpPlan = when (plan) {
            is WatchPollPlan.Stop -> WatchPollPlan.Stop(others)
            is WatchPollPlan.Lines -> WatchPollPlan.Lines(others)
        }
        val result = withContext(Dispatchers.IO) { watchPoller.poll(target, httpPlan, city) }
        if (!isCurrent(generation) || version != bindingVersion) return
        result.onSuccess { poll -> applyWatch(generation, poll) }
            .onFailure { applyFailure(generation, it) }
    }

    private fun applyWatch(generation: Int, poll: WatchPoll) {
        consecutiveFailures = 0
        // 站点接口没有线路运营状态：按运营中算，节奏只看最先到的那辆
        lastOperatingState = LineOperatingState.RUNNING
        trackingRepository.recordLineBuses(generation, poll.lines.mapValues { it.value.buses })

        val locationEnabled = locationTrackingAllowed && locationSource.hasPrecisePermission()
        val nowMillis = clock.now().toEpochMilliseconds()
        val nowElapsed = monotonicClock.elapsedMillis()
        var effects: List<TrackingEngine.Effect> = emptyList()
        val applied = trackingRepository.update(generation) { current ->
            val reduced = TrackingEngine.reduceWatchPoll(
                session = current,
                poll = poll,
                locationEnabled = locationEnabled,
                serviceFix = serviceFix,
                nowMillis = nowMillis,
                nowElapsedMillis = nowElapsed
            )
            effects = reduced.effects
            if (reduced.effects.any { it is TrackingEngine.Effect.Finished }) null else reduced.session
        }
        if (!applied) return
        effects.forEach(::runEffect)
        afterUpdate()
    }


    private fun targetOrderOf(session: TrackingSession): Int = when {
        session.state.legPhase == LegPhase.WAITING_BOARD && session.state.boardStopOrder > 0 -> session.state.boardStopOrder
        session.state.targetOrder > 0 -> session.state.targetOrder
        else -> session.leg.targetOrder
    }

    private fun contextOf(session: TrackingSession): String {
        val plan = TrackingEngine.watchPollPlan(session)
        val lines = when (plan) {
            is WatchPollPlan.Stop -> plan.lines
            is WatchPollPlan.Lines -> plan.lines
            null -> null
        }
        val target = if (lines != null) lines.joinToString { "${it.lineId}:${it.stopOrder}" }
        else if (session.leg.source == DataSourceType.MEISHAN || session.leg.lineId.startsWith("ms:")) {
            "${session.leg.lineId}:${targetOrderOf(session)}:${session.state.targetStopId}"
        } else session.leg.lineId
        return "${session.sessionId}:${session.generation}:${session.state.legPhase}:$target"
    }

    private fun watchPollOf(lineId: String, detail: BusLineDetail): WatchPoll = WatchPoll(
        requested = setOf(lineId),
        lines = mapOf(lineId to LinePoll(detail.buses, fromLineDetail = true, stops = detail.stops))
    )

    private fun startPush(session: TrackingSession, detail: BusLineDetail, order: Int, stationId: String, watch: Boolean) {
        val lineId = detail.line.lineId
        if (detail.line.source != DataSourceType.MEISHAN && !lineId.startsWith("ms:")) return
        if (detail.stops.isEmpty() || pushJobs[lineId]?.isActive == true) return
        val version = bindingVersion
        val generation = session.generation
        pushJobs[lineId] = lifecycleScope.launch {
            try {
                pushRepository.stream(detail, order, stationId).collect { update ->
                    if (!isCurrent(generation) || version != bindingVersion) return@collect
                    val fresh = update.detail
                    if (update.availability == MeishanPushAvailability.ONLINE && fresh?.line?.lineId == lineId) {
                        pushOnline.add(lineId)
                        pushRevisions[lineId] = (pushRevisions[lineId] ?: 0L) + 1L
                        if (watch) applyWatch(generation, watchPollOf(lineId, fresh)) else applyDetail(generation, fresh)
                    } else if (pushOnline.remove(lineId)) {
                        refreshWakeup.trySend(Unit)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                Log.w(TAG, "Tracking push unavailable", failure)
            } finally {
                if (version == bindingVersion && pushOnline.remove(lineId)) refreshWakeup.trySend(Unit)
            }
        }
    }

    private fun stopPush() {
        bindingVersion++
        pushJobs.values.forEach { it.cancel() }
        pushJobs.clear()
        pushOnline.clear()
        pushRevisions.clear()
        pushContext = null
        while (refreshWakeup.tryReceive().isSuccess) Unit
    }

    private fun updateWakeLock(session: TrackingSession) {
        val official = if (TrackingEngine.watchPollPlan(session) != null) {
            session.watchTarget?.lines?.any { it.source == DataSourceType.MEISHAN || it.lineId.startsWith("ms:") } == true
        } else session.leg.source == DataSourceType.MEISHAN || session.leg.lineId.startsWith("ms:")
        if (!official) { releaseWakeLock(); return }
        if (trackingWakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        trackingWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Trackr:OfficialTracking").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        trackingWakeLock?.let { if (it.isHeld) it.release() }
        trackingWakeLock = null
    }

    @VisibleForTesting
    internal val isTrackingWakeLockHeld: Boolean get() = trackingWakeLock?.isHeld == true

    private fun runEffect(effect: TrackingEngine.Effect) {
        when (effect) {
            is TrackingEngine.Effect.Alert -> when (effect.kind) {
                TrackingEngine.AlertKind.ARRIVED ->
                    arrivalAlerts.triggerArrivedAlert(this, effect.state, effect.forceStandalone)
                TrackingEngine.AlertKind.APPROACHING -> arrivalAlerts.triggerApproachingAlert(this, effect.state)
            }
            is TrackingEngine.Effect.TransferAlert -> {
                Log.d(TAG, "到换乘站 ${effect.stationName}，自动切换到下一程: ${effect.nextLineName}")
                arrivalAlerts.triggerTransferAlert(this, effect.stationName, effect.nextLineName)
            }
            TrackingEngine.Effect.Finished -> Log.d(TAG, "全部换乘程段已完成到达终点！")
        }
    }

    /** 服务自己写回仓库后：会话没了就停，切了程就重开轮询，否则刷新通知 */
    private fun afterUpdate() {
        val now = trackingRepository.session.value
        when {
            now == null -> shutdown()
            now.sessionId != attachedSessionId -> Unit
            now.generation != pollingGeneration -> attach(now)
            contextOf(now) != pushContext -> attach(now, force = true)
            else -> render(now)
        }
    }

    /** 刷新常驻通知与定位档位 */
    private fun render(session: TrackingSession, force: Boolean = false) {
        val state = session.state
        val watch = LiveProgressNotifier.WatchNotice.of(session)
        val content = state to watch
        if (!force && content == lastRendered) return
        lastRendered = content
        liveProgressNotifier.updateNotification(this, state, watch)
        updateLocationCadence(state)
    }

    /** 按追踪阶段调整定位频率；不需要时关闭定位 */
    private fun updateLocationCadence(state: TrackingSessionState) {
        val tier = TrackingEngine.locationTier(state, locationTrackingAllowed)
        if (tier == activeTier) return
        locationJob?.cancel()
        locationJob = null
        activeTier = LocationCadence.Tier.OFF
        if (tier == LocationCadence.Tier.OFF) return
        if (!locationSource.hasPrecisePermission()) return
        activeTier = tier
        locationJob = lifecycleScope.launch {
            locationSource.fixes(tier).collect { fix -> onServiceFix(fix) }
            // 没有可用的定位来源（流直接结束）：下次刷新时再试
            if (activeTier == tier) activeTier = LocationCadence.Tier.OFF
        }
    }

    private fun onServiceFix(fix: UserFix) {
        if (!TrackingEngine.shouldAcceptServiceFix(serviceFix, fix)) return
        serviceFix = fix
        // 服务定位同时喂给下车站辅助判定
        trackingRepository.updateUserFix(fix)
    }

    private fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
        activeTier = LocationCadence.Tier.OFF
        serviceFix = null
    }

    /** 会话结束：停轮询、关定位、撤通知、退出前台并停止服务 */
    private fun shutdown() {
        stopPush()
        releaseWakeLock()
        pollingJob?.cancel()
        pollingJob = null
        attachedSessionId = null
        pollingGeneration = NO_GENERATION
        lastRendered = null
        stopLocation()
        liveProgressNotifier.cancelNotification(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        stopPush()
        releaseWakeLock()
        pollingJob?.cancel()
        pollingJob = null
        stopLocation()
        // 服务被销毁（含系统回收）时结束正在追的会话，避免 UI 一直显示“追踪中”
        val current = trackingRepository.session.value
        if (current != null && current.sessionId == attachedSessionId && !current.interrupted) {
            runCatching { trackingRepository.stop() }
        }
        attachedSessionId = null
        pollingGeneration = NO_GENERATION
        liveProgressNotifier.cancelNotification(this)
        super.onDestroy()
    }

    /** 当前定位档位（测试读） */
    @VisibleForTesting
    internal val currentLocationTier: LocationCadence.Tier
        get() = activeTier

    companion object {
        private const val TAG = "BusTrackingService"
        private const val NO_GENERATION = -1

        const val ACTION_START_TRACKING = "com.trackr.action.START_TRACKING"
        const val EXTRA_SESSION_ID = "extra_session_id"

        /** 启动服务追踪仓库里的会话 [sessionId]（会话要先由 `TrackingRepository.start` 建好） */
        fun startIntent(context: Context, sessionId: String): Intent =
            Intent(context, BusTrackingService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
    }
}
