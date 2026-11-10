package com.trackr.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.data.repository.TrackingPoller
import com.trackr.core.domain.platform.LocationSource
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.model.LegPhase
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.WatchedLine
import com.trackr.core.model.UserFix
import com.trackr.data.repository.ITransitRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.time.Clock

/**
 * 实时公交前台追踪服务：轮询每一份追踪会话的实时数据，维持每项一条常驻通知与到站提醒。
 *
 * 追踪状态都在 [TrackingRepository]（重构计划 §2.6）。第 36 步起可以同时追多项：仓库里是一份会话列表，
 * 这里**每一份会话一路轮询**（各自的代际号、失败退避、运营状态、过期判断），一台前台服务跑到底；
 * 每项一条常驻通知，**一起等里每条线也各一条**（各自有进度和「结束」，结束只去掉那条线），
 * 实时活动（上岛）只给最新开始的那一项的第一条。
 * 服务启动时只收 sessionId；轮询结果交给 [TrackingEngine] 算出新会话和副作用，经 [TrackingRepository.update]
 * 写回（核对 sessionId + 代际号，切程 / 停止后回来的旧结果直接丢）。会话没了就停那一路、撤那条通知；
 * 一项不剩才退出前台并停止服务。
 * START_STICKY：进程被杀后系统重建服务时从仓库读回会话接着追；系统不让重新进前台就结束这次追踪。
 *
 * 一起等（第 15 步）：等车阶段每轮按 [TrackingEngine.watchPollPlan] 请求（1 次站点接口；待确认时只请求在站的线），
 * 结果交给 [TrackingEngine.reduceWatchPoll]；上车后会话切到那条线（代际号 +1），之后和路线乘车一样只请求那条线。
 *
 * 线程约定：都在主线程（lifecycleScope），只有网络请求切到 IO。
 */
class BusTrackingService : LifecycleService() {

    private val trackingRepository: TrackingRepository by inject()
    private val repository: ITransitRepository by inject()
    private val settings: SettingsRepository by inject()
    private val liveProgressNotifier: LiveProgressNotifier by inject()
    private val arrivalAlerts: ArrivalAlertNotifier by inject()
    private val locationSource: LocationSource by inject()
    private val clock: Clock by inject()
    private val monotonicClock: MonotonicClock by inject()

    /**
     * 一路轮询：一份会话一个，互不影响（一项切程 / 失败退避 / 结束，不牵动别的项）。
     */
    private class PollWorker(val sessionId: String) {
        var job: Job? = null

        /** 正在轮询的代际号 */
        var generation: Int = NO_GENERATION

        /** 每条通知上次刷过的内容（key -> 内容），相同就不重复刷 */
        val rendered: MutableMap<String, Pair<TrackingSessionState, LiveProgressNotifier.WatchNotice?>> = mutableMapOf()

        /**
         * 每条通知的 id（key -> id）。一项追踪可能对应好几条通知：
         * 一起等等车时**每条线一条**，各自有进度、各自能单独结束。
         */
        val notifyIds: MutableMap<String, Int> = mutableMapOf()
    }

    /**
     * 一条常驻通知要显示什么。
     * @param key 这条通知的身份：单线路/路线 = 会话 id；一起等 = 「会话#线」
     * @param watchLineId 一起等里这条通知属于哪条线（点「结束」只去掉这条）
     */
    private class NotificationSpec(
        val key: String,
        val state: TrackingSessionState,
        val watch: LiveProgressNotifier.WatchNotice?,
        val nextLineName: String? = null,
        val watchLineId: String = ""
    )

    /** 一起等的取数（同一个实时仓库，条数不影响请求数） */
    private val poller by lazy {
        TrackingPoller(trackingRepository, repository, settings, clock, monotonicClock,
            locationEnabled = { locationTrackingAllowed && locationSource.hasPrecisePermission() },
            serviceFix = { serviceFix }, onEffect = ::runEffect,
            onExpired = { arrivalAlerts.triggerTrackingExpired(this, it.state) }, requestDispatcher = Dispatchers.IO)
    }

    /**
     * 「我的 → 上车后自动结束追踪」（issue #32）现在开着没。
     *
     * 三处要看它：前台服务要不要带 location 类型、定位档位（单线路平时不取定位）、轮询里判不判上车。
     * 每次现读：用户在「我的」里关掉后，下一次刷新就按关着办。
     */
    private val stopAfterBoarding: Boolean
        get() = runCatching { settings.autoStopAfterBoarding.value }.getOrDefault(true)

    /** sessionId -> 这一路的轮询状态 */
    private val workers = mutableMapOf<String, PollWorker>()

    /** 已经发出去的常驻通知 id（不管那一项还在不在），收摊时挨条撤掉，不留关不掉的旧通知 */
    private val postedNotifyIds = mutableSetOf<Int>()

    /** 前台服务（实时活动 / 上岛）绑在哪一份会话的通知上；null = 还没进前台 */
    private var foregroundSessionId: String? = null

    /** 本次前台服务是否带上了 location 类型（没有就拿不到锁屏后的定位，上车检测关闭） */
    private var locationTrackingAllowed: Boolean = false

    /** 当前前台通知挂在哪个 id 上（一起等按线拆开后要跟着换） */
    private var foregroundNotifyId: Int = LiveProgressNotifier.NOTIFICATION_ID_LIVE_PROGRESS

    /** 当前定位档位与收集它的协程（所有会话取最勤的那一档） */
    private var activeTier: LocationCadence.Tier = LocationCadence.Tier.OFF
    private var locationJob: Job? = null

    /** 服务自己最近一次采用的定位（判上车用） */
    private var serviceFix: UserFix? = null

    /** 上次补带 location 类型的单调时刻；-1 = 还没试过 */
    private var lastLocationUpgradeAttemptMillis: Long = -1L

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
            trackingRepository.sessions.collect { onSessionsChanged(it) }
        }
        // 追踪中途才打开「上车后自动结束追踪」：前台服务当初没带 location 类型，要补上，否则这次追踪判不了上车（issue #41）
        lifecycleScope.launch(Dispatchers.Main) {
            settings.autoStopAfterBoarding.collect { upgradeLocationTypeIfNeeded(force = true) }
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
        val sessions = trackingRepository.sessions.value.filter { it.state.isActive }
        val session = sessions.firstOrNull { it.sessionId == sessionId }
        // startForegroundService 要求尽快 startForeground：即使这一份已经没了，也要先进前台再停。
        // 已经在追别的会话时（启动请求过时了），前台通知仍显示最新开始的那一项
        val shown = session ?: sessions.lastOrNull()
        val notification = notificationOf(shown)
        val wantLocation = sessions.any { TrackingEngine.wantsLocation(it.target, stopAfterBoarding) } &&
            locationSource.hasPrecisePermission()
        locationTrackingAllowed = try {
            enterForeground(notification, wantLocation, notifyIdOf(shown))
        } catch (e: IllegalStateException) {
            endStartFailure(session, e)
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            endStartFailure(session, e)
            return START_NOT_STICKY
        }
        foregroundSessionId = shown?.sessionId
        if (session == null) {
            // 启动请求发出后用户又结束了（或换了新会话，新会话自己会再来一次启动请求）
            if (shown == null) {
                shutdown()
                return START_NOT_STICKY
            }
            render(shown, force = true)
            return START_STICKY
        }
        val resumed = if (session.interrupted) trackingRepository.resume(session.sessionId) else session
        if (resumed == null) {
            if (trackingRepository.sessions.value.none { it.state.isActive }) {
                shutdown()
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        attach(resumed)
        return START_STICKY
    }

    /**
     * 进不了前台时的收尾。
     *
     * 中断会话（进程重建读回来的）保留下来，发一条「追踪已中断」提醒，等用户打开 App 再接续——
     * 会话本身没进行过，用户主动决定要不要继续。
     *
     * 正常会话是用户刚点下开始追踪的：服务起不来就没人轮询，留着会话只会让界面一直显示「追踪中」，
     * 所以直接结束全部追踪（发一条「追踪已中断」说明原因）。不这么做的话，[AndroidTrackingController.start]
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
            // 进不了前台就没人轮询这一次新开的追踪：只结束这一项，别的项还在追，不能一起停
            runCatching { trackingRepository.stop(session.sessionId) }
        } else {
            Log.w(TAG, "Cannot enter foreground while resuming tracking", error)
        }
        // 还有别的项在追就继续跑（服务已经在前台了），一项不剩才收摊
        val othersAlive = trackingRepository.sessions.value.any {
            it.sessionId != session.sessionId && !it.interrupted && it.state.isActive
        }
        if (!othersAlive) shutdown()
    }

    private fun handleRestart(): Int {
        lifecycleScope.launch {
            trackingRepository.awaitLoaded()
            val stored = trackingRepository.sessions.value.filter { it.state.isActive }
            if (stored.isEmpty()) {
                shutdown()
                return@launch
            }
            val focus = stored.last()
            val wantLocation = stored.any { TrackingEngine.wantsLocation(it.target, stopAfterBoarding) } &&
                locationSource.hasPrecisePermission()
            val notification = notificationOf(focus)
            locationTrackingAllowed = try {
                enterForeground(notification, wantLocation, notifyIdOf(focus))
            } catch (e: IllegalStateException) {
                // 系统不允许从后台重启前台服务：保留中断会话，等用户打开 App 再接续
                Log.w(TAG, "Cannot restart tracking in foreground", e)
                stored.forEach { arrivalAlerts.triggerTrackingInterrupted(this@BusTrackingService, it.state) }
                shutdown()
                return@launch
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot restart tracking in foreground", e)
                stored.forEach { arrivalAlerts.triggerTrackingInterrupted(this@BusTrackingService, it.state) }
                shutdown()
                return@launch
            }
            foregroundSessionId = focus.sessionId
            // 全部接上（代际号 +1）；接上后 collect 会把每一路轮询都拉起来
            if (trackingRepository.resumeAll().isEmpty()) {
                if (trackingRepository.sessions.value.none { it.state.isActive }) shutdown()
            }
        }
        return START_STICKY
    }

    /**
     * 进入前台。带 location 类型失败时（如从后台启动、Android 14+ 不允许使用期间定位）退回只用 specialUse。
     * @return 是否成功带上了 location 类型
     */
    private fun enterForeground(notification: Notification, wantLocation: Boolean, notificationId: Int): Boolean {
        val baseType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        if (wantLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val withLocation = baseType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            try {
                ServiceCompat.startForeground(this, notificationId, notification, withLocation)
                lastForegroundServiceType = withLocation
                foregroundNotifyId = notificationId
                postedNotifyIds += notificationId
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "Location FGS type not allowed, tracking without location", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Location FGS type rejected, tracking without location", e)
            }
        }
        ServiceCompat.startForeground(this, notificationId, notification, baseType)
        lastForegroundServiceType = baseType
        foregroundNotifyId = notificationId
        postedNotifyIds += notificationId
        return false
    }

    /** 仓库里的会话列表变了：对齐每一路轮询和通知 */
    private fun onSessionsChanged(sessions: List<TrackingSession>) {
        val live = sessions.filter { !it.interrupted && it.state.isActive }
        val liveIds = live.mapTo(mutableSetOf()) { it.sessionId }
        // 会话没了（用户结束、走完、过期）：先停那一路轮询（通知等前台换人之后再撤，
        // 免得把正在当前台通知的那条先撤掉，留下一个没有通知的前台服务）
        val gone = workers.keys.filterNot { it in liveIds }
        val endedWorkers = mutableListOf<PollWorker>()
        gone.forEach { sessionId ->
            workers.remove(sessionId)?.let { worker ->
                worker.job?.cancel()
                worker.job = null
                endedWorkers += worker
            }
            // 这一项没了，它留下的到站提醒也没有主人了：一起撤掉，别让「车辆已到站」在通知栏
            // 和锁屏上等用户手动点掉（#28）。常驻通知等前台换人之后再撤，提醒不用等。
            ArrivalAlertNotifier.cancelSessionAlerts(this, sessionId)
            if (foregroundSessionId == sessionId) foregroundSessionId = null
        }
        // 新增 / 换代：接上或重开这一路（通知 id 跟会话绑定，不用挪）
        live.forEach { session ->
            val worker = workers.getOrPut(session.sessionId) { PollWorker(session.sessionId) }
            when {
                worker.job?.isActive != true -> attach(session)
                worker.generation != session.generation -> attach(session)
                else -> render(session)
            }
        }
        syncForeground(live)
        // 前台通知已经换到还活着的那一项身上了，这才撤掉结束那几项的通知
        endedWorkers.forEach { worker ->
            worker.notifyIds.values.forEach { liveProgressNotifier.cancelNotification(this, it) }
        }
        // 旧版本留下的那条（id 10001，按钮不带 sessionId）：只要还有追踪在跑就清掉它，
        // 不然它会一直躺在通知栏，点它的「结束」还可能把别的项一起停掉
        if (live.isNotEmpty()) liveProgressNotifier.cancelNotification(this)
        upgradeLocationTypeIfNeeded(force = false)
        updateLocationCadence()
        if (live.isEmpty() && sessions.none { it.interrupted }) {
            shutdown()
        }
    }

    /** 同时追多项时，前台服务（实时活动、定位类型）挂在最新开始的那一项上 */
    private fun syncForeground(live: List<TrackingSession>) {
        val focus = live.lastOrNull()
        val focusId = focus?.sessionId
        if (focusId == foregroundSessionId) return
        if (focus == null) {
            foregroundSessionId = null
            return
        }
        val wantLocation = live.any { TrackingEngine.wantsLocation(it.target, stopAfterBoarding) } &&
            locationSource.hasPrecisePermission()
        foregroundSessionId = focusId
        locationTrackingAllowed = try {
            enterForeground(notificationOf(focus), wantLocation, notifyIdOf(focus))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot move foreground tracking to another session", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot move foreground tracking to another session", e)
            false
        }
        // 换焦点后每条通知都按新身份重画一遍（原来那条从前台通知变成普通常驻通知）
        live.forEach { render(it, force = true) }
    }

    /** 接上一个会话（或同一会话的新一代）：重置轮询状态、刷新通知、重开轮询 */
    private fun attach(session: TrackingSession) {
        val worker = workers.getOrPut(session.sessionId) { PollWorker(session.sessionId) }
        if (worker.generation == session.generation && worker.job?.isActive == true) {
            // 同一代又来一次启动请求（前台服务类型可能变了）：只刷新通知和定位档位
            render(session, force = true)
            return
        }
        worker.job?.cancel()
        worker.job = null
        if (worker.generation != NO_GENERATION && worker.generation != session.generation && workers.size <= 1) {
            // 切了程：上一次的服务定位不再用（新开会话时不清空，保留刚拿到的定位供首轮判断）
            serviceFix = null
        }
        if (serviceFix == null && locationTrackingAllowed && locationSource.hasPrecisePermission()) {
            locationSource.lastKnownFixes().forEach { onServiceFix(it) }
        }
        worker.generation = session.generation
        render(session, force = true)
        startPolling(worker)
    }

    private fun startPolling(worker: PollWorker) {
        worker.job?.cancel()
        worker.job = lifecycleScope.launch(Dispatchers.Main) {
            poller.run(worker.sessionId, worker.generation) { afterUpdate(worker) }
        }
    }

    private fun sessionOf(sessionId: String): TrackingSession? = trackingRepository.sessionById(sessionId)

    private fun runEffect(effect: TrackingEngine.Effect, sessionId: String) {
        when (effect) {
            is TrackingEngine.Effect.Alert -> when (effect.kind) {
                TrackingEngine.AlertKind.ARRIVED ->
                    arrivalAlerts.triggerArrivedAlert(this, effect.state, effect.forceStandalone, sessionId)
                TrackingEngine.AlertKind.APPROACHING ->
                    arrivalAlerts.triggerApproachingAlert(this, effect.state, sessionId)
                TrackingEngine.AlertKind.MISSED ->
                    arrivalAlerts.triggerMissedBusAlert(this, effect.state, sessionId)
            }
            is TrackingEngine.Effect.TransferAlert -> {
                Log.d(TAG, "到换乘站 ${effect.stationName}，自动切换到下一程: ${effect.nextLineName}")
                arrivalAlerts.triggerTransferAlert(this, effect.stationName, effect.nextLineName, sessionId)
            }
            TrackingEngine.Effect.Finished.Completed -> Log.d(TAG, "全部换乘程段已完成到达终点！")
            // 定位判出已上车：会话马上就被清掉，先跟用户交代一句，免得「追踪中」自己消失了（issue #32）
            is TrackingEngine.Effect.Finished.Boarded -> {
                Log.d(TAG, "定位判断已上车，结束追踪: ${effect.state.lineName}")
                arrivalAlerts.triggerEndedAfterBoarding(this, effect.state, sessionId)
            }
        }
    }

    /** 服务自己写回仓库后：这一路没了就收摊，切了程就重开轮询，否则刷新通知 */
    private fun afterUpdate(worker: PollWorker) {
        val now = sessionOf(worker.sessionId)
        when {
            now == null -> {
                worker.job?.cancel()
                // 留给 onSessionsChanged 统一收尾：先把前台交给仍在追的项，再撤通知。
                // 提前移除 worker 会使 collect 漏掉这份会话，前台通知撤不掉后也无人重试。
            }
            now.generation != worker.generation -> attach(now)
            else -> render(now)
        }
    }

    /**
     * 刷新这一项的所有常驻通知与定位档位。
     *
     * 一起等等车时**每条线一条通知**（各自有到站进度和「结束」，结束只去掉那条线）；
     * 单线路、路线、以及一起等上车后只有一条。
     */
    private fun render(session: TrackingSession, force: Boolean = false) {
        val worker = workers.getOrPut(session.sessionId) { PollWorker(session.sessionId) }
        val specs = notificationsOf(session)
        val keep = specs.mapTo(mutableSetOf()) { it.key }
        // 前台通知要跟着换到新的第一条上：一起等刚拿到线、从「总通知」拆成按线通知时，
        // 旧的前台通知在服务还是前台时系统不让撤，必须先挪走
        if (session.sessionId == foregroundSessionId && specs.isNotEmpty()) {
            val firstId = liveProgressNotifier.notificationIdOf(specs.first().key)
            if (firstId != foregroundNotifyId) {
                val wantLocation = TrackingEngine.wantsLocation(session.target, stopAfterBoarding) &&
                    locationSource.hasPrecisePermission()
                runCatching { enterForeground(notificationOf(session), wantLocation, firstId) }
                    .onSuccess { locationTrackingAllowed = it }
                    .onFailure { Log.w(TAG, "Cannot move foreground notification to the new one", it) }
            }
        }
        // 这一项不再需要的通知（一起等里去掉了一条线）：撤掉
        worker.rendered.keys.filterNot { it in keep }.toList().forEach { key ->
            worker.rendered.remove(key)
            worker.notifyIds.remove(key)?.let { liveProgressNotifier.cancelNotification(this, it) }
        }
        // 实时活动（上岛）只给这一项的第一条通知
        val promotionKey = specs.firstOrNull()?.key
        specs.forEach { spec ->
            val content = spec.state to spec.watch
            if (!force && worker.rendered[spec.key] == content) return@forEach
            worker.rendered[spec.key] = content
            val notificationId = liveProgressNotifier.notificationIdOf(spec.key)
            worker.notifyIds[spec.key] = notificationId
            postedNotifyIds += notificationId
            liveProgressNotifier.updateNotification(
                context = this,
                state = spec.state,
                watch = spec.watch,
                sessionId = session.sessionId,
                allowPromotion = session.sessionId == foregroundSessionId && spec.key == promotionKey,
                nextLineName = spec.nextLineName,
                notificationId = notificationId,
                watchLineId = spec.watchLineId
            )
        }
        updateLocationCadence()
    }

    /** 这一项要显示哪几条通知 */
    private fun notificationsOf(session: TrackingSession): List<NotificationSpec> {
        val watch = session.watchTarget
        val watched = session.watchedLines
        // 一起等等车：每条线一条通知（用户要的是每条线单独，能各自结束）
        if (watch != null && watched.isNotEmpty() && session.state.legPhase == LegPhase.WAITING_BOARD) {
            return watched.map { line ->
                NotificationSpec(
                    key = LiveProgressNotifier.watchLineKey(session.sessionId, line.line.lineId),
                    state = watchLineState(session, line),
                    watch = null,
                    watchLineId = line.line.lineId
                )
            }
        }
        return listOf(
            NotificationSpec(
                key = session.sessionId,
                state = session.state,
                watch = LiveProgressNotifier.WatchNotice.of(session),
                nextLineName = TrackingEngine.nextLegLineName(session)
            )
        )
    }

    /**
     * 一起等里一条线的通知状态：线名、终点、站序、那辆车的位置和还有几站都是这条线自己的
     * （会话的 state 只有排第一的那条线的数据）。
     */
    private fun watchLineState(session: TrackingSession, line: WatchedLine): TrackingSessionState {
        val watch = session.watchTarget ?: return session.state
        val state = session.state
        val stopOrder = line.line.stopOrder
        return state.copy(
            lineId = line.line.lineId,
            lineName = line.line.lineName,
            endStopName = line.line.endStopName,
            targetStopId = watch.stopId.ifBlank { state.targetStopId },
            targetStopName = watch.stopName.ifBlank { state.targetStopName },
            boardStopName = watch.stopName.ifBlank { state.boardStopName },
            boardStopOrder = stopOrder,
            targetOrder = stopOrder,
            startTrackingOrder = (stopOrder - 1).coerceAtLeast(1),
            totalStops = if (line.stopsRemaining >= 0) stopOrder + line.stopsRemaining else state.totalStops,
            currentBusId = line.busId,
            currentBusOrder = line.busOrder,
            currentBusProgress = line.busProgress,
            stopsRemaining = line.stopsRemaining,
            etaSeconds = line.etaSeconds,
            isBusArrivedAtStop = line.isArrived,
            legPhase = LegPhase.WAITING_BOARD,
            totalLegs = 1,
            // 一起等是一条通知一条线：文案里写「第 1/1 程」，不是换乘
            currentLegIndex = 0,
            groupId = null,
            groupName = null,
            isSignalLost = false,
            statusSummary = line.statusText.ifBlank {
                when {
                    line.busId != null -> state.statusSummary
                    line.hasData -> "暂无来车"
                    else -> "正在连接实时公交数据…"
                }
            }
        )
    }

    /** 一项追踪的第一条通知 id：进前台、重启、换焦点时用它 */
    private fun notifyIdOf(session: TrackingSession?): Int =
        if (session == null) {
            LiveProgressNotifier.NOTIFICATION_ID_LIVE_PROGRESS
        } else {
            liveProgressNotifier.notificationIdOf(notificationsOf(session).first().key)
        }

    /** 撤掉所有发出去过的常驻通知（收摊 / 被销毁时用） */
    private fun cancelPostedNotifications() {
        postedNotifyIds.forEach { liveProgressNotifier.cancelNotification(this, it) }
        postedNotifyIds.clear()
        // 旧版本那条（10001）也一起清掉，免得升级后残留一条点不动的常驻通知
        liveProgressNotifier.cancelNotification(this)
    }

    /** 一份会话的常驻通知（进前台、重启、换焦点时用；一起等用第一条线的） */
    private fun notificationOf(session: TrackingSession?): Notification =
        if (session == null) {
            liveProgressNotifier.buildNotification(
                this,
                TrackingSessionState(isActive = true),
                sessionId = ""
            )
        } else {
            val spec = notificationsOf(session).first()
            liveProgressNotifier.buildNotification(
                context = this,
                state = spec.state,
                nextLineName = spec.nextLineName,
                watch = spec.watch,
                sessionId = session.sessionId,
                allowPromotion = true,
                watchLineId = spec.watchLineId
            )
        }

    /**
     * 前台服务该带 location 类型却没带上时再试一次（issue #41「上车检测不一定生效」）。
     *
     * 没带上的情形：开始追踪时开关还关着；进程被杀后系统在后台重建服务，Android 14+ 不让后台进程拿 location 类型。
     * 之前只在进前台那一刻决定一次，这一整次追踪都判不了上车。现在会话每次变化时补试（用户回到 App、
     * 或在「我的」里打开开关时就能成功）；不是开关触发的补试最多一分钟一次。
     */
    private fun upgradeLocationTypeIfNeeded(force: Boolean) {
        if (locationTrackingAllowed) return
        val focusId = foregroundSessionId ?: return
        val live = trackingRepository.sessions.value.filter { !it.interrupted && it.state.isActive }
        val focus = live.firstOrNull { it.sessionId == focusId } ?: return
        if (live.none { TrackingEngine.wantsLocation(it.target, stopAfterBoarding) }) return
        if (!locationSource.hasPrecisePermission()) return
        val now = monotonicClock.elapsedMillis()
        if (!force && lastLocationUpgradeAttemptMillis >= 0L &&
            now - lastLocationUpgradeAttemptMillis < LOCATION_UPGRADE_RETRY_MILLIS
        ) return
        lastLocationUpgradeAttemptMillis = now
        locationTrackingAllowed = try {
            enterForeground(notificationOf(focus), wantLocation = true, notificationId = foregroundNotifyId)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot add location type to foreground tracking", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot add location type to foreground tracking", e)
            false
        }
        if (locationTrackingAllowed) {
            locationSource.lastKnownFixes().forEach { onServiceFix(it) }
            updateLocationCadence()
        }
    }

    /** 按所有会话里最勤的那一档调整定位频率；都不需要时关闭定位 */
    private fun updateLocationCadence() {
        val tier = if (!locationTrackingAllowed) {
            LocationCadence.Tier.OFF
        } else {
            trackingRepository.sessions.value
                .filter { !it.interrupted && it.state.isActive }
                .map { TrackingEngine.locationTier(it.state, locationEnabled = true, stopAfterBoarding = stopAfterBoarding) }
                .filter { it != LocationCadence.Tier.OFF }
                .minByOrNull { it.intervalMillis }
                ?: LocationCadence.Tier.OFF
        }
        if (tier == activeTier) return
        locationJob?.cancel()
        locationJob = null
        activeTier = LocationCadence.Tier.OFF
        if (tier == LocationCadence.Tier.OFF) return
        if (!locationSource.hasPrecisePermission()) return
        activeTier = tier
        locationSource.lastKnownFixes().forEach { onServiceFix(it) }
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

    /** 一项都不剩：停所有轮询、关定位、退出前台、撤所有通知并停止服务 */
    private fun shutdown() {
        workers.values.forEach { it.job?.cancel() }
        workers.clear()
        foregroundSessionId = null
        stopLocation()
        // 顺序很重要：前台通知在服务还处于前台时撤不掉（系统不让），要先退出前台再撤，
        // 否则那条通知会一直留在通知栏，点它的按钮也再没会话可改
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        cancelPostedNotifications()
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        workers.values.forEach { it.job?.cancel() }
        workers.clear()
        stopLocation()
        // 服务被销毁（含系统回收）时结束正在追的会话，避免 UI 一直显示“追踪中”
        val active = trackingRepository.sessions.value.filter { !it.interrupted }
        active.forEach { runCatching { trackingRepository.stop(it.sessionId) } }
        // 会话没了，这些追踪留下的到站提醒也跟着撤（#28）
        active.forEach { ArrivalAlertNotifier.cancelSessionAlerts(this, it.sessionId) }
        foregroundSessionId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        cancelPostedNotifications()
        super.onDestroy()
    }

    /** 当前定位档位（测试读） */
    @VisibleForTesting
    internal val currentLocationTier: LocationCadence.Tier
        get() = activeTier

    companion object {
        private const val TAG = "BusTrackingService"
        private const val NO_GENERATION = -1

        /** 补带 location 类型失败后，至少隔这么久再试 */
        private const val LOCATION_UPGRADE_RETRY_MILLIS = 60_000L

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
