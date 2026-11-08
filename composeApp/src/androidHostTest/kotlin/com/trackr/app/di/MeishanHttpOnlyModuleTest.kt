package com.trackr.app.di

import androidx.lifecycle.ViewModelStore
import com.trackr.core.data.local.LineStopsCache
import com.trackr.core.data.local.NoLineStopsCache
import com.trackr.core.data.repository.*
import com.trackr.core.domain.platform.Announcer
import com.trackr.core.domain.platform.NoAnnouncer
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.model.TransitCity
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.network.provider.meishan.MeishanTransitProvider
import com.trackr.core.network.remote.meishan.MeishanTransitClient
import com.trackr.core.network.remote.meishan.push.MeishanPushClientApi
import com.trackr.core.network.remote.meishan.push.NoMeishanPushClient
import com.trackr.core.testing.*
import com.trackr.data.repository.FakeScheduledReminderRepository
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.feature.line.lineModule
import com.trackr.feature.line.list.LineListViewModel
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], manifest = org.robolectric.annotation.Config.NONE)
class MeishanHttpOnlyModuleTest {
    private val main = MainDispatcherHolder()
    @BeforeTest fun installMain() = main.install()
    @AfterTest fun resetMain() = main.reset()

    @Test
    fun httpOnlyBindingsResolveBothSharedLineViewModels() = runTest(main.dispatcher) {
        val http = HttpClient(MockEngine { error("ViewModel creation must not make an HTTP request") })
        val client = MeishanTransitClient(http)
        val provider = MeishanTransitProvider(client)
        val settings = FakeSettingsRepository(TransitCity("034", "Shanghai"))
        val tracking = DefaultTrackingRepository(InMemoryTrackingSessionStore(), backgroundScope)
        val application = koinApplication {
            modules(routeDataModule, lineModule, meishanHttpOnlyModule(client, provider), module {
                single<SettingsRepository> { settings }
                single<FavoritesRepository> { FakeFavoritesRepository() }
                single<RealtimeRepository> { FakeRealtimeRepository() }
                single<IScheduledReminderRepository> { FakeScheduledReminderRepository() }
                single<TrackingRepository> { tracking }
                single<TrackingController> { FakeTrackingController(tracking) }
                single<LineStopsCache> { NoLineStopsCache }
                single<Announcer> { NoAnnouncer }
            })
        }
        val store = ViewModelStore()
        try {
            assertSame(client, application.koin.get<MeishanTransitClient>())
            assertSame(provider, application.koin.get<MeishanTransitProvider>())
            assertSame(NoMeishanPushClient, application.koin.get<MeishanPushClientApi>())
            assertNotNull(application.koin.get<MeishanPushRepository>())
            assertNotNull(application.koin.get<MeishanLineRepository>())
            val detail = application.koin.get<LineDetailViewModel> {
                parametersOf(ScreenKey.LineDetail(cityId = "034", lineId = "L59"))
            }
            store.put("detail", detail)
            val list = application.koin.get<LineListViewModel>()
            store.put("list", list)
        } finally {
            store.clear()
            application.close()
            http.close()
        }
    }
}
