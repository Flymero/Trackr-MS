package com.trackr.feature.bus.elder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.domain.platform.TransitCodeLauncher
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCodeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 到站整屏提醒要显示的内容（设计稿「车到站」）。
 * @param key 哪次会话的哪辆车：用户关掉后同一辆车不再弹，下一辆车到站照样弹
 */
data class ArrivalAlertUi(
    val key: String,
    /** 哪一份追踪会话（「我上车了」只结束这一项） */
    val sessionId: String,
    val lineName: String,
    val endStop: String,
    val stopName: String
)

/**
 * 要不要弹到站整屏提醒（纯函数）：单线路追踪（长辈线路页的「车快到时叫我」）、追踪服务已经为这辆车发过「到站了」提醒时弹，
 * 和通知同一个时机。车开走（换下一辆或没车）就不弹了。
 *
 * 路线、一起等不弹：路线的到站可能是下车站、换乘站，一起等长辈版不做，「我上车了」的意思也不一样。
 * 进程被杀后读回、服务还没接上的会话（interrupted）也不弹，那时的状态是旧的。
 */
fun arrivalAlertOf(session: TrackingSession?): ArrivalAlertUi? {
    if (session == null || session.interrupted) return null
    if (session.target !is TrackingTarget.SingleLine) return null
    val state = session.state
    val busId = state.currentBusId
    if (!state.isActive || !state.hasTriggeredArrivedAlert || busId == null) return null
    return ArrivalAlertUi(
        key = "${session.sessionId}:$busId",
        sessionId = session.sessionId,
        lineName = state.lineName,
        endStop = state.endStopName,
        stopName = state.targetStopName
    )
}

/** 到站整屏提醒发出的事件 */
sealed interface ArrivalAlertEvent {
    /** 「出示乘车码」：选了默认平台直接打开，「每次询问」才弹选择 */
    data object OpenTransitCode : ArrivalAlertEvent
    data object DismissTransitCodeSheet : ArrivalAlertEvent
    data class PickTransitCode(val provider: TransitCodeProvider, val saveAsDefault: Boolean) : ArrivalAlertEvent

    /** 「我上车了」：长辈版上车就结束追踪（设计稿第 4 节，不追下车站） */
    data object Boarded : ArrivalAlertEvent

    /** 系统返回：只关掉这一次，不结束追踪 */
    data object Dismiss : ArrivalAlertEvent
}

/**
 * 长辈版到站整屏提醒（§8.1，第 34 步）：App 在前台时，车到站整屏变绿。响铃、震动和通知还是追踪服务发，这里只管画面。
 * 放在长辈版导航的最上层，哪一页都能弹。
 */
class ArrivalAlertViewModel(
    trackingRepository: TrackingRepository,
    private val trackingController: TrackingController,
    private val settings: SettingsRepository,
    private val transitCodeLauncher: TransitCodeLauncher
) : ViewModel() {

    private val dismissedKey = MutableStateFlow<String?>(null)

    val alert: StateFlow<ArrivalAlertUi?> = combine(trackingRepository.sessions, dismissedKey) { sessions, dismissed ->
        // 同时可能追着好几项：哪一项的单线路车到站就提醒哪一项
        sessions.firstNotNullOfOrNull { arrivalAlertOf(it) }?.takeIf { it.key != dismissed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _transitCodeSheetOpen = MutableStateFlow(false)
    val transitCodeSheetOpen: StateFlow<Boolean> = _transitCodeSheetOpen.asStateFlow()

    fun onEvent(event: ArrivalAlertEvent) {
        when (event) {
            ArrivalAlertEvent.OpenTransitCode -> when (val provider = settings.preferredTransitCodeProvider.value) {
                TransitCodeProvider.ASK_ALWAYS -> _transitCodeSheetOpen.value = true
                else -> transitCodeLauncher.open(provider)
            }
            ArrivalAlertEvent.DismissTransitCodeSheet -> _transitCodeSheetOpen.value = false
            is ArrivalAlertEvent.PickTransitCode -> {
                _transitCodeSheetOpen.value = false
                if (event.provider == TransitCodeProvider.ASK_ALWAYS) return
                if (event.saveAsDefault) viewModelScope.launch { settings.setPreferredTransitCodeProvider(event.provider) }
                transitCodeLauncher.open(event.provider)
            }
            ArrivalAlertEvent.Boarded -> {
                val current = alert.value
                dismissedKey.update { current?.key ?: it }
                trackingController.stopTracking(current?.sessionId)
            }
            ArrivalAlertEvent.Dismiss -> dismissedKey.update { alert.value?.key ?: it }
        }
    }
}
