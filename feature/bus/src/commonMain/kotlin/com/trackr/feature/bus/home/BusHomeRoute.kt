package com.trackr.feature.bus.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.core.navigation.ScreenKey

/**
 * 公交首页的 Route：接 ViewModel、轮询开关和导航，页面本身是无状态的 [BusHomeScreen]。
 *
 * @param screen 画哪个页面：标准版 [BusHomeScreen]，长辈版 `ElderBusHomeScreen`（§8.5：共用 ViewModel，只换页面）
 */
@Composable
fun BusHomeRoute(
    viewModel: BusHomeViewModel,
    navigator: TrackrNavigator,
    screen: @Composable (state: BusHomeUiState, onEvent: (BusHomeEvent) -> Unit) -> Unit = { state, onEvent ->
        BusHomeScreen(state = state, onEvent = onEvent)
    }
) {
    // 首页轮询只在 App 前台、首页显示着时进行（重构计划 §4.4）：首页 entry 在组合里就是「首页页签 + 在栈顶」，
    // 进详情 / 切页签时 entry 离开组合即停；STARTED 这一档沿用原来 shouldPollHome 的口径
    LifecycleStartEffect(viewModel) {
        viewModel.setPollingActive(true)
        onStopOrDispose { viewModel.setPollingActive(false) }
    }

    // 上一次定位失败的话，回到前台（多半刚从系统设置里开了定位 / 补了权限）静默再试一次，见 issue #34
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.retryLocationIfFailed() }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val onEvent: (BusHomeEvent) -> Unit = remember(viewModel, navigator, lifecycleOwner) {
        { event ->
            // 导航只在 RESUMED 时执行：防快速连点压两个页面（原 dropUnlessResumed）
            val canNavigate = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            when (event) {
                BusHomeEvent.OpenSearch -> if (canNavigate) navigator.open(ScreenKey.Search)
                is BusHomeEvent.OpenLine -> if (canNavigate) navigator.open(event.target.toKey())
                is BusHomeEvent.OpenStop -> if (canNavigate) navigator.open(event.target.toKey())
                // 「编辑」进编辑收藏页；路线卡进新的路线详情（第 22 步）
                BusHomeEvent.EditFavorites -> if (canNavigate) navigator.open(ScreenKey.FavoritesEdit)
                is BusHomeEvent.OpenRoute -> if (canNavigate) navigator.open(ScreenKey.RouteDetail(event.routeId))
                BusHomeEvent.OpenSettings -> if (canNavigate) navigator.open(ScreenKey.More)
                else -> viewModel.onEvent(event)
            }
        }
    }

    screen(state, onEvent)
}

private fun LineTarget.toKey(): ScreenKey.LineDetail = ScreenKey.LineDetail(
    cityId = cityId,
    lineId = lineId,
    targetOrder = targetOrder,
    stopId = stopId,
    source = source,
    lineName = lineName
)

private fun StopTarget.toKey(): ScreenKey.StopDetail = ScreenKey.StopDetail(
    cityId = cityId,
    stopId = stopId,
    stopName = stopName,
    physicalStId = physicalStId,
    namesakeStId = namesakeStId,
    lat = lat,
    lng = lng
)
