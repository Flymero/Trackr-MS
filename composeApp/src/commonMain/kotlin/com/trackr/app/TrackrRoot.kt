package com.trackr.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.UiMode
import com.trackr.core.navigation.Navigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.TrackrTransitions
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.core.navigation.scene.BottomSheetSceneStrategy
import com.trackr.core.navigation.rememberNavigationState
import com.trackr.feature.bus.busEntries
import com.trackr.feature.favorites.favoritesEntries
import com.trackr.feature.line.lineEntries
import com.trackr.feature.me.meEntries
import com.trackr.feature.route.routeEntries
import com.trackr.feature.search.searchEntries
import com.trackr.feature.stop.stopEntries
import com.trackr.ui.navigation.LocalAppViewModelStoreOwner
import com.trackr.ui.navigation.rememberVisibleTabs
import com.trackr.ui.bottombar.LocalBottomBarCollapseOffset
import com.trackr.feature.bus.home.BusHomeViewModel
import com.trackr.ui.screens.routedetail.RouteDetailViewModel
import com.trackr.ui.screens.routeplan.RoutePlanViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * 应用的根 Composable（重构计划 §2.2、§3.3、§8.4）：主题和导航结构都按 [AppShellViewModel] 的界面模式选——
 * 标准版是底栏 + 各页签的返回栈（[TrackrNavApp]），长辈版是一条栈、没有底栏（[ElderNavApp]）。
 * 第 17 步从 `MainActivity` 原样搬来；`MainActivity` 只剩 Intent 处理和 `setContent { TrackrRoot(…) }`。
 *
 * @param deepLink 待落栈的链接（`DeepLinkParser` 解析小组件 / 快捷方式 / 通知的 `trackr://` 得到，§3.6）。
 *   落完调 [onDeepLinkConsumed]，调用方把它清掉（一次性消费）。乘车码链接不入栈，由平台层直接处理，不传进来。
 */
@Composable
fun TrackrRoot(
    deepLink: DeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    onAmapPrivacyAccepted: () -> Unit = {}
) {
    // 界面模式在 NavDisplay 之上读（§8.2）：决定主题和导航结构（标准版 / 长辈版，§8.4）
    val shellViewModel = koinViewModel<AppShellViewModel>()
    val shell by shellViewModel.shell.collectAsStateWithLifecycle()
    TrackrTheme(uiMode = shell.uiMode, themeMode = shell.themeMode, themeColors = shell.themeColors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // 原来在 NavDisplay 外取、跨页共享的 ViewModel（首页、搜索、规划 / 收藏）仍挂在这一层
            val appViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
                "TrackrRoot 要放在有 ViewModelStoreOwner 的地方（Activity / ComposeUIViewController）"
            }
            CompositionLocalProvider(LocalAppViewModelStoreOwner provides appViewModelStoreOwner) {
                // Android：地图会话（切页签不销毁地图）；iOS：直接显示
                val amapKeys = koinInject<AmapKeyRepository>()
                val privacyConsent by amapKeys.privacyConsent.collectAsStateWithLifecycle()
                // 长辈版没有地图规划，不建地图会话
                PlatformHost(enabled = privacyConsent == true && shell.uiMode == UiMode.Standard) {
                    // 标准版 3 个页签各一条栈；长辈版一条栈、没有底栏（§8.4）。切换模式时新模式从首页开始（Q9）
                    UiModeNavHost(
                        uiMode = shell.uiMode,
                        standard = {
                            TrackrNavApp(
                                deepLink = deepLink,
                                onDeepLinkConsumed = onDeepLinkConsumed,
                                onAmapPrivacyAccepted = onAmapPrivacyAccepted
                            )
                        },
                        elder = {
                            ElderNavApp(
                                deepLink = deepLink,
                                onDeepLinkConsumed = onDeepLinkConsumed
                            )
                        }
                    )
                }
                // 申请定位与通知权限（Android：首次打开时弹说明再申请；iOS 阶段 G 再做）。
                // 说明对话框和系统权限弹窗都关掉后，系统字体 ≥ 1.3 倍时再提示一次「试试长辈版」，两个对话框不叠在一起
                PermissionOnboarding {
                    FontScalePrompt(
                        dismissed = shell.fontScalePromptDismissed,
                        onEnableElder = shellViewModel::enableElderFromPrompt,
                        onDismiss = shellViewModel::dismissFontScalePrompt
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackrNavApp(
    deepLink: DeepLink?,
    onDeepLinkConsumed: () -> Unit,
    onAmapPrivacyAccepted: () -> Unit
) {
    // 3 个页签各一条返回栈 + 当前页签，跨进程重建恢复（:core:navigation，重构计划 §3.3）
    val navigationState = rememberNavigationState()
    val navigator = remember(navigationState) { Navigator(navigationState) }

    // 原来在这里（NavDisplay 外）取、跨页共享的 ViewModel：首页（首页 / 权限回调）、FavoritesGroupViewModel（规划页 / 收藏页）。
    // 拆开后仍在这里先取一次 Activity 级的实例，创建时机和原来一样（收藏的自动 ETA 刷新、一次性数据修复都从这时开始）
    val appViewModelStoreOwner = LocalAppViewModelStoreOwner.current
    val homeViewModel = koinViewModel<BusHomeViewModel>(viewModelStoreOwner = appViewModelStoreOwner)
    // 首页 ViewModel 建好就开着轮询；恢复到别的页签、或首页栈顶是子页时首页 Route 不组合，没人关它。
    // 和原来 shouldPollHome 第一帧一样：首页根页没显示就先关掉，之后由首页 Route 的 LifecycleStartEffect 开关
    LaunchedEffect(homeViewModel) {
        if (!navigationState.isShowingRootOf(ScreenKey.Home)) homeViewModel.setPollingActive(false)
    }
    koinViewModel<RoutePlanViewModel>(viewModelStoreOwner = appViewModelStoreOwner)
    koinViewModel<RouteDetailViewModel>(viewModelStoreOwner = appViewModelStoreOwner)

    // D2：没有高德 Key 就不显示「路线规划」；Key 被清空且正停在那个页签时回首页、清掉它的栈
    val visibleTabs = rememberVisibleTabs(koinInject<AmapKeyRepository>(), koinInject<SettingsRepository>(), navigator)
    val hasMapKey = ScreenKey.RoutePlan in visibleTabs
    val amapKeys = koinInject<AmapKeyRepository>()
    val privacyConsent by amapKeys.privacyConsent.collectAsStateWithLifecycle()
    var showAmapPrivacyDialog by rememberSaveable { mutableStateOf(false) }

    // 恢复到路线页但还没同意时回首页，并在用户主动进入路线页时再显示说明。
    LaunchedEffect(navigationState.topLevel, privacyConsent, hasMapKey) {
        if (hasMapKey && navigationState.topLevel == ScreenKey.RoutePlan && privacyConsent != true) {
            navigator.selectTab(ScreenKey.Home)
            showAmapPrivacyDialog = true
        }
    }

    // 收藏变化后同步桌面快捷方式与小组件：第 4 步起在应用级作用域里做（见 FavoritesShortcutWidgetSync）
    // 首页轮询开关：第 17 步起由首页 Route 的 LifecycleStartEffect 控制（重构计划 §4.4）

    // 来自桌面小组件、快捷方式或通知栏的跳转（§3.6）：合成返回栈，按返回回到页签根页。一次性消费，重放结果也不变
    // 首帧就带着的链接（冷启动）：只有它需要补关首页轮询，见下面
    val coldStartLink = remember { deepLink }
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        when (link) {
            is DeepLink.Stack -> {
                navigator.applyDeepLink(link)
                // 冷启动时首帧先组合了首页，它的 entry 还没到 STARTED 就被线路页换掉，LifecycleStartEffect 不会跑 onStop，
                // 首页 ViewModel 就一直开着默认的轮询。和上面首帧的判断一样：首页根页没显示就关掉。
                // 只管冷启动：热启动时首页 entry 已经 STARTED，离开时自己会关；这里再关的话，推入动画没完就按返回，
                // 首页 entry 没离开过组合、不会再走 onStart，轮询就一直关着
                if (link === coldStartLink && !navigationState.isShowingRootOf(ScreenKey.Home)) {
                    homeViewModel.setPollingActive(false)
                }
            }
            // 「上了哪辆？」：弹层叠在当前栈顶，页签和下面的页都不动；栈顶已是它就不重复叠
            is DeepLink.ChooseBoardedBus -> navigator.open(ScreenKey.ChooseBoardedBus(link.sessionId))
            // 乘车码由平台层直接拉起，不会传到这里
            is DeepLink.TransitCode -> Unit
        }
        onDeepLinkConsumed()
    }

    val isTopLevel = navigationState.isAtTabRoot

    // 控制滑动时底部导航栏平滑收起与展开
    var isNavigationBarVisible by rememberSaveable { mutableStateOf(true) }

    // 切换 Tab 或从子页面返回根页面时，确保导航栏重置为显示状态
    LaunchedEffect(isTopLevel, navigationState.topLevel) {
        if (isTopLevel) {
            isNavigationBarVisible = true
        }
    }

    val nestedScrollConnection = remember(isTopLevel) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (isTopLevel) {
                    // 手指向上推，浏览下方更多内容 -> 向下滑出隐藏导航栏
                    if (consumed.y < -8f) {
                        isNavigationBarVisible = false
                    }
                    // 手指向下拉，回看上方内容或在顶部下拉 -> 向上滑入显示导航栏
                    else if (consumed.y > 8f || available.y > 8f) {
                        isNavigationBarVisible = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    val navBarTranslationY by animateFloatAsState(
        targetValue = if (isNavigationBarVisible) 0f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "navBarTranslationY"
    )

    // 页面里贴底的悬浮元素（首页「乘车码」）要跟着底栏一起收起：给它一个「底栏已经滑下去多少」的距离。
    // 高度含系统导航栏内边距，收起后按钮仍停在系统栏上方，所以只按净高度挪。
    var navBarHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val systemNavBarInsetPx = WindowInsets.navigationBars.getBottom(density)
    val bottomBarCollapseOffset: () -> Float = {
        navBarTranslationY * (navBarHeightPx - systemNavBarInsetPx).coerceAtLeast(0)
    }

    CompositionLocalProvider(LocalBottomBarCollapseOffset provides bottomBarCollapseOffset) {
        Scaffold(
            modifier = Modifier.nestedScroll(nestedScrollConnection),
            contentWindowInsets = WindowInsets(0.dp),
            bottomBar = {
                AnimatedVisibility(
                    visible = isTopLevel,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(280)
                    ) + fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(280)
                    ) + fadeOut(animationSpec = tween(200))
                ) {
                    NavigationBar(
                        modifier = Modifier
                            .onSizeChanged { navBarHeightPx = it.height }
                            .graphicsLayer {
                                translationY = navBarTranslationY * size.height
                            }
                    ) {
                        visibleTabs.forEach { key ->
                            val label = bottomTabLabel(key)
                            NavigationBarItem(
                                selected = navigationState.topLevel == key,
                                // 点当前页签回到它的根页；点别的页签切过去，各自的返回栈保留
                                onClick = dropUnlessResumed {
                                    if (key == ScreenKey.RoutePlan && privacyConsent != true) {
                                        showAmapPrivacyDialog = true
                                    } else {
                                        navigator.selectTab(key)
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = bottomTabIcon(key),
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        ) { _ ->
            NavDisplay(
                // 只显示当前页签的栈（和第 16 步之前一样）：非首页页签根页按返回交给系统，切页签走横向滑动
                entries = navigationState.toDecoratedEntries(startUnderneath = true, entryProvider = entryProvider {
                    busEntries(navigator)
                    routeEntries(navigator)
                    searchEntries(navigator)
                    lineEntries(navigator)
                    stopEntries(navigator)
                    meEntries(navigator)
                    favoritesEntries(navigator)
                }),
                onBack = { navigator.back() },
                modifier = Modifier.fillMaxSize(),
                // 弹层型目的地（§3.7）：entry 带 BottomSheetSceneStrategy.bottomSheet() 就叠在上一页上；其余照常单页
                sceneStrategies = remember { listOf(BottomSheetSceneStrategy<NavKey>()) },
                transitionSpec = TrackrTransitions.push,
                popTransitionSpec = TrackrTransitions.pop,
                predictivePopTransitionSpec = TrackrTransitions.predictivePop
            )
        }
    }

    if (showAmapPrivacyDialog && hasMapKey && privacyConsent != true) {
        AlertDialog(
            onDismissRequest = { showAmapPrivacyDialog = false },
            title = { Text("高德地图隐私说明") },
            text = {
                Text("路线规划会使用高德地图与公交服务获取地图、地点提示和公交路线。相关请求会包含你输入的地点及必要的位置信息，请阅读并同意高德隐私政策后继续。")
            },
            confirmButton = {
                TextButton(onClick = {
                    amapKeys.setPrivacyConsent(true)
                    showAmapPrivacyDialog = false
                    onAmapPrivacyAccepted()
                    navigator.selectTab(ScreenKey.RoutePlan)
                }) { Text("同意并继续") }
            },
            dismissButton = {
                TextButton(onClick = { showAmapPrivacyDialog = false }) { Text("暂不使用") }
            }
        )
    }
}

/** 底栏文字（眉山特化：公交 / 线路 / 路线 / 设置；Key 名 Home / More 暂不改） */
internal fun bottomTabLabel(key: ScreenKey): String = when (key) {
    ScreenKey.Home -> "公交"
    ScreenKey.Lines -> "线路"
    ScreenKey.RoutePlan -> "路线"
    ScreenKey.More -> "设置"
    else -> error("$key 不是顶层页签")
}
