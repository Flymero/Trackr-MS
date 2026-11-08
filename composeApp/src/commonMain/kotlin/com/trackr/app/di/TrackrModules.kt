package com.trackr.app.di

import com.trackr.core.data.local.LocalMigrationFlags
import com.trackr.core.data.repository.HomeCoordinates
import com.trackr.core.data.repository.MeishanLineRepository
import com.trackr.core.data.repository.DefaultMeishanLineRepository
import com.trackr.core.data.repository.DefaultMeishanPushRepository
import com.trackr.core.data.repository.MeishanPushRepository
import com.trackr.core.data.repository.RoutePlanRepository
import com.trackr.core.data.route.DefaultRoutePlanRepository
import com.trackr.core.data.route.FavoriteGroupSelection
import com.trackr.core.data.route.FavoritesGroupDataFix
import com.trackr.core.data.route.RouteAlignmentService
import com.trackr.core.data.route.RouteGroupSaver
import com.trackr.feature.bus.busModule
import com.trackr.feature.favorites.favoritesModule
import com.trackr.feature.line.lineModule
import com.trackr.feature.me.meModule
import com.trackr.feature.route.routeModule
import com.trackr.feature.search.searchModule
import com.trackr.feature.stop.stopModule
import com.trackr.app.AppShellViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 路线规划 / 收藏共用的数据层对象（第 17 步从 `FavoritesGroupViewModel` 拆出来，都是 `single`）：
 * 对齐备忘、算路 / 输入提示缓存跟着进程，规划页、路线、编辑收藏共用一份。
 * 迁移版本号 [LocalMigrationFlags] 由平台层提供（Android：存在设置里）。
 */
val routeDataModule: Module = module {
    single { RouteAlignmentService(settings = get(), realtime = get(), coordinator = get()) }
    single<RoutePlanRepository> { DefaultRoutePlanRepository(gateway = get(), alignment = get(), coordinator = get()) }
    // 眉山官方线路列表（眉山特化：/line/list 全量线路）
    single<MeishanLineRepository> { DefaultMeishanLineRepository(client = get()) }
    single<MeishanPushRepository> { DefaultMeishanPushRepository(client = get(), provider = get()) }
    single { FavoriteGroupSelection() }
    // 首页当前坐标，给搜索用（拆分前搜索直接读首页状态）
    single { HomeCoordinates() }
    single { RouteGroupSaver(groupRepository = get(), alignment = get(), selection = get()) }
    single { FavoritesGroupDataFix(groupRepository = get(), alignment = get(), migrationFlags = get()) }
}

/** 根级壳状态（界面模式、字号提示，§8.2）；TrackrRoot 在 Activity 级取 */
val appShellModule: Module = module {
    viewModel { AppShellViewModel(settings = get()) }
}

/** 平台 UI 注入：Android Material 底栏、iOS UIKit 底栏。 */
expect val platformUiModule: Module

/** 各平台额外的 feature 模块（Android：高德 SDK 通道等） */
internal expect val platformFeatureModules: List<Module>

/** `:composeApp` 组装的全部功能模块；平台入口（Android 的 TrackrApp）把它们和自己的平台模块一起交给 Koin */
val trackrModules: List<Module>
    get() = listOf(
        routeDataModule,
        appShellModule,
        busModule,
        searchModule,
        stopModule,
        lineModule,
        routeModule,
        favoritesModule,
        meModule,
    ) + platformFeatureModules
