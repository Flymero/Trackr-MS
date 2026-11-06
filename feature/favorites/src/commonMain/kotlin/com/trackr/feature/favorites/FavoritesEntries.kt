package com.trackr.feature.favorites

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.ui.screens.favoritesedit.FavoritesEditRoute
import com.trackr.ui.screens.favoritesedit.FavoritesEditViewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 编辑收藏页的导航入口（第 22 步）。
 * 旧收藏页（`ScreenKey.FavoritesGroup`）已删除，首页「编辑」、路线卡的「去看看」都进这一页。
 */
fun EntryProviderScope<NavKey>.favoritesEntries(navigator: TrackrNavigator) {
    entry<ScreenKey.FavoritesEdit> {
        FavoritesEditRoute(viewModel = koinViewModel(), navigator = navigator)
    }
}

/** 编辑收藏的 Koin 定义（分组增删改 + 收藏排序 / 改分组 / 删除） */
val favoritesModule = module {
    viewModel {
        FavoritesEditViewModel(
            favorites = get(),
            selection = get(),
            dataFix = get()
        )
    }
}
