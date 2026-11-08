package com.trackr.app.di

import com.trackr.feature.route.routeAndroidModule
import org.koin.core.module.Module
import org.koin.dsl.module
import com.trackr.app.navigation.AppTabBar
import com.trackr.app.navigation.MaterialAppTabBar

actual val platformUiModule: Module = module { single<AppTabBar> { MaterialAppTabBar() } }

/** Android：高德 SDK 通道、Key 注入、地图 SDK 初始化（:feature:route androidMain） */
internal actual val platformFeatureModules: List<Module> = listOf(
    routeAndroidModule,
    platformUiModule,
)
