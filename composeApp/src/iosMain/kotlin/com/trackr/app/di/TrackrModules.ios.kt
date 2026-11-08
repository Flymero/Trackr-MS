package com.trackr.app.di

import org.koin.core.module.Module
import org.koin.dsl.module
import com.trackr.app.navigation.AppTabBar
import com.trackr.app.navigation.UIKitAppTabBar

actual val platformUiModule: Module = module { single<AppTabBar> { UIKitAppTabBar() } }

/** iOS 原生 UI 能力，业务导航状态仍由共享层持有。 */
internal actual val platformFeatureModules: List<Module> = listOf(
    platformUiModule,
)
