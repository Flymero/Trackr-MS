package com.trackr.core.testing

import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.domain.platform.Announcer
import com.trackr.core.domain.platform.AppSigningInfo
import com.trackr.core.domain.platform.AppUpdateInstaller
import com.trackr.core.domain.platform.LocationSource
import com.trackr.core.domain.platform.ReminderAlarms
import com.trackr.core.domain.platform.SpeechInput
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.domain.platform.TransitCodeLauncher
import com.trackr.core.domain.platform.UpdateChecker
import com.trackr.core.domain.tracking.LocationCadence
import com.trackr.core.model.AppUpdateInfo
import com.trackr.core.model.AppUpdateInstallState
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCodeProvider
import com.trackr.core.model.UpdateCheckResult
import com.trackr.core.model.UserFix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow

/*
 * :core:domain 平台能力接口的 Fake：只记下调用，结果由测试设定。
 * 都不是线程安全的，只在单线程测试（runTest）里用。
 */

/**
 * 记下所有命令。传了 [repository] 时命令同时转给它（和 Android 实现一样，只是不启动服务），
 * 测试就能从仓库读到追踪状态。
 */
class FakeTrackingController(
    private val repository: TrackingRepository? = null
) : TrackingController {
    var stopCount: Int = 0
        private set
    var boardedCount: Int = 0
        private set
    var nextLegCount: Int = 0
        private set
    var cycleThresholdCount: Int = 0
        private set
    val started: MutableList<TrackingTarget> = mutableListOf()
    val fixes: MutableList<UserFix> = mutableListOf()

    /** [start] 的返回值（模拟后台不允许启动前台服务时设成 false） */
    var startResult: Boolean = true

    override fun start(target: TrackingTarget): Boolean {
        started += target
        if (!startResult) return false
        repository?.start(target)
        return true
    }

    /** 每次「结束」传的 sessionId（null = 全部 / 主会话） */
    val stoppedSessionIds: MutableList<String?> = mutableListOf()

    override fun stopTracking(sessionId: String?) {
        stopCount++
        stoppedSessionIds += sessionId
        repository?.stop(sessionId)
    }

    override fun confirmBoarded(sessionId: String?) {
        boardedCount++
        repository?.confirmBoarded(sessionId)
    }

    /** [chooseBoardedBus] 收到的选择（null =「还没上」） */
    val boardChoices: MutableList<String?> = mutableListOf()

    override fun chooseBoardedBus(busId: String?, sessionId: String?) {
        boardChoices += busId
        repository?.chooseBoardedBus(busId, sessionId)
    }

    /** 一起等里去掉的线（会话 id 到线 id） */
    val removedWatchLines: MutableList<Pair<String, String>> = mutableListOf()

    override fun removeWatchLine(sessionId: String, lineId: String) {
        removedWatchLines += sessionId to lineId
        repository?.removeWatchLine(sessionId, lineId)
    }

    override fun switchToNextLeg(sessionId: String?) {
        nextLegCount++
        repository?.switchToNextLeg(sessionId)
    }

    override fun cycleThreshold(sessionId: String?) {
        cycleThresholdCount++
        repository?.cycleThreshold(sessionId)
    }

    override fun updateUserFix(fix: UserFix) {
        fixes += fix
        repository?.updateUserFix(fix)
    }
}

/** 记下读过的句子；[isAvailable] 设成 false 模拟手机没有朗读引擎（不记、[speak] 返回 false） */
class FakeAnnouncer(var isAvailable: Boolean = true) : Announcer {
    val spoken: MutableList<String> = mutableListOf()
    var stopCount: Int = 0
        private set

    override suspend fun speak(text: String): Boolean {
        if (!isAvailable) return false
        spoken += text
        return true
    }

    override fun stop() {
        stopCount++
    }
}

class FakeTransitCodeLauncher(
    var installed: Set<TransitCodeProvider> = setOf(TransitCodeProvider.ALIPAY, TransitCodeProvider.UNIONPAY)
) : TransitCodeLauncher {
    val opened: MutableList<TransitCodeProvider> = mutableListOf()

    override fun open(provider: TransitCodeProvider): Boolean {
        if (provider == TransitCodeProvider.ASK_ALWAYS || provider !in installed) return false
        opened += provider
        return true
    }

    override fun isInstalled(provider: TransitCodeProvider): Boolean = provider in installed
}

/**
 * 定位 Fake：[emit] 发一个定位给正在收集的 [fixes]；档位为 OFF 时丢掉（和接口约定一致）。
 * 收集时记下请求的档位，方便断言定位频率。
 */
class FakeLocationSource(
    var lastKnownFix: UserFix? = null,
    var precisePermission: Boolean = true
) : LocationSource {
    private val live = MutableSharedFlow<UserFix>(extraBufferCapacity = 64)
    val requestedTiers: MutableList<LocationCadence.Tier> = mutableListOf()

    override fun fixes(tier: LocationCadence.Tier): Flow<UserFix> {
        requestedTiers += tier
        return if (tier == LocationCadence.Tier.OFF) emptyFlow() else live
    }

    /** 正在收集 [fixes] 的数量（断言定位已关掉时看它是不是 0） */
    val activeCollectors: Int
        get() = live.subscriptionCount.value

    /** 发出一个定位，同时更新 [lastKnownFix]；没人收集时只更新 [lastKnownFix] */
    fun emit(fix: UserFix) {
        lastKnownFix = fix
        live.tryEmit(fix)
    }

    override fun lastKnown(): UserFix? = lastKnownFix

    override fun hasPrecisePermission(): Boolean = precisePermission

    /** 有任一定位权限；默认跟着 [precisePermission] */
    var anyPermission: Boolean? = null

    override fun hasAnyPermission(): Boolean = anyPermission ?: precisePermission

    /** 各来源缓存的定位；为 null 时退回 [lastKnownFix] */
    var lastKnownFixList: List<UserFix>? = null

    override fun lastKnownFixes(): List<UserFix> = lastKnownFixList ?: listOfNotNull(lastKnownFix)

    /**
     * [currentFixes] 的行为：为 null 时返回 [live]（测试用 [emit] 推定位，永不结束）；
     * 设成一个列表时依次发出这些定位后结束（模拟窗口内收到的定位）。
     */
    var currentFixScript: List<UserFix>? = null

    /** 被调了几次 [currentFixes]（断言是否开了窗口） */
    var currentFixesCount: Int = 0
        private set

    override fun currentFixes(): Flow<UserFix> {
        currentFixesCount++
        val script = currentFixScript ?: return live
        return script.asFlow()
    }
}

/** 语音输入 Fake：依次返回 [answers] 里的结果，用完后返回 null */
class FakeSpeechInput(answers: List<String?> = emptyList()) : SpeechInput {
    private val queue = ArrayDeque(answers)
    var listenCount: Int = 0
        private set

    override suspend fun listen(): String? {
        listenCount++
        return queue.removeFirstOrNull()
    }
}

/**
 * 闹钟 Fake：[schedule] 记下「下一次触发时刻」，时刻由 [nextTriggerOf] 算（默认用提醒的时分凑一个假时刻，测试一般自己传）。
 */
class FakeReminderAlarms(
    var exactAllowed: Boolean = true,
    private val nextTriggerOf: (ScheduledReminder) -> Long = { it.hour * 3_600_000L + it.minute * 60_000L }
) : ReminderAlarms {
    /** 当前排着的闹钟：提醒 ID → 计划触发时刻 */
    val scheduled: MutableMap<String, Long> = linkedMapOf()

    override fun schedule(reminder: ScheduledReminder) {
        if (!reminder.enabled) {
            cancel(reminder.id)
            return
        }
        scheduled[reminder.id] = nextTriggerOf(reminder)
    }

    override fun cancel(reminderId: String) {
        scheduled.remove(reminderId)
    }

    override fun rescheduleAll(reminders: List<ScheduledReminder>) {
        reminders.forEach { schedule(it) }
    }

    override fun canScheduleExact(): Boolean = exactAllowed

    /** 「去设置」被调了几次；[openSettingsResult] 是返回值（false = 打不开） */
    var openSettingsCount: Int = 0
        private set
    var openSettingsResult: Boolean = true

    override fun openExactAlarmSettings(): Boolean {
        openSettingsCount++
        return openSettingsResult
    }

    override fun lastScheduledTriggerMillis(reminderId: String): Long? = scheduled[reminderId]
}

class FakeUpdateChecker(var result: UpdateCheckResult) : UpdateChecker {
    var checkCount: Int = 0
        private set

    override suspend fun checkForUpdates(currentVersionCode: Int, currentVersionName: String): UpdateCheckResult {
        checkCount++
        return result
    }
}

/**
 * 记下下载 / 安装调用；结果由测试设定，不碰文件也不碰系统安装器。
 *
 * @param progress 每次下载要回调的进度序列，用来验证「下载中」状态
 */
class FakeAppUpdateInstaller(
    var downloadResult: AppUpdateInstallState = AppUpdateInstallState.Launched,
    var retryResult: AppUpdateInstallState = AppUpdateInstallState.Launched,
    private val progress: List<Pair<Long, Long>> = emptyList()
) : AppUpdateInstaller {
    var downloadCount: Int = 0
        private set
    var retryCount: Int = 0
        private set
    var discardCount: Int = 0
        private set
    var openSettingsCount: Int = 0
        private set

    /** 设了就卡在下载结束前，用来观察「下载中」的中间状态；测试里 complete 后继续 */
    var downloadGate: CompletableDeferred<Unit>? = null

    override suspend fun downloadAndInstall(
        updateInfo: AppUpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): AppUpdateInstallState {
        downloadCount++
        progress.forEach { (downloaded, total) -> onProgress(downloaded, total) }
        downloadGate?.await()
        return downloadResult
    }

    override fun retryPendingInstall(): AppUpdateInstallState {
        retryCount++
        return retryResult
    }

    override fun openInstallPermissionSettings() {
        openSettingsCount++
    }

    override fun discardPending() {
        discardCount++
    }
}

class FakeAppSigningInfo(
    override val appId: String = "com.github.u5151561.trackr",
    private val sha1: String? = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD"
) : AppSigningInfo {
    override fun certificateSha1(): String? = sha1
}
