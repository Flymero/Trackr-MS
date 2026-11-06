package com.trackr.ui.screens.stopdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.model.TrackingSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 「上了哪辆？」导航弹层（`ScreenKey.ChooseBoardedBus`）的 ViewModel：只看 [sessionId] 这次会话的待选。
 * 通知点开、或站点页遇到待选时打开；选完、会话换了、待选没了都变成 [ChooseBoardedBusState.Closed]，弹层自己关。
 */
class ChooseBoardedBusViewModel(
    private val sessionId: String,
    trackingRepository: TrackingRepository,
    private val trackingController: TrackingController
) : ViewModel() {

    val state: StateFlow<ChooseBoardedBusState> = flow {
        // 冷启动从通知点进来时会话还在从盘里读：读完再判断，免得一打开就关
        trackingRepository.awaitLoaded()
        emitAll(trackingRepository.sessions.map { list -> stateOf(list.firstOrNull { it.sessionId == sessionId }) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChooseBoardedBusState.Loading)

    /** 选了 [busId]，null =「还没上」 */
    fun choose(busId: String?) {
        trackingController.chooseBoardedBus(busId, sessionId)
    }

    private fun stateOf(session: TrackingSession?): ChooseBoardedBusState = when {
        session == null || session.sessionId != sessionId -> ChooseBoardedBusState.Closed
        // 进程重建后读回的会话先标成中断，这时选了也不生效；等追踪服务接上
        session.interrupted -> ChooseBoardedBusState.Loading
        else -> boardChoiceUiOf(session)?.let(ChooseBoardedBusState::Showing) ?: ChooseBoardedBusState.Closed
    }
}

sealed interface ChooseBoardedBusState {
    data object Loading : ChooseBoardedBusState
    data class Showing(val choice: BoardChoiceUi) : ChooseBoardedBusState
    data object Closed : ChooseBoardedBusState
}
