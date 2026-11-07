package com.trackr.feature.bus

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.feature.bus.elder.ArrivalAlertViewModel
import com.trackr.feature.bus.elder.ElderBusHomeScreen
import com.trackr.feature.bus.home.BusHomeRoute
import com.trackr.feature.bus.home.BusHomeViewModel
import com.trackr.ui.navigation.LocalAppViewModelStoreOwner
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 公交首页（公交页签根页）的导航入口（`:composeApp` 组装）。第 19 步起页面在 commonMain，Android / iOS 共用。
 */
fun EntryProviderScope<NavKey>.busEntries(navigator: TrackrNavigator) {
    entry<ScreenKey.Home> {
        // Activity 级的首页 ViewModel（权限回调、TrackrRoot 首帧关轮询也用它），和原来一样
        val viewModel: BusHomeViewModel = koinViewModel(viewModelStoreOwner = LocalAppViewModelStoreOwner.current)
        BusHomeRoute(viewModel = viewModel, navigator = navigator)
    }
}

/**
 * 长辈版首页（§8.4、8.5，第 32 步）：同一个 Key、同一个 Activity 级 ViewModel，只换成 [ElderBusHomeScreen]。
 * 右上角「设置」进「我的」（长辈版没有底部页签）。
 */
fun EntryProviderScope<NavKey>.elderBusEntries(navigator: TrackrNavigator) {
    entry<ScreenKey.Home> {
        val viewModel: BusHomeViewModel = koinViewModel(viewModelStoreOwner = LocalAppViewModelStoreOwner.current)
        BusHomeRoute(viewModel = viewModel, navigator = navigator) { state, onEvent ->
            ElderBusHomeScreen(state = state, onEvent = onEvent)
        }
    }
}

/** 首页的 Koin 定义；仍取 Activity 级的一个实例 */
val busModule = module {
    viewModel {
        // 补刷判断用的单调时钟：原来直接读 SystemClock.elapsedRealtime()，Android 上注入的就是它
        val clock = get<MonotonicClock>()
        BusHomeViewModel(
            settings = get(),
            favorites = get(),
            realtime = get(),
            userLocationStore = get(),
            locationRepository = get(),
            trackingController = get(),
            trackingRepository = get(),
            transitCodeLauncher = get(),
            systemSettings = get(),
            elapsedRealtimeMillis = { clock.elapsedMillis() },
            homeCoordinates = get(),
            announcer = get()
        )
    }
    // 长辈版到站整屏提醒（第 34 步），长辈导航最上层取一个
    viewModel {
        ArrivalAlertViewModel(
            trackingRepository = get(),
            trackingController = get(),
            settings = get(),
            transitCodeLauncher = get()
        )
    }
}
