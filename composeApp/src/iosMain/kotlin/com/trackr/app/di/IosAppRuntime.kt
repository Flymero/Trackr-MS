@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.trackr.app.di

import com.trackr.app.platform.*
import com.trackr.core.data.local.*
import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.data.repository.*
import com.trackr.core.data.route.WebAmapRouteGateway
import com.trackr.core.database.createTrackrDatabase
import com.trackr.core.datastore.*
import com.trackr.core.domain.platform.*
import com.trackr.core.network.provider.chelaile.ChelaileTransitProvider
import com.trackr.core.network.provider.mygolbs.MygolbsTransitProvider
import com.trackr.core.network.remote.amap.AmapTransitApiClient
import com.trackr.core.network.remote.chelaile.ChelaileApiClient
import com.trackr.core.network.remote.mygolbs.MygolbsApiClient
import com.trackr.core.network.remote.common.createTrackrHttpClient
import com.trackr.data.repository.IRouteGroupRepository
import com.trackr.data.repository.IScheduledReminderRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.*
import platform.UIKit.*
import kotlin.time.Clock
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin

/** Owns the native application's stores and services, shared by every CMP destination. */
internal class IosAppRuntime(val module: Module, val resolveFavorite: (String) -> String?, private val dispose: suspend () -> Unit) {
    private val closeMutex = Mutex()
    private var closed = false
    fun close() { CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { closeAndJoin() } }
    suspend fun closeAndJoin() = withContext(NonCancellable) {
        closeMutex.withLock { if (!closed) { closed = true; dispose() } }
    }

    companion object {
        private var startupFailureInjected = false
        suspend fun open(): IosAppRuntime {
            var opened: IosAppRuntime? = null
            try {
                return withContext(Dispatchers.Default) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val db = createTrackrDatabase()
                    val http = try { createTrackrHttpClient() } catch (error: Throwable) { db.close(); scope.cancel(); throw error }
                    if (NSProcessInfo.processInfo.arguments.contains("--offline-ui-test")) {
                        http.plugin(HttpSend).intercept { error("离线验收：网络请求已禁用") }
                    }
                    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                    var lifecycle: IosAppLifecycle? = null
                    try {
                        val root = requireNotNull(NSFileManager.defaultManager.URLForDirectory(
                            NSApplicationSupportDirectory, NSUserDomainMask, null, true, null
                        )?.path)
                        val privateDir = prepareIosPrivateDirectory(root)
                        val store = RoomDataStoreTransitStore(db, createPreferencesDataStore("$root/datastore/trackr_settings.preferences_pb", scope))
                        val writer = SerialWriter(scope)
                        val settings = DefaultSettingsRepository.create(store, writer)
                        val history = DefaultSearchHistoryRepository.create(store, writer)
                        val favorites = DefaultFavoritesRepository.create(RoomFavoritesStore(db))
                        val defaults = AmapKeyDefaults(hasAndroidMapSdk = false,
                            hasIosMapSdk = com.trackr.ui.screens.routeplan.map.isRouteMapSupported,
                            iosKey = (NSBundle.mainBundle.objectForInfoDictionaryKey("AmapIosKey") as? String).orEmpty(),
                            webKey = (NSBundle.mainBundle.objectForInfoDictionaryKey("AmapWebKey") as? String).orEmpty())
                        val keys = DefaultAmapKeyRepository.create(
                            DataStoreAmapKeyStore(createPreferencesDataStore("$privateDir/trackr_amap_keys.preferences_pb", scope)), writer, defaults
                        )
                        val locationStore = IosUserLocationStore()
                        val coordinator = TransitProviderCoordinator(
                            ChelaileTransitProvider(ChelaileApiClient(http, monotonicClock = IosMonotonicClock), IosMonotonicClock),
                            MygolbsTransitProvider(MygolbsApiClient(http, clock = IosMonotonicClock))
                        )
                        val tracking = DefaultTrackingRepository(
                            DataStoreTrackingSessionStore(createTrackingSessionDataStore("$privateDir/tracking_session.json", scope)),
                            scope, monotonicClock = IosMonotonicClock
                        )
                        val location = withContext(Dispatchers.Main) { IosLocationSource() }
                        val baseRealtime = DefaultRealtimeRepository(coordinator, settings, favorites, history,
                            userLocation = { locationStore.userLocation.value }, clock = IosMonotonicClock, fetchScope = scope)
                        val favoriteSnapshot = MutableStateFlow(IosFavoriteSnapshot())
                        var active = false
                        val realtime = object : RealtimeRepository by baseRealtime {
                            override suspend fun refreshFavoritesRealtime(): Result<Map<String, com.trackr.core.model.NearbyStop>> =
                                baseRealtime.refreshFavoritesRealtime().also { result ->
                                    result.onSuccess { favoriteSnapshot.value = IosFavoriteSnapshot(Clock.System.now().toEpochMilliseconds(), it) }
                                }
                        }
                        val notifications = IosNotifications(mainScope, RoomReminderScheduleStateStore(db))
                        notifications.refreshAuthorization()
                        val reminders = DefaultScheduledReminderRepository.create(RoomReminderStore(db), notifications)
                        notifications.onOneTimeConsumed = { id -> mainScope.launch { reminders.toggleReminder(id, false) } }
                        val announcer = withContext(Dispatchers.Main) { IosAnnouncer() }
                        val controller = ForegroundTrackingController(tracking, realtime, settings, location, mainScope,
                            Clock.System, IosMonotonicClock, onEffect = notifications::alert)
                        mainScope.launch {
                            keys.privacyConsent.collect { consent ->
                                // Do not touch the SDK until a privacy decision has actually been made.
                                if (consent != null) com.trackr.ui.screens.routeplan.map.IosRouteMapBridge.privacy?.invoke(consent)
                            }
                        }
                        fun publish() = publishSnapshot(favorites.favoriteItems.value, tracking.sessions.value,
                            settings.isLiveNotificationEnabled.value && settings.showOnLockScreen.value, favoriteSnapshot.value, active)
                        mainScope.launch {
                            combine(favorites.favoriteItems, tracking.sessions, settings.isLiveNotificationEnabled, settings.showOnLockScreen, favoriteSnapshot) { _, _, _, _, _ -> Unit }
                                .collect { publish() }
                        }
                        withContext(Dispatchers.Main) {
                            lifecycle = IosAppLifecycle { foreground ->
                                active = foreground
                                controller.setActive(active)
                                if (!active) announcer.stop()
                                if (active) {
                                    mainScope.launch { notifications.refreshAuthorization() }
                                    notifications.rescheduleAll(reminders.reminders.value)
                                }
                                publish()
                            }
                        }
                        if (NSProcessInfo.processInfo.arguments.contains("--fail-startup-once") && !startupFailureInjected) {
                            startupFailureInjected = true
                            error("Injected startup failure after opening stores")
                        }
                        IosAppRuntime(module {
                            single<SettingsRepository> { settings }
                            single<SearchHistoryRepository> { history }
                            single<FavoritesRepository> { favorites }
                            single<IRouteGroupRepository> { DefaultRouteGroupRepository(favorites) }
                            single { coordinator }
                            single<LocalMigrationFlags> { StoreMigrationFlags(store) }
                            single<AmapKeyRepository> { keys }
                            single { defaults }
                            single { AmapTransitApiClient(http) }
                            single<AmapRouteGateway> { WebAmapRouteGateway(get(), get()) }
                            single<UserLocationStore> { locationStore }
                            single<MonotonicClock> { IosMonotonicClock }
                            single { location }
                            single<LocationSource> { get<IosLocationSource>() }
                            single<LocationRepository> { DefaultLocationRepository(get(), get()) }
                            single<RealtimeRepository> { realtime }
                            single<LineStopsCache> { RoomLineStopsCache(db.lineStopsCache()) { Clock.System.now().toEpochMilliseconds() } }
                            single<TrackingRepository> { tracking }
                            single<TrackingController> { object : TrackingController by controller {
                                override fun start(target: com.trackr.core.model.TrackingTarget): Boolean {
                                    val started = controller.start(target)
                                    if (started) mainScope.launch {
                                        if (!notifications.requestPermission()) showPlatformNotice("通知未开启，追踪仍可在应用内查看。请到系统设置允许追客通知。")
                                    }
                                    return started
                                }
                            } }
                            single<IScheduledReminderRepository> { reminders }
                            single<ReminderAlarms> { notifications }
                            single<com.trackr.ui.platform.NotificationHealthAccess> { notifications }
                            single<com.trackr.ui.platform.LiveUpdateAccess> { object : com.trackr.ui.platform.LiveUpdateAccess {
                                override fun status() = com.trackr.ui.platform.LiveUpdateStatus(true, com.trackr.app.iosActivitiesAllowed?.invoke() == true)
                                override fun openSettings() { IosSystemSettingsLauncher.openAppSettings() }
                            } }
                            single<TransitCodeLauncher> { IosTransitCodeLauncher }
                            single<Announcer> { announcer }
                            single<SystemSettingsLauncher> { IosSystemSettingsLauncher }
                            single<AppSigningInfo> { IosAppSigningInfo }
                            single { AppVersionInfo(
                                (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 1,
                                NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.1.0"
                            ) }
                        }, resolveFavorite = { key -> favorites.favoriteItems.value.firstOrNull { it.key == key }?.destination() }) {
                            withContext(Dispatchers.Main) {
                                lifecycle?.close(); controller.close(); announcer.stop()
                                mainScope.cancel(); scope.cancel()
                            }
                            mainScope.coroutineContext.job.join(); scope.coroutineContext.job.join()
                            http.close(); db.close()
                        }.also { opened = it }
                    } catch (error: Throwable) {
                        withContext(NonCancellable) {
                            withContext(Dispatchers.Main) { lifecycle?.close(); mainScope.cancel(); scope.cancel() }
                            mainScope.coroutineContext.job.join(); scope.coroutineContext.job.join()
                            http.close(); db.close()
                        }
                        throw error
                    }
                }
            } catch (error: Throwable) {
                opened?.closeAndJoin()
                throw error
            }
        }
    }
}
