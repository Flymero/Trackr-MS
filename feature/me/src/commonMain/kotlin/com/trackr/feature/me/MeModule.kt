package com.trackr.feature.me

import com.trackr.core.domain.platform.AppVersionInfo
import com.trackr.core.navigation.ScreenKey
import com.trackr.ui.screens.me.AboutViewModel
import com.trackr.ui.screens.me.AmapKeyViewModel
import com.trackr.ui.screens.me.MeNotices
import com.trackr.ui.screens.me.MeViewModel
import com.trackr.ui.screens.me.ReminderEditViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 「我的」页签的 ViewModel（第 25 步）。
 *
 * 平台不一定有的能力用 `getOrNull()`：状态栏胶囊 `LiveUpdateAccess`、应用内更新 `UpdateChecker`（都只有 Android）、
 * 版本号 `AppVersionInfo`。iOS 没绑定时对应那一行不显示。
 */
val meModule = module {
    // 编辑页保存 / 删除后的提示交给「我的」页显示
    single { MeNotices() }
    viewModel {
        MeViewModel(
            settings = get(),
            reminderRepository = get(),
            amapKeys = get(),
            notices = get(),
            liveUpdateAccess = getOrNull(),
            versionName = getOrNull<AppVersionInfo>()?.name,
            notificationHealthAccess = getOrNull()
        )
    }
    viewModel { (key: ScreenKey.ReminderEdit) ->
        ReminderEditViewModel(
            reminderId = key.reminderId,
            reminderRepository = get(),
            alarms = get(),
            notices = get()
        )
    }
    viewModel { AmapKeyViewModel(amapKeys = get(), signingInfo = get(), defaults = get(), notices = get()) }
    viewModel {
        val version = getOrNull<AppVersionInfo>() ?: AppVersionInfo(1, "0.0.1")
        AboutViewModel(
            updateChecker = getOrNull(),
            updateInstaller = getOrNull(),
            versionProvider = { version.code to version.name }
        )
    }
}
