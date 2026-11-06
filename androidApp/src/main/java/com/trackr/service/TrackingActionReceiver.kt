package com.trackr.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trackr.core.data.repository.TrackingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 接收常驻通知栏 Live Updates 交互按钮的广播，直接改 [TrackingRepository]；
 * 追踪服务收集仓库里的会话，自己跟着停 / 切程 / 刷新通知。
 *
 * 第 36 步起每项追踪一条通知，按钮带 [EXTRA_SESSION_ID] 指明改哪一份。
 *
 * 两个坑都在这里兜住：
 * - **进程刚被拉起**（通知栏点按钮把 App 唤起来）时仓库还在读盘，这时候直接改会话是空操作，
 *   会把界面上所有追踪都弄丢、通知也关不掉。所以先 [TrackingRepository.awaitLoaded] 再改。
 * - **旧版本发出去的通知**没有 [EXTRA_SESSION_ID]。这种广播只作用在主会话上，绝不「全部结束」——
 *   不然点一下旧通知，正在追的几项会一起消失。
 */
class TrackingActionReceiver : BroadcastReceiver(), KoinComponent {

    private val trackingRepository: TrackingRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() }
        val watchLineId = intent.getStringExtra(EXTRA_WATCH_LINE_ID)?.takeIf { it.isNotBlank() }
        val pending = goAsync()
        receiverScope.launch {
            try {
                // 冷启动：盘里的会话读回来之前改会话会被「读盘完成」整个盖掉
                trackingRepository.awaitLoaded()
                apply(action, sessionId, watchLineId, context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle tracking action $action", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun apply(action: String, sessionId: String?, watchLineId: String?, context: Context) {
        when (action) {
            ACTION_STOP_TRACKING -> {
                // 没有 sessionId（旧通知）：只停主会话那一项，绝不全部结束
                val target = sessionId ?: trackingRepository.session.value?.sessionId ?: return
                if (watchLineId != null) {
                    // 一起等里的一条线：只去掉这条，别的线接着等
                    trackingRepository.removeWatchLine(target, watchLineId)
                    if (trackingRepository.sessionById(target)?.watchedLines
                            ?.any { it.line.lineId == watchLineId } != true
                    ) {
                        LiveProgressNotifier.cancelWatchLineNotification(context, target, watchLineId)
                    }
                    return
                }
                trackingRepository.stop(target)
                // 仓库里已经没有这一份了（用户先前已经结束过 / 服务已退出）：这条通知也没有主人了，
                // 直接撤掉，免得留一条点不动的常驻通知
                if (trackingRepository.sessionById(target) == null) {
                    LiveProgressNotifier.cancelSessionNotification(context, target)
                }
            }
            ACTION_CYCLE_THRESHOLD -> trackingRepository.cycleThreshold(sessionId ?: primaryId())
            ACTION_CONFIRM_BOARDED -> trackingRepository.confirmBoarded(sessionId ?: primaryId())
            ACTION_NEXT_LEG -> trackingRepository.switchToNextLeg(sessionId ?: primaryId())
        }
    }

    /** 旧通知没带 sessionId 时作用在主会话上（不会牵连别的追踪） */
    private fun primaryId(): String? = trackingRepository.session.value?.sessionId

    companion object {
        private const val TAG = "TrackingActionReceiver"

        /** 广播的处理时限是 10 秒，读盘一般几十毫秒；用应用级作用域，别被广播结束打断 */
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val ACTION_STOP_TRACKING = "com.trackr.action.STOP_TRACKING"
        const val ACTION_CYCLE_THRESHOLD = "com.trackr.action.CYCLE_THRESHOLD"
        const val ACTION_CONFIRM_BOARDED = "com.trackr.action.CONFIRM_BOARDED"
        const val ACTION_NEXT_LEG = "com.trackr.action.NEXT_LEG"

        /** 通知按钮属于哪一份追踪会话（没有 = 旧通知，作用于主会话） */
        const val EXTRA_SESSION_ID = "extra_session_id"

        /** 一起等里这条通知属于哪条线（有它时「结束」只去掉这条线） */
        const val EXTRA_WATCH_LINE_ID = "extra_watch_line_id"
    }
}
