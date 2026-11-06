package com.trackr.service

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.UserFix
import com.trackr.core.model.withAlertThresholdStops
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * `:core:domain` 里 [TrackingController] 的 Android 实现：命令都写进 [TrackingRepository]，
 * 开始追踪时再用 sessionId 启动前台服务 [BusTrackingService]（服务收集仓库里的会话，结束 / 切程不用再发 Intent）。
 *
 * @param alertThresholdStops 读「我的」里的到站前提醒站数（第 25 步）；每次开始追踪都写进目标，
 *   线路页、站点页、路线、定时提醒的追踪都按它提醒
 * @param starter 启动前台服务（测试替换）；后台不允许时抛 IllegalStateException（Android 12+ 是
 *   ForegroundServiceStartNotAllowedException）或 SecurityException
 * @param appScope 应用级作用域：冷启动接续要等仓库把盘里的会话读完，不能阻塞 onStart（Koin 的 AppScopeQualifier）
 */
class AndroidTrackingController(
    context: Context,
    private val repository: TrackingRepository,
    private val arrivalAlerts: ArrivalAlertNotifier? = null,
    private val alertThresholdStops: () -> Int = { 2 },
    private val starter: (Context, String) -> Unit = { ctx, sessionId ->
        ContextCompat.startForegroundService(ctx, BusTrackingService.startIntent(ctx, sessionId))
    },
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : TrackingController {

    private val appContext: Context = context.applicationContext ?: context

    override fun start(target: TrackingTarget): Boolean {
        val previous = repository.sessionReplacedBy(target.withAlertThresholdStops(alertThresholdStops()))
        val session = repository.start(target.withAlertThresholdStops(alertThresholdStops())) ?: return false
        return try {
            starter(appContext, session.sessionId)
            true
        } catch (e: IllegalStateException) {
            val notAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            Log.w(TAG, "Cannot start tracking service (fgsNotAllowed=$notAllowed)", e)
            repository.restore(session, previous)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start tracking service", e)
            repository.restore(session, previous)
            false
        }
    }

    /**
     * 冷启动接续。仓库在 init 里异步读盘（[TrackingRepository.awaitLoaded]），onStart 时
     * `session.value` 往往还是 null，直接读会永远接不上；所以读盘没完成时先挂到 [appScope] 等读完再接续，
     * 不阻塞主线程。已经读过盘时同步判断，行为不变。
     *
     * @return 只有「已读盘、确有中断会话、且已启动成功」才返回 true；读盘未完成时返回 false
     *   （真正的接续在 [appScope] 里等读盘完成后进行）
     */
    override fun resumeInterrupted(): Boolean {
        repository.sessions.value.firstOrNull { it.interrupted }?.let { return startResume(it) }
        appScope.launch {
            repository.awaitLoaded()
            // 进程重建后可能有好几份中断的会话：服务起来后自己会把它们全部接上（见 BusTrackingService）
            val session = repository.sessions.value.firstOrNull { it.interrupted } ?: return@launch
            startResume(session)
        }
        return false
    }

    private fun startResume(session: TrackingSession): Boolean = try {
        starter(appContext, session.sessionId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot resume interrupted tracking", e)
        arrivalAlerts?.triggerTrackingInterrupted(appContext, session.state)
        false
    }

    override fun stopTracking(sessionId: String?) {
        repository.stop(sessionId)
    }

    override fun confirmBoarded(sessionId: String?) {
        repository.confirmBoarded(sessionId)
    }

    override fun chooseBoardedBus(busId: String?, sessionId: String?) {
        repository.chooseBoardedBus(busId, sessionId)
    }

    override fun removeWatchLine(sessionId: String, lineId: String) {
        repository.removeWatchLine(sessionId, lineId)
    }

    override fun switchToNextLeg(sessionId: String?) {
        repository.switchToNextLeg(sessionId)
    }

    override fun cycleThreshold(sessionId: String?) {
        repository.cycleThreshold(sessionId)
    }

    override fun updateUserFix(fix: UserFix) {
        repository.updateUserFix(fix)
    }

    private companion object {
        const val TAG = "TrackingController"
    }
}
