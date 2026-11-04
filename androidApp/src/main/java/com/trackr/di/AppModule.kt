package com.trackr.di

import com.trackr.core.data.local.FavoritesStore
import com.trackr.core.data.local.LineStopsCache
import com.trackr.core.data.local.ReminderStore
import com.trackr.core.data.local.TransitLocalStore
import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.DefaultFavoritesRepository
import com.trackr.core.data.repository.DefaultLocationRepository
import com.trackr.core.data.repository.FavoritesRepository
import com.trackr.core.data.repository.LocationRepository
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SearchHistoryRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.data.repository.DefaultTrackingRepository
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.domain.platform.AppUpdateInstaller
import com.trackr.core.domain.platform.LocationSource
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.platform.ReminderAlarms
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.domain.platform.UpdateChecker
import com.trackr.core.domain.tracking.TrackingEngine
import com.trackr.core.network.provider.chelaile.ChelaileTransitProvider
import com.trackr.core.network.provider.mygolbs.MygolbsTransitProvider
import com.trackr.core.network.remote.amap.AmapTransitApiClient
import com.trackr.core.network.remote.chelaile.ChelaileApiClient
import com.trackr.core.network.remote.common.createTrackrHttpClient
import com.trackr.core.network.remote.mygolbs.MygolbsApiClient
import com.trackr.app.di.trackrModules
import com.trackr.data.local.StorageBackend
import com.trackr.data.location.AndroidLocationSource
import com.trackr.data.location.AndroidMonotonicClock
import com.trackr.data.remote.amap.AmapSdkKeySync
import com.trackr.data.remote.update.AndroidAppUpdateInstaller
import com.trackr.data.remote.update.GithubUpdateChecker
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.data.repository.RouteGroupRepository
import com.trackr.data.repository.ScheduledReminderRepository
import com.trackr.data.repository.TransitRepository
import com.trackr.service.AndroidTrackingController
import com.trackr.service.ArrivalAlertNotifier
import com.trackr.service.LiveProgressNotifier
import com.trackr.util.ReminderAlarmScheduler
import com.trackr.util.ReminderScheduleState
import com.trackr.widget.BusStopWidgetUpdater
import com.trackr.widget.FavoritesShortcutWidgetSync
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import com.trackr.core.network.remote.meishan.MeishanTransitClient
import kotlin.time.Clock

/** 应用级协程作用域的限定名：活得和进程一样久，接收器、小组件、收藏同步都用它 */
val AppScopeQualifier = named("app")

/**
 * 应用级依赖。
 *
 * 网络客户端、数据源和三个仓库原来在 TrackrApp.onCreate 里直接 new，现在由 Koin 构建。
 * TrackrApp.onCreate 先取 [StorageBackend]（打开存储、导入旧数据），再按原顺序取三个仓库。
 */
val appModule: Module = module {
    // Ktor（OkHttp 引擎）：总超时 20 秒，连接 12 秒、读 15 秒、写 12 秒，和原来的 OkHttpClient 一样
    single<HttpClient> { createTrackrHttpClient() } onClose { it?.close() }
    single { ChelaileApiClient(get()) }
    single { MygolbsApiClient(get()) }
    // 眉山官方后台（http 明文，域名在 network_security_config.xml 白名单）
    single { MeishanTransitClient(get()) }
    single { ChelaileTransitProvider(get()) }
    single { MygolbsTransitProvider(get()) }
    single {
        TransitProviderCoordinator(
            chelaileProvider = get<ChelaileTransitProvider>(),
            mygolbsProvider = get<MygolbsTransitProvider>()
        )
    }
    single { AmapTransitApiClient(get()) }

    // 本地存储（第 9 步）：按 USE_NEW_STORE 选新（Room + DataStore，打开时先导入旧数据）或旧（SharedPreferences）
    single { StorageBackend.open(androidContext(), get(AppScopeQualifier)) } onClose { it?.close() }
    single<TransitLocalStore> { get<StorageBackend>().transitStore }
    single<FavoritesStore> { get<StorageBackend>().favoritesStore }
    single<ReminderStore> { get<StorageBackend>().reminderStore }
    single<ReminderScheduleState> { get<StorageBackend>().reminderScheduleState }
    single<AmapKeyRepository> { get<StorageBackend>().amapKeys }
    // 线路站表磁盘缓存（第 20 步，只用来先显示）
    single<LineStopsCache> { get<StorageBackend>().lineStopsCache }

    single<ITransitRepository> {
        TransitRepository(
            context = androidContext(),
            coordinator = get(),
            store = get(),
            favoritesRepository = get(),
            writeScope = get(AppScopeQualifier)
        )
    }
    // 收藏合一（第 14 步）：唯一一份，TransitRepository 和 RouteGroupRepository 都转调它
    single<DefaultFavoritesRepository> { RouteGroupRepository.openFavorites(get<FavoritesStore>()) }
    // 拆开的仓库接口（重构计划 §2.5）：第 9 步换存储之前都是同一个 TransitRepository
    single<SettingsRepository> { get<ITransitRepository>() }
    single<FavoritesRepository> { get<DefaultFavoritesRepository>() }
    single<RealtimeRepository> { get<ITransitRepository>() }
    single<SearchHistoryRepository> { get<ITransitRepository>() }
    single<IScheduledReminderRepository> {
        ScheduledReminderRepository(store = get(), alarms = get())
    }
    single<IRouteGroupRepository> {
        RouteGroupRepository(favorites = get<DefaultFavoritesRepository>())
    }

    // 应用级作用域：SupervisorJob，一个任务失败不影响别的；Koin 关闭时取消
    single<CoroutineScope>(AppScopeQualifier) { CoroutineScope(SupervisorJob() + Dispatchers.Default) } onClose { it?.cancel() }

    // 收藏真变了才同步桌面快捷方式和小组件（原来挂在 MainActivity 的组合里）
    single { FavoritesShortcutWidgetSync(androidContext(), get(), get(AppScopeQualifier)) }
    // 桌面小组件取数（第 26 步）：所有实例共用一次请求，走 RealtimeRepository 的合并缓存
    single { BusStopWidgetUpdater(favoritesRepository = get(), realtime = get()) }

    // 原来是 object 的有状态组件，改成类后由 Koin 提供唯一实例
    single {
        LiveProgressNotifier(
            liveNotificationSetting = { get<ITransitRepository>().isLiveNotificationEnabled.value },
            nextLegLineNameOf = { legIndex ->
                get<TrackingRepository>().session.value?.let { TrackingEngine.nextLegLineName(it, legIndex) }
            },
            showOnLockScreenSetting = { get<ITransitRepository>().showOnLockScreen.value },
            transitCodeProviderSetting = { get<ITransitRepository>().preferredTransitCodeProvider.value }
        )
    }
    single { ArrivalAlertNotifier(get()) }
    single<ReminderAlarms> { ReminderAlarmScheduler(androidContext(), get()) }

    // 追踪会话的唯一数据源（第 12 步）：UI、通知动作、追踪服务都读写它
    single<TrackingRepository> {
        DefaultTrackingRepository(
            store = get<StorageBackend>().trackingSessionStore,
            scope = get(AppScopeQualifier),
            clock = get(),
            monotonicClock = get()
        )
    }

    // 时钟：墙钟（写进会话的时刻）和单调时钟（定位年龄）
    single<Clock> { Clock.System }
    single<MonotonicClock> { AndroidMonotonicClock }

    // 平台能力：ViewModel 不再直接拿 Context
    single<LocationSource> { AndroidLocationSource(androidContext()) }
    single<LocationRepository> { DefaultLocationRepository(source = get(), clock = get()) }
    // 冷启动接续要等仓库读盘完成，用应用级作用域（AppScopeQualifier），不能阻塞 onStart
    single<TrackingController> {
        val settings = get<SettingsRepository>()
        AndroidTrackingController(
            androidContext(),
            get(),
            get(),
            alertThresholdStops = { settings.alertThresholdStops.value },
            appScope = get(AppScopeQualifier)
        )
    }
    // AmapSdkKeyApplier（把 Key 注入高德 SDK）第 17 步随 SDK 封装挪到 :feature:route 的 routeAndroidModule
    // 用户改了 Android Key 就重新注入 SDK（第 11 步：Key 对话框在「更多」页，不再由规划页 VM 注入）
    single { AmapSdkKeySync(get(), get(), get(AppScopeQualifier)) }

    // 原来每个 AboutViewModel 都新建一个检查器，这里保持 factory
    factory<UpdateChecker> { GithubUpdateChecker(get()) }
    // 应用内更新：下载安装包并调系统安装器，用自己的 HttpClient（不限下载总时长）
    single<AppUpdateInstaller> { AndroidAppUpdateInstaller(androidContext()) } onClose {
        (it as? AndroidAppUpdateInstaller)?.close()
    }
}

/**
 * 启动时装载的全部 Koin 模块；`KoinGraphVerifyTest` 逐个校验。
 * 第 17 步：原来的 viewModelModule 拆进各功能模块（`:composeApp` 的 [trackrModules]），页面要的 Android 能力在 [platformBridgeModule]。
 */
val appModules: List<Module> = listOf(appModule, platformBridgeModule) + trackrModules
