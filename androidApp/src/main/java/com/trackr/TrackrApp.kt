package com.trackr

import android.app.Application
import com.trackr.core.domain.platform.MapSdkInitializer
import com.trackr.data.local.StorageBackend
import com.trackr.data.remote.amap.AmapSdkKeySync
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.di.appModules
import com.trackr.widget.FavoritesShortcutWidgetSync
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

class TrackrApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 网络客户端、数据源、存储和三个仓库由 Koin 构建。startKoin 只登记定义，不建任何实例
        startKoinOnce()

        // 打开本地存储（第 9 步：USE_NEW_STORE 开时先把旧 SharedPreferences 导入 Room / DataStore，只读不删）
        val storage = get<StorageBackend>()

        // 先只配置构建时 Key，不触碰高德 SDK；隐私说明同意后由 MainActivity 初始化。
        get<MapSdkInitializer>().configureBuiltInKeys(BuildConfig.AMAP_ANDROID_KEY, BuildConfig.AMAP_WEB_KEY)
        if (storage.amapKeys.privacyConsent.value == true) {
            runCatching { get<MapSdkInitializer>().initialize(storage.amapKeys.androidKey.value) }
        }
        // 之后用户在「更多」页改 Android Key 时重新注入（已同意时）。
        get<AmapSdkKeySync>().start()

        // 三个仓库按原来的顺序在这里取一次（构建时读盘）
        get<ITransitRepository>()
        get<IScheduledReminderRepository>()
        get<IRouteGroupRepository>()

        // 收藏真变了才同步桌面快捷方式和小组件（原来在 MainActivity 里，App 不打开就不同步）
        get<FavoritesShortcutWidgetSync>().start()
    }

    override fun onTerminate() {
        if (GlobalContext.getOrNull() != null) stopKoin()
        super.onTerminate()
    }

    private fun startKoinOnce() {
        // 生产环境每个进程只会走一次；Robolectric 每个用例都新建 Application，而 Koin 的全局容器跨用例保留，先停掉旧的
        if (GlobalContext.getOrNull() != null) stopKoin()
        startKoin {
            androidContext(this@TrackrApp)
            modules(appModules)
        }
    }
}
