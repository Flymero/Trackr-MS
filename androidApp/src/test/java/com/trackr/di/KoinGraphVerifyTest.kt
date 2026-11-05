package com.trackr.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.trackr.core.model.DataSourceType
import com.trackr.app.di.appShellModule
import com.trackr.app.di.routeDataModule
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.network.provider.ITransitProvider
import com.trackr.ui.screens.linedetail.LineDetailViewModel
import com.trackr.ui.screens.search.SearchViewModel
import com.trackr.ui.screens.stopdetail.ChooseBoardedBusViewModel
import com.trackr.ui.screens.stopdetail.StopDetailViewModel
import com.trackr.feature.bus.busModule
import com.trackr.feature.favorites.favoritesModule
import com.trackr.feature.line.lineModule
import com.trackr.feature.me.meModule
import com.trackr.feature.route.routeAndroidModule
import com.trackr.feature.route.routeModule
import com.trackr.feature.search.searchModule
import com.trackr.feature.stop.stopModule
import com.trackr.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineDispatcher
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.trackr.ui.screens.me.ReminderEditViewModel
import org.junit.runner.RunWith
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.core.parameter.ParametersHolder
import org.koin.core.parameter.emptyParametersHolder
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import org.koin.test.verify.verify
import org.robolectric.RobolectricTestRunner
import kotlin.reflect.KClass

/**
 * Koin 没有编译期检查，缺绑定只会在运行时崩（计划 R4）。这里两层把关：
 * 1. 静态：对 [appModules] 里每个模块跑 `verify()`，检查每个定义的构造参数都有绑定；
 * 2. 运行时：在 Robolectric 里用 App 启动时装好的真实 Koin 图，把每个模块的每个定义都实际取一次。
 */
@RunWith(RobolectricTestRunner::class)
class KoinGraphVerifyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Koin 不管、由 Android 或调用方提供的类型 */
    private val extraTypes: List<KClass<*>> = listOf(
        Context::class,
        Application::class,
        // HttpClient 的构造参数（Koin 里用 createTrackrHttpClient() 现建）
        HttpClientEngine::class,
        HttpClientConfig::class,
        // verify 会检查所有公开构造函数：TransitProviderCoordinator 主构造函数收 Map，次构造函数收两个 ITransitProvider；
        // Koin 里用次构造函数，传的是具体的 ChelaileTransitProvider / MygolbsTransitProvider
        Map::class,
        ITransitProvider::class,
        // LiveProgressNotifier 收两个 lambda（读设置开关、取下一程线路名），在模块里现写
        Function0::class,
        Function1::class,
        // AndroidTrackingController 收一个启动前台服务的 lambda（测试替换用，Koin 里用默认值）
        Function2::class,
        // 第 17 步拆出来的 ViewModel / 数据层对象：IO 调度器用构造参数默认值
        CoroutineDispatcher::class,
        // 第 23 步：entry 级 ViewModel 的 SavedStateHandle 由 koinViewModel() 从导航 entry 取
        SavedStateHandle::class
    )

    /** 静态校验某个模块：别的模块的定义也算可用绑定 */
    private fun verifyModule(target: Module) {
        val others = appModules.filter { it !== target }
        module { includes(listOf(target) + others) }.verify(extraTypes = extraTypes)
    }

    @Test
    fun appModule_verify() = verifyModule(appModule)

    @Test
    fun platformBridgeModule_verify() = verifyModule(platformBridgeModule)

    // 第 17 步：原来的 viewModelModule 拆进各功能模块，每个模块一条
    @Test
    fun routeDataModule_verify() = verifyModule(routeDataModule)

    // 第 30 步：根级壳状态（界面模式）
    @Test
    fun appShellModule_verify() = verifyModule(appShellModule)

    @Test
    fun busModule_verify() = verifyModule(busModule)

    @Test
    fun searchModule_verify() = verifyModule(searchModule)

    @Test
    fun stopModule_verify() = verifyModule(stopModule)

    @Test
    fun lineModule_verify() = verifyModule(lineModule)

    @Test
    fun routeModule_verify() = verifyModule(routeModule)

    @Test
    fun routeAndroidModule_verify() = verifyModule(routeAndroidModule)

    @Test
    fun favoritesModule_verify() = verifyModule(favoritesModule)

    @Test
    fun meModule_verify() = verifyModule(meModule)

    @Test
    fun appModules_containsEveryModuleUnderTest() {
        // 新加模块时要在上面补一条 verify 用例，这里提醒一下
        assertEquals(
            listOf(
                appModule, platformBridgeModule, routeDataModule, appShellModule, busModule, searchModule, stopModule,
                lineModule, routeModule, favoritesModule, meModule, routeAndroidModule
            ),
            appModules
        )
    }

    @Test
    fun meishanPush_dependenciesAreSingletons() {
        val koin = GlobalContext.get()
        val client = koin.get<com.trackr.core.network.remote.meishan.push.MeishanPushClientApi>()
        val repository = koin.get<com.trackr.core.data.repository.MeishanPushRepository>()
        assertTrue(client === koin.get<com.trackr.core.network.remote.meishan.push.MeishanPushClientApi>())
        assertTrue(repository === koin.get<com.trackr.core.data.repository.MeishanPushRepository>())
    }

    /** 带参数的定义：按类型给一份样例参数 */
    private fun parametersFor(type: KClass<*>): ParametersHolder = when (type) {
        LineDetailViewModel::class -> parametersOf(
            ScreenKey.LineDetail(
                cityId = "034",
                lineId = "line-1",
                targetOrder = 3,
                stopId = "stop-3",
                source = DataSourceType.CHELAILE,
                lineName = "1路"
            )
        )
        StopDetailViewModel::class -> parametersOf(
            ScreenKey.StopDetail(
                cityId = "034",
                stopId = "stop-3",
                stopName = "人民广场",
                physicalStId = "",
                namesakeStId = "",
                lat = 31.23,
                lng = 121.47
            )
        )
        // 页面里由 koinViewModel() 从导航 entry 带进来；这里手动给一个
        SearchViewModel::class -> parametersOf(SavedStateHandle())
        ChooseBoardedBusViewModel::class -> parametersOf(ScreenKey.ChooseBoardedBus(sessionId = "s-1"))
        ReminderEditViewModel::class -> parametersOf(ScreenKey.ReminderEdit(reminderId = "rem-1"))
        else -> emptyParametersHolder()
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun everyDefinition_resolvesAtRuntime() {
        val koin = GlobalContext.get()
        val definitions = appModules.flatMap { it.mappings.values }.map { it.beanDefinition }.distinct()
        assertTrue("应至少有一个定义", definitions.isNotEmpty())

        for (definition in definitions) {
            val type = definition.primaryType
            val instance: Any? = koin.getOrNull(type, definition.qualifier) { parametersFor(type) }
            assertNotNull("取不到 ${type.qualifiedName}（qualifier=${definition.qualifier}）", instance)
            assertTrue(
                "${type.qualifiedName} 取到的实例类型不对：${instance!!::class.qualifiedName}",
                type.isInstance(instance)
            )
        }
    }
}
