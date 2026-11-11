package com.trackr.feature.line

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.feature.line.elder.ElderLineDetailScreen
import com.trackr.ui.screens.linedetail.LineDetailRoute
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/** 线路详情页的导航入口（`:composeApp` 组装）。第 20 步页面重写进 commonMain，Android / iOS 共用 */
fun EntryProviderScope<NavKey>.lineEntries(navigator: TrackrNavigator) {
    entry<ScreenKey.LineDetail> { key ->
        val detailViewModel: LineDetailViewModel = koinViewModel(
            key = "detail_${key.cityId}_${key.lineId}_${key.targetOrder}"
        ) { parametersOf(key) }

        LineDetailRoute(
            viewModel = detailViewModel,
            onBack = dropUnlessResumed { navigator.popChildPage() }
        )
    }
}

/**
 * 长辈版线路页（§8.4、8.5，第 32 步）：同一个 Key、同一个 ViewModel 和事件处理，只换成 [ElderLineDetailScreen]
 * （沿途车站放在页内的「换站」[com.trackr.feature.line.elder.ElderPickStopScreen]）。
 */
fun EntryProviderScope<NavKey>.elderLineEntries(navigator: TrackrNavigator) {
    entry<ScreenKey.LineDetail> { key ->
        val detailViewModel: LineDetailViewModel = koinViewModel(
            key = "detail_${key.cityId}_${key.lineId}_${key.targetOrder}"
        ) { parametersOf(key) }

        LineDetailRoute(
            viewModel = detailViewModel,
            onBack = dropUnlessResumed { navigator.popChildPage() }
        ) { state, onEvent ->
            ElderLineDetailScreen(state = state, onEvent = onEvent)
        }
    }
}

/** 线路详情的 Koin 定义 */
val lineModule = module {
    viewModel { (key: ScreenKey.LineDetail) ->
        LineDetailViewModel(
            cityId = key.cityId,
            initialLineId = key.lineId,
            initialTargetOrder = key.targetOrder.takeIf { it > 0 },
            initialStationId = key.stopId.takeIf { it.isNotBlank() },
            initialSource = key.source,
            lineNameHint = key.lineName,
            entryBoardStopName = key.boardStopName,
            entryAlightStopName = key.alightStopName,
            settings = get(),
            favorites = get(),
            realtime = get(),
            reminderRepository = get(),
            trackingRepository = get(),
            trackingController = get(),
            lineStopsCache = get(),
            announcer = get()
        )
    }
}
