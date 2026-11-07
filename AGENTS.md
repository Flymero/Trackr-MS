# Agent 指南

工程开发、重构与测试指南。

## 开发规范与最佳实践

遵循 Google 官方推荐的 **MAD (Modern Android Development)** 最佳实践：

1. **架构规范**：严格遵循官方应用架构指南（Guide to app architecture），采用分层架构（UI 层、可选 Domain 层、Data 层）与单向数据流（UDF, Unidirectional Data Flow）。
2. **现代 UI 开发**：优先使用 Jetpack Compose 构建声明式界面，规范状态提升（State Hoisting）与生命周期感知，遵循 Compose 性能最佳实践。
3. **异步与响应式流**：全面采用 Kotlin Coroutines 与 Flow 处理异步任务和数据流，结合 `viewModelScope` 及生命周期感知 API（如 `repeatOnLifecycle` / `collectAsStateWithLifecycle`）安全收集。
4. **组件解耦与依赖注入**：坚持单一职责与依赖倒置原则，面向接口/抽象编程，保障模块高内聚低耦合与高可测试性。
5. **现代 Jetpack 库生态**：积极采用最新的 AndroidX / Jetpack 推荐组件，避免引入已废弃（Deprecated）或过时的旧 API。

## 测试策略

所有代码变更提交前需通过测试验证。详细说明见 [测试策略与指南](docs/testing.md)。

### 准则

1. **测试隔离**。单元测试不发起真实 HTTP 请求。
   - ViewModel / 页面测试用 Fake 仓库：`:core:testing` 里按接口各有一个（`FakeSettingsRepository`、`FakeFavoritesRepository`、`FakeRealtimeRepository`、`FakeSearchHistoryRepository`、`FakeLocationRepository`、`FakeAmapKeyRepository`），`:androidApp` 的 `FakeTransitRepository` 把它们合在一起给还拿 `ITransitRepository` 的 ViewModel 用。
   - 网络客户端和 Provider 测试用 Ktor `MockEngine`：`:core:network` 测试里的 `mockHttpClient { request -> MockReply(json) }`。要用真实数据时用录好的响应（`RecordedResponses`），不要在测试里连真服务器；需要重新录时每个接口只调一次。
2. **协程测试**。带轮询的 ViewModel 传入 `enableAutoPolling = false`，避免测试调度器死循环。
3. **验证命令**
   - 运行全量测试：`./gradlew test`
   - 运行单个测试类：`./gradlew :feature:bus:testAndroidHostTest --tests "com.trackr.feature.bus.home.BusHomeViewModelTest"`（`:androidApp` 里的测试用 `./gradlew :androidApp:testDebugUnitTest --tests "com.trackr.TrackrAppNavigationTest"`）
   - 功能模块 / 根 Composable 的 iOS 只做编译和测试链接：`./gradlew :feature:bus:compileKotlinIosSimulatorArm64`、`./gradlew :feature:bus:linkDebugTestIosSimulatorArm64`（其他 `:feature:*`、`:composeApp`、`:core:ui` 同理）
   - KMP 模块测试：`./gradlew :core:domain:testAndroidHostTest`（JVM，`:core:network`、`:core:data` 同理）、`./gradlew :core:domain:iosSimulatorArm64Test`（需要 Xcode 和 iOS 模拟器运行时）
   - 导航测试：`./gradlew :core:navigation:testAndroidHostTest`（JVM + Robolectric）
   - 打 release 包：`./gradlew :androidApp:assembleRelease`（产物在 `androidApp/build/outputs/apk/release/`）
   - 生成覆盖率报告：`./gradlew jacocoTestReport`

## 工程结构

- `:androidApp`（目录 `androidApp/`）：Android 应用，applicationId `com.github.u5151561.trackr`，包名 `com.trackr`。第 17 步起页面和 ViewModel 都搬走了，这里只剩 Android 平台部分：
  - `MainActivity`：只读 Intent（小组件 / 快捷方式 / 通知跳转、乘车码快捷方式），然后 `setContent { TrackrRoot() }`。
  - `TrackrApp`：启动 Koin，`appModules = appModule + platformBridgeModule + trackrModules`（`trackrModules` 来自 `:composeApp`）。`platformBridgeModule` 给出功能模块要的平台接口的 Android 实现（乘车码、签名、`LiveUpdateSettings`、`SystemSettingsLauncher`、`UserLocationStore`、版本号 `AppVersionInfo` 等）。
  - 其余：存储（`StorageBackend`、USE_NEW_STORE 开关、旧 SharedPreferences 仓库 `TransitRepository` / `RouteGroupRepository` / `ScheduledReminderRepository`）、定位、高德 Key 存储与同步、更新检查、追踪服务与通知、厂商适配、广播、小组件。
  - 依赖规则：正式代码只依赖 `:composeApp` 和 `:core:*`，不直接依赖 `:feature:*`（`testImplementation` 可以，截图和 Koin 图校验要用）。
- `:composeApp`（目录 `composeApp/`）：根 Composable，KMP + CMP，Android namespace `com.trackr.composeapp`。`TrackrRoot()`：主题、底栏（3 个页签：公交、路线、我的）、`NavDisplay`，把各 feature 的 `xxxEntries` 和 Koin 模块组装起来（`trackrModules` = `routeDataModule` + 各 feature 的 `xxxModule` + 平台模块，Android 多一个 `routeAndroidModule`）。依赖全部 `:feature:*` 和 `:core:*`，不依赖 `:androidApp`。测试在 `src/androidHostTest`（Robolectric）。
- `:core:ui`（目录 `core/ui/`）：业务通用组件，KMP + CMP。两个以上 feature 用的东西放这里：`commonMain` 放纯逻辑（`PollingCoordinator`、`PendingScreen`、导航工具）和 v3 共享组件（第 19 步：`transitcode/TransitCodeFab`、`TransitCodeChooserSheet`，`arrival/ArrivalUi`、`ArrivalTrailing`、`LineBadge`、`arrivalToneColor`）；第 17 步搬来的旧组件（`BusArrivalBadge`、分组卡片文案）和 `LiveUpdateSettings` 接口先放 `androidMain`。只依赖 `:core:*`。
- `:feature:*`（目录 `feature/<名字>/`）：功能模块，都用 `trackr.kmp.feature`。包名沿用搬来前的（`com.trackr.ui.screens.*`），模块入口在 `com.trackr.feature.<名字>`。
  - `:feature:bus`：v3 公交首页（第 19 步，全在 `commonMain`，包 `com.trackr.feature.bus`）：`BusHomeRoute` + 无状态 `BusHomeScreen(state, onEvent)`、`BusHomeViewModel`、城市选择。
  - `:feature:search`：搜索（`SearchViewModel`，entry 级，输入存 `SavedStateHandle`）。第 23 步起页面全在 commonMain：`SearchRoute` + 无状态 `SearchScreen(state, onEvent)`，结果行由 `searchRows()` 摊平；另依赖 `lifecycle-viewmodel-savedstate` 和 CMP 资源（用 `:core:designsystem` 的图标）。
  - `:feature:stop`：站点详情（`StopDetailViewModel`）。第 21 步起页面全在 commonMain：`StopDetailRoute` + 无状态 `StopDetailScreen(state, onEvent)`；选几条一起等、存成 1 程路线（`:core:domain` 的 `WatchTogetherRoute`）、一起等进行中视图、「上了哪辆？」导航弹层（`ScreenKey.ChooseBoardedBus` + `ChooseBoardedBusViewModel`，通知链接 `trackr://tracking/board` 和站点页遇到待选都打开它）。
  - `:feature:line`：线路详情（`LineDetailViewModel`）、时刻表、走向条、定时提醒入口、乘车码弹层（`androidMain/res` 中英文案）；底栏 = 追踪主按钮 + 换向圆钮（单程线不占位）+ 乘车码圆钮。乘车码圆钮（以及长辈版线路页 / 换站页底部的「出示乘车码」）和首页悬浮按钮共用一个开关 `SettingsRepository.showTransitCodeButton`，「我的」里关掉后都不画。
  - `:feature:route`：路线规划（`RoutePlanViewModel`）、路线详情（`RouteDetailViewModel`，第 22 步起页面在 commonMain：每程候选线勾选、追踪、改名 / 改分组 / 生成返程 / 删除）；高德 Android SDK 封装（`AmapSdkManager`、`AmapSdkKeyApplier`、`AndroidAmapRouteGateway`、地图会话）放 `androidMain`，Koin 在 `routeAndroidModule`。另依赖 `:core:network`。
  - `:feature:favorites`：编辑收藏（`FavoritesEditViewModel`，第 22 步起页面在 commonMain）。第 33 步起读收藏合一仓库的 `FavoritesRepository.favoriteItems`（车 + 路线同一列，和首页「收藏」同一份数据）：拖动排序走 `move(keys)`、点一条改分组走 `setGroup`、删除按 key 分 `removeFavorite` / `removeRoute`，分组计数按 `groupId` 统计。旧收藏页的卡片、分组弹窗组件暂留 `androidMain`，第 29 步清理。
  - `:feature:me`：我的、定时提醒编辑、高德 Key、关于（`MeViewModel`、`ReminderEditViewModel`、`AmapKeyViewModel`、`AboutViewModel`）；页面都在 commonMain。
  - 源集规则：ViewModel、UiState、Koin 模块 `xxxModule`、`expect fun xxxEntries(navigator)` 放 `commonMain`；旧 Screen 原样放 `androidMain`，`actual xxxEntries` 在 `androidMain` 接旧页面、`iosMain` 先放空壳（阶段 D 逐页重写进 `commonMain`）。`commonMain` 不写 `android.*`，要平台能力就经接口注入（`UserLocationStore`、`LiveUpdateSettings`、`MapSdkInitializer`、`AmapSdkKeyApplier`、`AppVersionInfo`）。
  - 依赖规则：feature 只依赖 `:core:*`，feature 之间不互相依赖。两个 feature 都要的东西下沉到 `:core:ui` 或 `:core:data`；同时用两个 feature 的页面放 `:composeApp`。
  - 测试：ViewModel 测试在 `src/commonTest`，Compose UI / SDK 测试在 `src/androidHostTest`（Robolectric，`resources/robolectric.properties` 里 `sdk=34`）。
- `:core:designsystem`（目录 `core/designsystem/`）：设计系统，KMP + Compose Multiplatform。代码在 `src/commonMain`，图标和通用文案在 `src/commonMain/composeResources`（生成类 `com.trackr.core.designsystem.resources.Res`），测试在 `src/androidHostTest`。入口 `TrackrTheme(uiMode)`；页面读 `MaterialTheme.typography.*`、`TrackrTheme.colors.*`、`TrackrTheme.dimens.*`。
- `:core:model`（目录 `core/model/`）：领域模型，KMP 纯 Kotlin，包名 `com.trackr.core.model`（`@Serializable` 数据类、枚举、`ProvinceCityCatalog`、`UserFix`）。不跑 Compose 编译器，页面直接用作参数的扁平模型要登记在根目录 `compose_stability.conf`。
- `:core:domain`（目录 `core/domain/`）：领域层，KMP 纯 Kotlin，包名 `com.trackr.core.domain.*`：`arrival`（`ArrivalText`、`ArrivalRules`）、`tracking`（追踪状态机 `TrackingEngine`：开始 / 轮询 / 我上车了 / 下一程 / 轮询间隔 / 下车站判定，产出提醒、换乘提醒、结束等效果；内部用 internal 的 `SingleLegTracker`（单线路选车 + 提醒；开着「上车后自动结束追踪」时也用 `BoardingDetector` 判人有没有跟着车走）、`GroupLegTracker`、`BoardingDetector`、`WatchTogetherTracker`（一起等多条线，第 15 步：`watchPollPlan` 决定每轮请求什么、`reduceWatchPoll` 处理一轮、「我上车了」按 §2.6 一条规则选车，不同线的车同时在站又分不清时写 `boardChoice`）、`BoardingRule`，以及公开的定位档位 `LocationCadence`）、`math`（`RouteProjectionMath`、`CoordTransform`）、`platform`（平台能力接口 `TrackingController`、`ReminderAlarms`、`LocationSource`、`UpdateChecker` 等；第 17 步加 `AmapSdkKeyApplier`、`MapSdkInitializer`、`AppVersionInfo`；定位提示跳系统设置页用 `SystemSettingsLauncher`（`NoSystemSettingsLauncher` 是默认空实现）；墙钟用 `kotlin.time.Clock`、单调时钟用 `MonotonicClock`）。`commonMain` 不写 `java.*` / `android.*`，「现在」由调用方传入。测试在 `src/commonTest`（`kotlin.test`）。
- `:core:network`（目录 `core/network/`）：网络层，KMP。Ktor 客户端 `createTrackrHttpClient()`（Android 用 OkHttp 引擎，iOS 用 Darwin；总超时 20 秒）、车来了 / 掌上公交 / 高德 Web 客户端、车来了加解密（`expect`/`actual`）、两个数据源的 `ITransitProvider`、`SingleFlightCache`、`AmapLruCache`。解析代码用 `com.trackr.core.network.json` 里和 org.json 同规则的只读 JSON。
- `:core:data`（目录 `core/data/`）：数据层，KMP。仓库接口 `SettingsRepository`、`FavoritesRepository`、`RealtimeRepository`、`SearchHistoryRepository`、`LocationRepository`、`RoutePlanRepository`、`AmapRouteGateway`；追踪会话的唯一数据源 `TrackingRepository`（`DefaultTrackingRepository`：内存里一份**会话列表**加锁、每次变化排队写盘，按 sessionId + 代际号核对丢旧结果，进程重建后读回标成中断、服务接上后恢复；可以同时追多项，同一个目标再点一次是替换不是叠加，一起等会把同站单独追的那条线收进来；一起等的 `chooseBoardedBus`、按线去掉一条的 `removeWatchLine`）；一起等取数 `WatchTogetherPoller`（等车每轮 1 次站点请求，站点结果缺的线才单独请求线路接口、并发 ≤4；待确认只请求在站的线）；实现 `DefaultRealtimeRepository`（实时查询、线路详情 6 秒合并缓存）、`DefaultLocationRepository`（经 `LocationSource`）；按数据源选 Provider 的 `TransitProviderCoordinator`。高德 Key 两槽 `AmapKeyRepository`（各槽「用户填的 > 构建时的」、互不回落；由它算出 `hasMapKey`：底栏有没有「路线规划」，`canShowMap`：路线页画不画地图，规则在 `AmapKeyRules`，第 11 步）。收藏合一（第 14 步）：`DefaultFavoritesRepository` 是收藏的车、路线、收藏分组和合一顺序的唯一持有者（`favoriteItems`、`move`、`setGroup`、删分组后收藏变不分组），`:androidApp` 的 `TransitRepository`（收藏部分）和 `RouteGroupRepository` 都转调它。`local/`：仓库下面的存储层 `TransitLocalStore`、`FavoritesStore`、`ReminderStore`、`AmapKeyStore` 及新实现（Room + DataStore）；`androidMain/legacy/`：旧 SharedPreferences 导入（`LegacyLibraryMigration`、只读的 `LegacySettingsMigration` / `LegacyAmapKeyMigration`，旧 key 一律不删）。设置 / 搜索历史的仓库仍是 `:androidApp` 的 `TransitRepository`，存储经 `TransitLocalStore`。`route/`（第 17 步从 `FavoritesGroupViewModel` 拆出来）：`DefaultRoutePlanRepository`（算路按城市 + 坐标 + 15 分钟时间桶缓存、输入提示按城市 + 关键字缓存）、`RouteAlignmentService`（路线对齐实时线路）、`RouteGroupSaver`、`FavoriteGroupSelection`、`FavoritesGroupDataFix`，Koin 在 `:composeApp` 的 `routeDataModule`。`UserLocationStore`：首页定位写入的最近坐标（Android 由 `ITransitRepository` 适配）。
- `:core:database`（目录 `core/database/`）：Room KMP，库文件 `trackr.db`（参与备份），表结构导出在 `core/database/schemas/`。当前版本 2（第 14 步加 `favorite_item`，迁移在 `Migrations.kt`）。改表结构要升版本号、在 `TRACKR_MIGRATIONS` 里加 Migration、在 `:core:data` 的 `TrackrDatabaseMigrationTest` 里按旧版 schema JSON 建库验证，不要开 destructive fallback。装过新版本库的手机不能再装低版本的包。
- `:core:datastore`（目录 `core/datastore/`）：DataStore KMP，`files/datastore/` 下三个文件：`trackr_settings.preferences_pb`（参与备份）、`trackr_amap_keys.preferences_pb`（备份排除）、`tracking_session.json`（追踪会话，`TrackingRepository` 读写；USE_NEW_STORE 关时只在内存里；备份排除）。改这些文件名要同时改 `androidApp/src/main/res/xml/` 的两份备份规则（`BackupRulesTest` 会先失败）。
- 存储开关 USE_NEW_STORE（第 9 步，`androidApp/.../data/local/StoreSwitch.kt`）：默认新存储；`files/store_switch` 写 `old` 后杀进程重开即退回旧 SharedPreferences，写 `new` 或删掉文件恢复。新存储打不开或导入出错时自动退回旧存储。
- `:core:testing`（目录 `core/testing/`）：测试辅助，平台能力接口的 Fake、`FakeClock`、`:core:data` 仓库接口的 Fake。
- `:core:navigation`（目录 `core/navigation/`）：导航，KMP + CMP 版 Navigation 3，包名 `com.trackr.core.navigation`。`ScreenKey`（新增子类要同时登记进 `TrackrSavedStateConfiguration`）、`TopLevel`（页签）、`NavigationState`（每个页签一条返回栈 + 当前页签，`rememberNavigationState()`）、`Navigator`（所有导航动作：`open`、`back`、`selectTab`、关弹层的 `dismiss` 等；弹层 Key 实现 `OverlayKey`，叠在根页上底栏不收起）、`TrackrTransitions`。页面不直接改返回栈，一律调 `Navigator`。测试：`commonTest` 的 `NavigatorTest`、`NavigationKeyTest`，`androidHostTest` 的 `NavigationStateRestorationTest`（Robolectric）。
- `build-logic/convention`：约定插件，统一 SDK、JDK、单元测试与 Compose 编译器配置。
  - `trackr.android.application`：`:androidApp` 用。
  - `trackr.kmp.library` + `trackr.kmp.compose`：KMP 模块用（Android + iosArm64 + iosSimulatorArm64），`:core:designsystem`、`:core:navigation`、`:core:ui`、`:composeApp` 两个都用，`:core:model`、`:core:domain`、`:core:network`、`:core:data`、`:core:database`、`:core:datastore`、`:core:testing` 只用 `trackr.kmp.library`。
  - `trackr.kmp.feature`：`:feature:*` 用，= `trackr.kmp.library` + `trackr.kmp.compose` + 共用依赖（`KmpFeatureConventionPlugin.kt`）。commonMain：`:core:model` / `domain` / `data` / `navigation` / `designsystem` / `ui`、CMP、KMP 版 ViewModel 和 lifecycle-compose、协程、kotlinx-datetime、Koin（core / compose / compose-viewmodel）；androidMain：Jetpack Compose（Material 3、图标）、activity-compose、koin-android；commonTest：kotlin.test、`:core:testing`、coroutines-test、Turbine；androidHostTest：Robolectric、Compose UI 测试。feature 的 `build.gradle.kts` 一般只写插件，额外的依赖（如 `:feature:route` 的 `:core:network`、高德 SDK）才单独加；不要在这里加别的 `:feature:*`。
