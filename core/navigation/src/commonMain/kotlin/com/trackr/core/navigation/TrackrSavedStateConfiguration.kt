package com.trackr.core.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * 返回栈存进 SavedState 用的配置：把 [ScreenKey] 的每个子类登记为 [NavKey] 的多态子类。
 *
 * Android 上 `rememberNavBackStack(vararg)` 靠反射可以不登记，但 iOS 等平台没有这个重载，
 * 必须传带 `polymorphic(NavKey::class)` 的配置（§3.8）。两端统一用这一份。
 */
val TrackrSavedStateConfiguration: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(ScreenKey.Home.serializer())
            subclass(ScreenKey.RoutePlan.serializer())
            subclass(ScreenKey.Lines.serializer())
            subclass(ScreenKey.FavoritesGroup.serializer())
            subclass(ScreenKey.Search.serializer())
            subclass(ScreenKey.LineDetail.serializer())
            subclass(ScreenKey.StopDetail.serializer())
            subclass(ScreenKey.More.serializer())
            subclass(ScreenKey.About.serializer())
            subclass(ScreenKey.ThemeSettings.serializer())
            subclass(ScreenKey.ReminderEdit.serializer())
            subclass(ScreenKey.AmapKey.serializer())
            subclass(ScreenKey.RouteDetail.serializer())
            subclass(ScreenKey.FavoritesEdit.serializer())
            subclass(ScreenKey.ChooseBoardedBus.serializer())
        }
    }
}
