package com.trackr.feature.route

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.trackr.core.data.repository.AmapRouteGateway
import com.trackr.core.domain.platform.AmapSdkKeyApplier
import com.trackr.core.domain.platform.MapSdkInitializer
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.data.remote.amap.AmapSdkTransitService
import com.trackr.data.remote.amap.AndroidAmapRouteGateway
import com.trackr.data.remote.amap.AndroidAmapSdkKeyApplier
import com.trackr.data.remote.amap.AndroidMapSdkInitializer
import com.trackr.ui.navigation.LocalAppViewModelStoreOwner
import com.trackr.ui.screens.routedetail.RouteDetailRoute
import com.trackr.ui.screens.routedetail.RouteDetailViewModel
import com.trackr.ui.screens.routeplan.RoutePlanRoute
import com.trackr.ui.screens.routeplan.RoutePlanViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.dsl.module

actual fun EntryProviderScope<NavKey>.routeEntries(navigator: TrackrNavigator) {
    entry<ScreenKey.RoutePlan> {
        // 原来 MainActivity 在 NavDisplay 外取一个 FavoritesGroupViewModel 给规划页和收藏页共用；拆开后仍取 Activity 级
        val appOwner = LocalAppViewModelStoreOwner.current
        val routePlanViewModel: RoutePlanViewModel = koinViewModel(viewModelStoreOwner = appOwner)
        val routeDetailViewModel: RouteDetailViewModel = koinViewModel(viewModelStoreOwner = appOwner)
        val lifecycleOwner = LocalLifecycleOwner.current
        // 第 24 步：页面在 commonMain（RoutePlanRoute），地图会话从 LocalAmapMapSession 取（RouteMap 的 Android 实现里）
        RoutePlanRoute(
            viewModel = routePlanViewModel,
            routeDetailViewModel = routeDetailViewModel,
            // 「去看看」：切到首页页签，让编辑收藏页成为栈顶（已有就退到它，没有就回根再打开）
            onNavigateToFavorites = dropUnlessResumed {
                navigator.showInTab(ScreenKey.Home, ScreenKey.FavoritesEdit)
            },
            onNavigateToLineDetail = { line, _ ->
                // 只在 RESUMED 时跳，防连点重复入栈（同 dropUnlessResumed）
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    navigator.open(line.toLineDetailKey())
                }
            }
        )
    }

    // 路线详情（第 22 步）：和规划页共用一个 RouteDetailViewModel，追踪状态两边一致
    entry<ScreenKey.RouteDetail> { key ->
        val appOwner = LocalAppViewModelStoreOwner.current
        val routeDetailViewModel: RouteDetailViewModel = koinViewModel(viewModelStoreOwner = appOwner)
        RouteDetailRoute(groupId = key.groupId, viewModel = routeDetailViewModel, navigator = navigator)
    }
}

/** 高德 Android SDK 相关的绑定（§2.9：SDK 封装在 :feature:route 的 androidMain） */
val routeAndroidModule = module {
    // SDK 优先、失败才走 Web 的高德通道（原 FavoritesGroupViewModel 的内部类）；SDK 建不出来时只走 Web
    single<AmapRouteGateway> {
        AndroidAmapRouteGateway(
            amapSdkService = runCatching { AmapSdkTransitService(androidContext()) }.getOrNull(),
            amapApiClient = get(),
            amapKeys = get()
        )
    }
    single<AmapSdkKeyApplier> { AndroidAmapSdkKeyApplier(androidContext()) }
    single<MapSdkInitializer> { AndroidMapSdkInitializer(androidContext()) }
}
