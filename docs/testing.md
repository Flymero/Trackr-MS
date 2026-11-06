# 测试策略与指南

本项目测试涵盖核心算法、ViewModel 业务逻辑、Navigation 3 路由、Compose 界面行为与 JaCoCo 代码覆盖率。

---

## 1. 测试架构概览

- 单元测试框架：JUnit 4 (`junit:junit:4.13.2`), `kotlinx-coroutines-test:1.10.2`
- 无头环境与平台测试：Robolectric (`org.robolectric:robolectric:4.14.1`)，使用预置 `android-all-14-robolectric-10818077` 离线桩
- Compose UI 测试：`androidx.compose.ui:ui-test-junit4`, `androidx.compose.ui:ui-test-manifest`
- 协程与数据流测试：`MainDispatcherRule` (基于 `StandardTestDispatcher` 与 `Dispatchers.setMain`), `Turbine`
- 代码覆盖率：JaCoCo Gradle Plugin (`jacocoTestReport`)

---

## 2. 内存隔离机制 (FakeTransitRepository)

单元测试不发起真实网络请求，也不依赖本地存储。测试套件通过接口 `ITransitRepository` 注入 `FakeTransitRepository`（位于 `androidApp/src/test/java/com/trackr/data/repository/FakeTransitRepository.kt`）。

支持配置以下模拟响应：
- `supportedCitiesResult`：城市列表
- `stopDetailResult`：站点详情与多站台途经线路
- `lineDetailResult`：线路在途公交与反向线路
- `searchResult`：线路与站点混合搜索
- `favorites`：收藏夹变更

### 协程后台轮询参数化

为避免 ViewModel 中 `while(isActive) { delay(...) }` 轮询导致协程测试调度器死循环，所有带后台轮询的 ViewModel 均提供 `enableAutoPolling: Boolean = true` 构造参数：
- 生产环境：使用默认值 `true`，执行 10s 或 15s 轮询。
- 测试环境：传入 `enableAutoPolling = false`，通过 `testScheduler.runCurrent()` 或 `testScheduler.advanceTimeBy(ms)` 控制执行时序。

---

## 3. 测试目录与套件清单

`./gradlew test` 共跑 93 个测试类、XXX 个用例：

| 模块 | 测试类 | 模块 | 测试类 |
| :--- | :--- | :--- | :--- |
| `:androidApp` | 26 | `:core:ui` | 4 |
| `:composeApp` | 2 | `:feature:bus` | 4 |
| `:core:data` | 12 | `:feature:favorites` | 3 |
| `:core:database` | 1 | `:feature:line` | 6 |
| `:core:datastore` | 1 | `:feature:me` | 2 |
| `:core:designsystem` | 2 | `:feature:route` | 5 |
| `:core:domain` | 4 | `:feature:search` | 3 |
| `:core:navigation` | 5 | `:feature:stop` | 4 |
| `:core:network` | 13 | `:core:testing` | 1 |

`:core:model` 没有测试。KMP 模块的 `test` 由约定插件 `trackr.kmp.library` 补上，只跑 Android 主机测试（`testAndroidHostTest`）；iOS 另跑 `./gradlew :core:domain:iosSimulatorArm64Test`（`:core:network`、`:core:data` 同理，需要 Xcode 和 iOS 模拟器运行时），`:core:database` 的 DAO 测试同样在 iOS 模拟器上跑（`./gradlew :core:database:iosSimulatorArm64Test`，用 BundledSQLiteDriver），设计系统只做编译检查 `./gradlew :core:designsystem:compileKotlinIosSimulatorArm64`，导航做编译检查加测试链接 `./gradlew :core:navigation:linkDebugTestIosSimulatorArm64`。各 `:feature:*`、`:composeApp`、`:core:ui` 在 iOS 上也只做这两步：`./gradlew :feature:bus:compileKotlinIosSimulatorArm64`、`./gradlew :feature:bus:linkDebugTestIosSimulatorArm64`（其他模块换模块名）。

第 17 步起页面和 ViewModel 搬进 `:feature:*`、`:composeApp`、`:core:ui`，测试跟着搬（包名不变）：

- ViewModel 测试放各模块的 `src/commonTest`，用 `kotlin.test`。`commonTest` 里没有 JUnit 的 `MainDispatcherRule`，改用 `:core:testing` 的 `MainDispatcherHolder`：`@BeforeTest` 调 `install()`、`@AfterTest` 调 `reset()`，用例写 `runTest(main.dispatcher)`，`viewModelScope` 里的 `delay` 才受 `advanceTimeBy` 控制。
- 这些测试看不到 `:androidApp`，Fake 都在 `:core:testing`：`FeatureTestSupport.kt`（`FakeUserLocationStore`、`MainDispatcherHolder`）、`RouteGroupFakes.kt`（`InMemoryFavoritesStore`、`InMemoryRouteGroupRepository`（转调真实的 `DefaultFavoritesRepository`）、`FakeAmapRouteGateway`、`FakeLocalMigrationFlags`）、`SearchFakes.kt`（`RecordingSearchRealtimeRepository`：记下每次搜索，可让搜索挂起）。`FakeScheduledReminderRepository` 也从 `:androidApp` 挪进了 `:core:testing`。
- Compose UI 测试和高德 SDK 测试放 `src/androidHostTest`，跑 Robolectric。新模块的 Robolectric 配置在各自 `src/androidHostTest/resources/robolectric.properties`（`sdk=34`）。
- 单跑某个模块：`./gradlew :feature:route:testAndroidHostTest`（其他模块同理）。

Koin 由 `TrackrApp.onCreate` 启动，Robolectric 用例里每个用例都会重新启动一次。第 4 步留下的 object 转发第 12 步删了：通知、提醒用 Koin 里的 `LiveProgressNotifier`、`ArrivalAlertNotifier` 实例（Robolectric 用例里 `GlobalContext.get().get()`），纯函数和常量在 `ReminderAlarmScheduler`、`AmapKeyStorage` 的伴生对象里。要换掉某个依赖（如定位、实时数据、`TrackingController`）时，在用例里 `loadKoinModules(module { single<接口> { fake } })` 覆盖，下一个用例重启 Koin 后自动恢复。

追踪会话只有一份，在 `TrackingRepository`（`:core:data`）。ViewModel 测试用真实的 `DefaultTrackingRepository(InMemoryTrackingSessionStore(), scope)` 加 `FakeTrackingController(repository)`（命令转给仓库，不启动服务）；要造某个追踪状态时 `start` 一个目标后用 `update(generation) { it.copy(state = ...) }` 改。

第 9 步起 `TrackrApp.onCreate` 会打开本地存储（`StorageBackend`：默认 Room + DataStore，并把旧 SharedPreferences 导入一次）。带 `TrackrApp` 的 Robolectric 用例因此用的是新存储；要测旧存储，直接 new 仓库的旧构造函数（`RouteGroupRepository(context)`、`ScheduledReminderRepository(context)`），要单独测存储本身，用 `@Config(application = Application::class)` 不启动 `TrackrApp`（同一进程里同一个 DataStore 文件只能开一个）。

`:androidApp` 的测试位于 `androidApp/src/test/java/com/trackr/`，下表按子目录列出全部测试类（辅助类 `FakeTransitRepository`、`MainDispatcherRule` 不含测试；`FakeTransitRepository` 由 `:core:testing` 里按接口拆开的 Fake 合成）。功能模块、`:composeApp`、`:core:ui` 的测试见后面单独一张表。`:core:designsystem` 的测试位于 `core/designsystem/src/androidHostTest/kotlin/com/trackr/core/designsystem/`：`rail/RouteRailDrawUtilsTest`（走向条绘制工具常量与绘制不崩溃）、`theme/TrackrThemeTest`（v3 蓝浅 / 深两套且不用动态取色、`TrackrColors` 与旧名别名、`eta` / `item` 字号、圆角与尺寸、Compose 资源在 Android 上能读到），都是 Robolectric Compose UI。单跑：`./gradlew :core:designsystem:testAndroidHostTest`。

`:core:domain` 的测试放 `core/domain/src/commonTest/kotlin/com/trackr/core/domain/`，用 `kotlin.test`，Android 主机和 iOS 模拟器各跑一遍：`arrival/ArrivalTextTest`、`math/CoordTransformTest`、`tracking/TrackingEngineTest`（第 12 步把第 7 步搬来的 `GroupLegTrackerTest`、`SingleLegTrackerTest`、`BoardingDetectionTest` 全部用例和原 `TransferAutoSwitchManagerTest` 的纯逻辑用例并进来，覆盖范围见下表）。`src/androidHostTest` 里的 `JvmParityTest` 只在 JVM 上跑：逐个比对第 7 步换掉的 JVM API（`String.format(Locale.CHINA, "%.1f公里")`、`Math.toRadians`、`Math.round`、`"%02d:%02d".format`）和新写法结果一致。`:core:testing` 放平台能力接口的 Fake（`FakeClock`、`FakeTrackingController`、`FakeAnnouncer`、`FakeLocationSource`、`FakeReminderAlarms` 等），`FakesTest` 测这些 Fake 本身。单跑：`./gradlew :core:domain:testAndroidHostTest`。

| 目录 | 测试类 | 覆盖范围 | 运行方式 |
| :--- | :--- | :--- | :--- |
| 根目录 | `TransitUnitTest` | 核心算法（`RouteProjectionMath` 坐标插值与站名纠错、线路与城市模型；车来了加解密第 8 步搬到 `:core:network` 的 `ChelaileCryptoTest`） | 本地 JVM |
|  | `TrackrAppNavigationTest` | 用真实的 `TrackrRoot` 走一遍（首页 ViewModel 换成接 Fake 的）：首页根页在轮询；切到「更多」后停轮询，超过一个周期（15 秒）再回来立即补刷；进搜索后停轮询，15 秒内回来不补刷；收藏页是首页栈的子页，返回回首页；进程重建后恢复到「更多」时新建的首页 ViewModel 不轮询、切回首页才开始；第 18 步 deep link（线路详情 ViewModel 也接 Fake）：冷启动带链接 → `[首页, 线路详情]`、首页停轮询、返回回首页根页；热启动在「更多」或首页子页（搜索）上收到链接 → 首页栈重置为 `[首页, 线路详情]`，返回直接回首页根页；同一条链接重放不叠第二页；「上了哪辆？」链接把弹层叠在当前页上（首页和底栏照常在）、点「还没上」弹层关掉并清掉待选、链接里的会话已经结束时弹层自己关；搜索页（第 23 步，entry 级 + `SavedStateHandle`）：输入的词进程重建后还在，离开再进来是空的 | Robolectric Compose UI |
|  | `MainActivityDeepLinkTest` | 真实的 `MainActivity`（第 18 步）：冷启动带 `trackr://line` 打开对应线路详情、Intent 里的链接被清掉、返回回首页根页；旧版 6 个 extra 仍能打开；热启动 `onNewIntent` 同样；带保存状态重建时不重放；`trackr://transit-code` 直接拉起支付宝乘车码、不动栈；坏链接忽略 | Robolectric Compose UI |
| `deeplink` | `DeepLinkIntentsTest` | Intent ↔ `trackr://` 链接（第 18 步）：data 里的新链接；旧版 6 个 extra、两种拼法的旧 `OPEN_TRANSIT_CODE` action 兼容（只有 alipay 是支付宝）；`consume` 后同一个 Intent 不重放、不碰非 `trackr` 的 data；别的 App 传来的坏 Intent 忽略；动态快捷方式发的是 `ACTION_VIEW` + `trackr://line` | Robolectric (JVM) |
| `data/remote/amap` | `AmapSdkManagerTest` | 高德 Key 本地持久化（`AmapKeyStorage`）：Android / Web 两槽分开存、读 Android Key 不回落到 Web 槽、旧 Key 从 `trackr_prefs` 挪出只做一次、新文件里已有的 Key 不被旧值覆盖（SDK 封装的用例第 17 步搬到 `:feature:route`） | Robolectric (JVM) |
|  | `AmapSdkKeySyncTest` | 改了 Android Key 才重新注入 SDK：跳过启动时的值、改 Web Key 不注入、重复 `start()` 不开第二个收集 | 本地 JVM |
| `data/remote/update` | `GithubUpdateCheckerTest` | 更新检查多源回退、强制更新判定、下载地址 https 与域名白名单校验、取消后不再试剩下的源（Ktor `MockEngine`） | Robolectric (JVM) |
| `data/local` | `StorageBackendTest` | 整套存储端到端：同一份旧数据经新存储和旧存储读出来完全相同、旧文件不被改动、新存储写入重开读回、USE_NEW_STORE 关了再开能带回旧存储期间的改动、库打不开时退回旧存储、旧的聚合配置在新旧存储里读成同一个主数据源 | Robolectric (JVM) |
|  | `BackupRulesTest` | `backup_rules.xml` 和 `data_extraction_rules.xml` 两段：排除高德 Key（新旧两个文件）和追踪会话、不排除 `trackr.db` 和设置、没有 include、三段一样、Manifest 都接上了 | Robolectric (JVM) |
| `data/repository` | `RouteGroupRepositoryTest` | 旧存储（SharedPreferences）上：路线分组与收藏分组增删改排序、并发写入不丢更新、JSON 损坏备份（第 14 步起经收藏合一仓库） | Robolectric (JVM) |
|  | `RoomRouteGroupRepositoryTest` | 新存储（Room）上：读回逐字段一致（程、候选线、轨迹点）、清理假数据、删分组时路线一起改成不分组、排序和加候选线持久化、并发写入不丢更新 | Robolectric (JVM) |
|  | `ScheduledReminderRepositoryTest` | 旧存储上：定时提醒增删改与持久化、编辑已有提醒是原位替换并按新时间重排闹钟、并发写入不丢更新、JSON 损坏备份、计划触发时间记录 | Robolectric (JVM) |
|  | `RoomScheduledReminderRepositoryTest` | 新存储上：增删改与重开读回、并发保存 / 开关不丢更新、计划触发时刻存进 `reminder.lastScheduledTriggerAt` 并且改别的提醒时不被冲掉 | Robolectric (JVM) |
| `di` | `KoinGraphVerifyTest` | Koin 依赖图：`appModule`、`platformBridgeModule`、`routeDataModule`、各 feature 的 `xxxModule` 和 `routeAndroidModule` 各自 `verify()`，静态检查构造参数都有绑定；`appModules` 就是这些模块按顺序拼起来；Robolectric 里把每个定义实际取一次（带参数的 ViewModel 用样例 key；`SearchViewModel` 给一个 `SavedStateHandle`） | Robolectric (JVM) |
| `receiver` | `ScheduledReminderReceiverTest` | 定时提醒触发、自动跟车需精确闹钟、传给 `TrackingController` 的单线路目标；降级：非精确闹钟或启动被拒时改发通知并追加「系统限制未能自动开启跟车」提示，点「开启实时追踪」仍失败时保留通知、成功才关掉；点通知发 `trackr://line` 链接、解析出同一条线路详情（第 18 步） | Robolectric (JVM) |
|  | `BootCompletedReceiverTest` | 开机禁用已错过的一次性提醒、时间变化重新调度、无关广播忽略 | Robolectric (JVM) |
| `service` | `ArrivalAlertManagerTest` | 到站/临近提醒与实时通知并存时的去重 | Robolectric (JVM) |
|  | `AndroidTrackingControllerTest` | 开始追踪时把「我的」里的到站前提醒写进目标、每次重读设置、后台不让启动前台服务时不留会话 | Robolectric (JVM) |
|  | `LiveProgressNotificationHelperTest` | 实时进度通知进度计算与文案；一起等通知：标题写最先到的、正文只带第 2 条线、「我上车了 / 乘车码 / 结束」（乘车码发 `trackr://transit-code` 打开 App）、「上了哪辆？」和乘车中文字；有「上了哪辆？」待选时点通知发 `trackr://tracking/board?session=`、显式指向 `MainActivity`，没有待选照旧只拉起 App | Robolectric (JVM) |
|  | `BusTrackingServiceTest` | 追踪服务：路线且有精确定位时前台服务带 `location` 类型、等车近站开定位；没有精确定位退回 `specialUse` 且不开定位；单线路不要 `location`；结束追踪时关定位并停服务；过时的启动请求先进前台再停；服务销毁时结束会话；一起等：3 条线每轮只 1 次站点请求、最先到的写进会话、带 `location` 类型，上车后只请求那一条线并以终点为目标 | Robolectric (JVM) |
| `service/vendor` | `LiveUpdatePromotionTest` | Android 16 Live Update 推广通知构建与厂商识别 | Robolectric (JVM) |
|  | `XiaomiFocusAdapterTest` | 小米焦点通知 JSON 负载构建 | Robolectric (JVM) |
| `ui` | `ScreenshotGeneratorTest` | 主要页面明暗主题截图生成 | Robolectric Compose UI |
| `util` | `ReminderSchedulerTest` | 提醒下次触发时间计算、错过的一次性提醒判定 | 本地 JVM |
|  | `SignatureUtilsTest` | 包名与签名 SHA-1（取签名历史中的当前证书） | Robolectric (JVM) |
|  | `TransitCodeHelperTest` | 支付宝/云闪付乘车码跳转 Intent | Robolectric (JVM) |
| `widget` | `FavoritesShortcutWidgetSyncTest` | 应用级收藏同步：启动时不刷新，收藏真变一次只刷新一次快捷方式与小组件，重复 `start()` 不开第二个收集 | Robolectric (JVM) |

功能模块、`:composeApp`、`:core:ui` 的测试类（第 17 步从 `:androidApp` 搬来或新写；目录相对各模块的 `src/commonTest/kotlin/com/trackr/` 或 `src/androidHostTest/kotlin/com/trackr/`）。原 `FavoritesGroupViewModelTest` 删了，用例按新 ViewModel 拆进 `RoutePlanViewModelTest`、`RouteDetailViewModelTest`、`FavoritesEditViewModelTest`，缓存部分进 `:core:data` 的 `RoutePlanRepositoryTest`：

| 模块 / 目录 | 测试类 | 覆盖范围 | 运行方式 |
| :--- | :--- | :--- | :--- |
| `:core:ui` `ui/util` | `PollingCoordinatorTest` | 定时 / 倒计时轮询策略：按周期触发、每秒倒计时到点触发、动作抛异常不打断；协调器生命周期与手动控制、重启倒计时（关了自动轮询也通知 tick）、`restart()` 让定时轮询从现在起重新等一个周期（暂停时不启动）、scope 取消后停止 | commonTest（JVM；iOS 只编译和链接测试，不跑） |
| `:core:ui` `ui/components/transitcode` | `TransitCodeFabTest` | 乘车码悬浮按钮：默认展开、点一下就打开；往下滑缩成图标（读屏仍读「乘车码」、能点）、往上滑展开；小于 8 px 的抖动不切换、在顶部继续下拉也展开 | Robolectric Compose UI |
| `:core:ui` `ui/components` | `BusArrivalBadgeTest` | 到站状态徽标文字与高亮（走 `ArrivalText`）：到站、快到算到站、0 站未到算下一站、剩余站数带 / 不带 ETA、3 分钟内高亮、站序无效、优先用状态文字、没车兜底 | androidHostTest（只 JVM） |
| `:core:ui` `ui/screens/favoritesgroup` | `LegTextsTest` | 规划页、路线详情还在用的纯函数：线路 id 是合成的算站数没对齐、跟车阶段标签、点线路进详情用上车站序号、到站分钟（先用 ETA，没有就按站数估） | commonTest（JVM） |
| `:feature:bus` `feature/bus/home` | `BusHomeViewModelTest` | 公交首页（第 19 步由 `HomeViewModelTest` 改名，原用例全部保留）：初始加载已选城市、切城市、城市汉字与拼音过滤、按数据源过滤城市、收藏监听；切数据源立即刷新附近站、附近站固定 6 个（「附近站数」设置不再影响首页）；启动定位（没存城市切到定位城市、存过就保留、离开已存城市回到城市中心）、下拉刷新保留城市、城市选择器里重新定位；结束追踪（追踪卡消失、路线状态清掉）；轮询暂停恢复，`setPollingActive` 恢复时离上次刷新 ≥15 秒立即补刷、不到不补；没可用定位时报错并保留位置；收藏合一（车和路线一个列表、分组 chips 筛选、分组删了回到「全部」）、没车时写下一班发车时间、数据没变沿用旧实例；乘车码（选了平台直接开、「每次询问」弹选择、勾「以后都用这个」才记成默认）；附近站请求失败发一次性消息 | commonTest（JVM；iOS 未验） |
| `:feature:bus` `feature/bus/home` | `BusHomeScreenTest` | 无状态 `BusHomeScreen`：标题是城市名、没有乘车码卡、悬浮按钮发事件；收藏行写上车站和「始发 → 终点」、点开线路页、「编辑」；有分组才显示 chips；没收藏一句话、没有「编辑」；一起等卡最先到的放大、多于 3 条折叠成「还有 N 条」点开展开、「结束」；附近站线路行进线路页、站名进站点页 | Robolectric Compose UI |
| `:feature:bus` `feature/bus/home` | `BusHomeMapperFavoriteNameTest` | 收藏卡线路号兜底（issue #20）：收藏里没存名字时显示线路号、名字和线路号都空时用实时数据里的名字、有名字时保持不动 | commonTest（JVM；iOS 未验） |
| `:feature:bus` `feature/bus/citypicker` | `CityPickerItemRowTest` | 城市选择列表项渲染与点击、没省份标签时不显示省份 | Robolectric Compose UI |
| `:feature:search` `ui/screens/search` | `SearchViewModelTest` | 搜索（第 17 步从首页拆出来）：搜索历史加入去重排序、删一条、清空、容量上限；输入防抖 300 ms 后只搜一次、关键字去首尾空格、防抖内换词取消旧的、新词取消进行中的请求、「搜索中」状态；空查询清结果并取消待发的搜索；换城市清结果保留查询词、换数据源有词才重搜；用选中城市和首页当前坐标搜（拆分前读首页的 currentLat / currentLng，经 `HomeCoordinates` 传过来），没有首页坐标时用 GPS、再没有用城市中心；输入写进 `SavedStateHandle`，带着词重建时显示并只搜一次（第 23 步） | commonTest（JVM；iOS 只编译和链接测试，不跑） |
|  | `SearchRowsTest` | 搜索结果摊成列表行（第 23 步）：每个方向一行「始发 → 终点」、小字只写已知的（地铁 · 运营时间 · 票价）、缺站名的退路、车站行途经线去重 / 退回地铁线名、距离、段内首中末位置、先线路后车站、重复线路 / 站 key 也唯一 | commonTest（JVM；iOS 只编译和链接测试，不跑） |
|  | `SearchScreenTest` | v3 搜索页（第 23 步，无状态 `SearchScreen(state, onEvent)`）：没输入只显示最近搜过的词、没有推荐词和双引擎卡；点词 / 点字最右边都是搜这个词、点 × 才删；全清先确认；每个方向一行、点哪行进哪个方向；车站行显示途经线和距离、点了进站点；没结果的提示、搜索中不提示；输入 / 键盘搜索 / 清空 / 返回发对事件 | Robolectric Compose UI |
| `:feature:stop` `ui/screens/stopdetail` | `StopDetailViewModelTest` | 站点详情：初次加载站台并解析站名、切物理站台后用那个站台刷新、收藏切换、轮询暂停恢复、上一轮没回来跳过本轮、回到页面只在数据过时时刷新（关了自动轮询不刷）；第 21 步 v3：站台按「往 终点」命名、缺数据或同名退回「站台 1 / 2」，行的到站文字和小字；选几条一起等（不设上限、不可选的行忽略、换站台清空勾选、返回先退出选线）、存成 1 程路线（只存一次）、开始一起等（失败留在选线并提示）；本站一起等进行中：按到站排序、切到会话的站台、页面自己不轮询、结束后补刷、别的站不显示；「再加一条」带上正在等的线、「我上车了」、「上了哪辆？」选车 / 还没上 / 划掉；乘车码（有默认直接开、「每次询问」弹选择、勾选记成默认）；下拉刷新 | commonTest（JVM；iOS 只编译和链接测试，不跑） |
|  | `StopDetailScreenTest` | v3 站点详情（无状态 `StopDetailScreen(state, onEvent)`）：普通模式显示站台、线路行、没有「查看全线」，点行进线路、点站台切换、按钮发事件；选线模式计数和按钮、没勾时按钮不可点；一起等视图最先到的在前、有「我上车了」，上车后隐藏 | Robolectric Compose UI |
|  | `ChooseBoardedBusTest` | 「上了哪辆？」弹层：标题、说明和每辆车都显示，点车发车 id，点「还没上」发 null | Robolectric Compose UI |
|  | `ChooseBoardedBusViewModelTest` | 「上了哪辆？」导航弹层（`ScreenKey.ChooseBoardedBus`）：有待选时显示每辆车和终点、选了交给 `TrackingController`、「还没上」后关；没有待选、链接里是别的会话、追踪结束都关 | commonTest（JVM；iOS 未验） |
| `:feature:line` `ui/screens/linedetail` | `LineDetailViewModelTest` | 线路详情（第 20 步 v3）：初次加载拆成静态 / 实时两半（带目标站序只请求一次、不带时为默认站补一次）；选站 / 手动刷新后下一轮从那一刻起再等 10 秒；下拉刷新只在下拉时画圈；站表先读磁盘缓存（网络没回来先显示、不挡请求、站表变了才写回、静态部分沿用旧实例、缓存上点过的站不被初次结果改掉）；跟车；收藏切换及提示条（有分组时带「换分组」、换分组后提示）；定时提醒保存并提示；换向找同名站；乘车码弹层与偏好；追踪当前站（提前 2 站）和「追踪本站」判断；车都过了用时刻表补下一班（快来的车不用）；上一轮没回来跳过；回到页面只在过时时刷新 | commonTest（JVM；iOS 只编译和链接测试，不跑） |
|  | `BusFollowContentTest` | 跟车内容渲染与空车辆处理 | Robolectric Compose UI |
|  | `LineDetailEntryScrollTest` | 从某个站点点进线路页：整页（含走向条）直接跳到那一站、主卡不被裁、收起 / 展开卡片、横排竖排两种走向条都跳到位；顶上「始发 → 终点」站名太长时滚动而不是打省略号 | Robolectric Compose UI |
|  | `StationArrivalContentTest` | 线路页主卡：在路上（大字分钟、小字站数、车牌、预计到达、下一辆）、到站了、车都过了只给下一班始发时间（没有时用时刻表或提示）、首班、末班已过、实时没回来显示「正在查车…」、追踪中标记、预警 chips 已去掉、「全线 N 辆车在途 · 查看」与候车站胶囊（只写站名、长站名在胶囊里滚动不省略）点击；反向切换按钮不在主卡 | Robolectric Compose UI |
| `:feature:line` `ui/components` | `ExpressiveRouteRailUiTest` | 竖向走向条（第 20 步删了横向）渲染与点击、「候车中」标记、放进 `LazyColumn` 的 `routeRailItems` 渲染与点击、车辆标签不进文字、线段颜色 | Robolectric Compose UI |
|  | `HorizontalRouteRailUiTest` | 横排走向条：站名竖排但读屏读完整站名、点站换候车、点车标跟随、空站表不画、长站名按字数给足高度不被省略号吃、贴满整宽；带括号的站名整段一个 Text、括号那几行换全角空格占位再画上横版括号（对齐字心、不再拆成单字，见 #23） | Robolectric Compose UI |
|  | `BusRailPositionTest` / `RailVerticalNameTest` | 横排走向条的车位置（按 `segmentProgress` 插值、没坐标贴站、站序认不出不画）与竖排站名的括号转换（哪些字换成横版括号、其余不换、一字一行） | commonTest（JVM；iOS 只编译和链接测试，不跑） |
|  | `TransitCodeBottomSheetTest` | 乘车码选择弹层：两个渠道都显示，点支付宝回调 `(ALIPAY, 不记住)` | Robolectric Compose UI |
| `:feature:line` `ui/components/timetable` | `TimetableBottomSheetTest` | 时刻表底部弹窗渲染 | Robolectric Compose UI |
| `:feature:route` `ui/screens/routeplan` | `RoutePlanViewModelTest` | 路线规划：输入提示防抖只请求最后一个词、太短的词不请求并清空、同城同词命中 LRU 缓存；算路取消上一次、相同查询走缓存、「我的位置」不可用时报错且不查 POI、跨城才带 `cityd`；高德 Key 跟着仓库走、旧 Web 槽不当 Android Key；存入收藏没选分组时用当前选中的收藏分组 | commonTest（JVM；iOS 只编译和链接测试，不跑） |
|  | `RoutePlanScreenTest` | 路线规划搜索台、起终点互换、存入收藏分组弹窗、方案卡片与特征标签（每个方案一个、并列给第一个、都不突出就不给）、方案摘要时间与分段文字、跟车状态文字、方案面板切页与按选中方案开始跟车、跟车中显示状态条；没有「配置Key」胶囊；只有 Web Key / 没有地图会话时不画地图、没有回中按钮 | Robolectric Compose UI |
| `:feature:route` `ui/screens/routedetail` | `RouteDetailViewModelTest` | 路线详情：给某程加候选线、多线路 ETA 聚合并选最快的线、开始跟车用卡片上选的候选线、临时方案开始跟车不保存且收藏前显示为临时 | commonTest（JVM；iOS 只编译和链接测试，不跑） |
|  | `RouteDetailScreenTest` | 路线详情页（第 22 步）：两程与候选线都显示、点候选线发出选中、开始追踪、临时方案「收藏」弹层（只问名字和分组）、给某程添加候选线 | Robolectric Compose UI |
| `:feature:route` `data/remote/amap` | `AmapSdkManagerTest` | 高德 SDK 封装：Android / Web Key 都是用户填的优先、空了回落内置（去空格）；隐私合规不崩、注入 Key 安全返回、没 Key 返回 false；地图不可用原因（Key 和 ABI 都有时为空、模拟器 ABI 不支持、缺 Key）。内置 Key 由 `setBuiltInKeys` 设定，不受本机 `local.properties` 影响 | Robolectric (JVM) |
|  | `AmapSdkTransitServiceTest` | 高德 SDK 公交路径转换为方案（含折线和末段步行、多候选线用主线站点）、无 Key 时的提示 | Robolectric (JVM) |
| `:feature:favorites` `ui/screens/favoritesedit` | `FavoritesEditViewModelTest` | 编辑收藏：初始分组与类别筛选、自定义收藏分组增删与筛选、删除 / 保存临时分组、存通勤路线自动在「家」里补返程、通勤与家分开筛选、手动生成返程、一次性数据修复只迁一次旧通勤并记版本；ViewModel 清掉时把进程级的选中收藏分组一起清掉 | commonTest（JVM；iOS 只编译和链接测试，不跑） |
|  | `FavoritesEditScreenTest` | 编辑收藏页（第 22 步）：分组和路线都显示、新建分组、点路线进详情、删路线先确认 | Robolectric Compose UI |
| `:feature:me` `ui/screens/me` | `MeViewModelTest` | 「我的」页（第 25 步）：数据源只有车来了 / 掌上公交二选一、乘车码偏好、到站前提醒全局设置（1–3 站）、定时提醒行显示最早一条开着的提醒和开关、实时通知 / 锁屏开关、状态栏胶囊（实时通知关着或 iOS 不显示、去设置、回前台重查）、高德 Key 状态文字跟着仓库、子页提示显示一次就删、提醒短文字；长辈版开关位置预留（不显示） | commonTest（JVM） |
|  | `ReminderEditViewModelTest` | 定时提醒编辑：读出已有提醒、提醒已删、改时间 / 重复日后保存是原位替换并给「我的」发提示、预设重复日、开了「到点自动开始追踪」又没有精确闹钟权限时提示（回前台重查、去设置、打不开设置时给提示）、删除要确认、连点保存只存一次 | commonTest（JVM） |
|  | `AmapKeyViewModelTest` | 高德 Key 页：包名 + SHA-1 来自 `AppSigningInfo`、iOS 没有 Android 槽和 SHA-1、只写改过的那一槽、清空一槽不动另一槽、长度不对不让存、提示文字跟输入和内置 Key、复制 / 打不开浏览器的提示 | commonTest（JVM） |
|  | `AboutViewModelTest` | 关于页更新检查：初始空闲、有更新 / 已最新 / 出错、关弹窗回空闲、检查中不重复检查、没有 `UpdateChecker`（iOS）时不显示「检查更新」 | commonTest（JVM） |
| `:composeApp` `ui/navigation` | `NavigationFlowTest` | D2 底栏「路线规划」：有 / 无 Key、只有 Web Key、只有 Android Key、只有构建时 Key 时显示与否；停在该页签时 Key 被清空回首页并清栈；恢复到该页签但已无 Key 时回首页；后来填了 Key 页签出现但不切过去 | Robolectric Compose UI |
| `:composeApp` `ui/screens/favoritesgroup` | `FavoritesGroupScreenTest` | 旧收藏页：多线路直达和多程换乘卡片只显示线路胶囊、点胶囊切候选线、同站换乘（步行 0）、分组新建与删除、快速新建分组胶囊、临时追踪置顶卡片且菜单里只有「保存」 | Robolectric Compose UI |

`:core:domain`、`:core:navigation`、`:core:network`、`:core:data`、`:core:database`、`:core:datastore` 和 `:core:testing` 的测试类（`:core:navigation` 单跑：`./gradlew :core:navigation:testAndroidHostTest`；网络测试一律用 Ktor `MockEngine`，不发真实 HTTP）：

| 模块 / 目录 | 测试类 | 覆盖范围 | 运行方式 |
| :--- | :--- | :--- | :--- |
| `:core:domain` `arrival` | `ArrivalTextTest` | 到站文字 8 种状态（在路上、快到/已到、车都过了、还没发车、末班已过、停运/无数据、加载中、没网）的大字/小字/色调/朗读文本，到站按 `ArrivalRules` 判，边界：0 站、≤3 分钟、末班刚过 | commonTest（JVM + iOS 模拟器） |
| `:core:domain` `math` | `CoordTransformTest` | WGS-84 / GCJ-02 坐标转换 | commonTest（JVM + iOS 模拟器） |
| `:core:domain` `tracking` | `TrackingEngineTest` | 追踪状态机：单线路选车、换车重置提醒、车辆短暂消失保持原车；路线两阶段（候车锁车、乘车到站、信号丢失）；定位跟车检测（待确认、上车、没赶上、超时兜底、错锁纠正）与定位档位；自适应轮询间隔与失败退避；下车站判定（含 WGS-84 转 GCJ-02）；开始 / 换乘切程（代际号 +1、只发换乘提醒）/ 走完结束 / 手动下一程 / 我上车了 / 改提醒站数；服务定位过滤；一起等（第 15 步，`watch_*`）：多线取最快、先后变化、到站提醒每辆一次、没定位不自动判上车、只有一辆在站、两辆在站定位分得清不弹窗 / 分不清或没定位写 `boardChoice`、选车与「还没上」、`boardChoice` 5 分钟清掉或被定位分清、手动上车按 A..A+1 最近、锁不到后续补锁并以终点为目标、某线无数据、10 条线等车阶段只发 1 次站点请求、有定位自动判上车且待确认只请求在站的线、只有 1 程无下车站到终点结束、上车后旧结果丢弃；第 21 步：站点页存的 1 程无下车站路线存了能原样读回、开始时按一起等追，有下车站的路线照旧按路线追 | commonTest（JVM + iOS 模拟器） |
| `:core:domain` 根目录 | `JvmParityTest` | 第 7 步换掉的 JVM API 与新写法逐个比对：公里数格式化、角度转弧度（逐位相同）、两种测距公式、分钟取整、提醒时刻格式化 | androidHostTest（只 JVM） |
| `:core:navigation` | `NavigatorTest` | `Navigator` 纯逻辑：栈顶去重压栈、逐页返回、非首页页签根页返回回首页、首页根页返回交给系统；切页签保留各自的栈、再点当前页签回根并发 `reselect`；`applyDeepLink`（第 18 步合成返回栈：冷 / 热启动都把目标页签栈重置为 `[根, 目标]`、别的页签不动、重放幂等、跳过根和相邻重复、未知页签落到首页）；`showInTab`（规划页去收藏：栈里已有就退到它、没有就回根再压，不叠重复页）；页签隐藏时清栈回首页；`isShowingRootOf`（首页轮询条件）；`stacksShown`（叠放起始页签 / 只显示当前页签）；`TopLevel.visibleTabs` 没 Key 时只去掉「路线规划」；第 17 步起 3 个页签，「收藏」不能再当页签切、作为首页栈的子页打开，`popChildPage` 只在当前栈里退、根页不切页签；弹层 Key（`OverlayKey`）：叠在页签根页上仍算根页（底栏不收起）、叠在子页上不算，`open` 重放不叠第二个，`dismiss` 只拿掉那个弹层（不在栈里时什么都不做，页签根页不动） | commonTest（JVM + iOS 模拟器） |
|  | `DeepLinkParserTest` | `DeepLinkParser` / `DeepLinkUris`（第 18 步，§3.6 表）：line / route / stop / tracking/board / transit-code 五种链接；和拼链接的函数往返（含掌上公交带中文和冒号的 id、线名里的 `& = # ? / +`）；scheme / host 不分大小写；外来 scheme、未知 host、段数不对、空白 / 控制字符 / 超长 id、坏的百分号编码忽略；可选参数坏了退回默认值 | commonTest（JVM + iOS 模拟器） |
|  | `NavigationKeyTest` | `ScreenKey` 用 JSON 序列化与反序列化（第 16 步从 `:androidApp` 搬来） | commonTest（JVM + iOS 模拟器） |
|  | `NavigationStateRestorationTest` | `StateRestorationTester` 模拟进程重建后 3 条栈和当前页签、页内 `rememberSaveable` 都恢复；存档后页签少了一个（4 → 3）时每条栈仍归原页签、不按位置错给「更多」；切页签不丢另一条栈的页内状态和 ViewModel、弹出页面只清它自己的 ViewModel；`TrackrSavedStateConfiguration` 登记了全部 `ScreenKey`，默认配置存不了 | Robolectric Compose UI（只 JVM） |
|  | `NavigationSceneAndResultTest` | 第 18 步导航基建：`BottomSheetSceneStrategy` 把带 `bottomSheet()` 的 entry 叠在上一页上显示、返回关掉，没带的照常单页；所有 entry 共用的 `ResultEventBus`：弹层 `sendResult`，下面那页 `ResultEffect` 收到 | Robolectric Compose UI（只 JVM） |
| `:core:network` `json` | `JsonCompatTest` | 和 org.json 同规则的只读 JSON：optString / optInt / optDouble / optBoolean / opt 的取值与默认值 | commonTest（JVM + iOS 模拟器） |
|  | `JsonCompatParityTest` | 同一段 JSON 每个字段分别用兼容层和真正的 org.json 取值，结果逐个相同 | androidHostTest（只 JVM） |
| `:core:network` `remote/common` | `BaseTransitApiClientTest` | 地铁站名识别、距离计算、车牌清洗；HTTP/JSON 执行封装、异常信息不带 query、协程取消时底层请求一起取消、20 秒总超时（换成小值验证）且超时消息不带 key | commonTest（JVM + iOS 模拟器） |
|  | `SingleFlightCacheTest` | 同 key 并发只加载一次、取消一个等待者不影响其他、全部取消后下次重新加载、失败不缓存、TTL 内复用与过期、TTL 为 0 只去重、条目数上限、手动失效 | commonTest（JVM + iOS 模拟器） |
| `:core:network` `remote/amap` | `AmapLruCacheTest` | 高德结果 LRU 缓存满时淘汰最久未用的条目 | commonTest（JVM + iOS 模拟器） |
|  | `AmapTransitApiClientTest` | 高德 Web API 换乘方案解析、6 位小数坐标与 URL 编码、`cityd` / `nightflag`、发出去的地址和拼好的串一字不差、输入提示解析 | commonTest（JVM + iOS 模拟器） |
|  | `AmapFormatJvmTest` | 换德语 Locale 后坐标仍用 `.`；定点格式化逐个对比 `String.format(Locale.ROOT, "%.6f")`、表单编码逐个对比 `URLEncoder` | androidHostTest（只 JVM） |
| `:core:network` `remote/chelaile` | `ChelaileCryptoTest` | 签名官方测试向量、信封截取（含字符串里的括号）、固定密文解密、H5 备用密钥回退、非 JSON 明文报错、带换行的 Base64 | commonTest（JVM + iOS 模拟器） |
|  | `ChelaileCryptoJvmTest` | javax.crypto 加密 → common 解密 200 组往返；签名对比 `MessageDigest` | androidHostTest（只 JVM） |
|  | `ChelaileLineNameFallbackTest` | 上游只给 `lineNo`、`name` 是空串时（站点详情 / 线路详情）线路名退回线路号，不再画成空白线路号徽标（issue #20） | commonTest（JVM + iOS 模拟器） |
| `:core:network` `provider` | `TransitProviderTest` | 车来了 Provider 多物理站台并发补全、附近站过滤与站点状态映射；掌上公交站点详情、搜索、附近站、城市列表 | commonTest（JVM + iOS 模拟器） |
|  | `TransitProviderCacheTest` | Provider 层省请求：车来了附近站补全短缓存、其他站台只在首次加载补全、空主查询下次跳过；掌上公交 cmd103 缓存只轮询 cmd104、搜索回退结果线路与站点共用、附近站短缓存、反向地理编码空结果短缓存 | commonTest（JVM + iOS 模拟器） |
|  | `RecordedResponsesTest` | 录好的真实响应（`RecordedResponses`，车来了 5 个接口、掌上公交 4 个 CMD）走一遍客户端和解析，并核对发出去的请求头和表单 | commonTest（JVM + iOS 模拟器） |
| `:core:data` `provider` | `TransitProviderCoordinatorTest` | 数据源二选一（D1）：搜索、附近站、站点详情只查当前数据源，搜索去掉地铁并按线路名自然排序；线路详情按来源 / id 前缀路由，认不出来走当前数据源；逆地理先车来了、空了再掌上公交；城市列表两家合并 | commonTest（JVM + iOS 模拟器） |
| `:core:data` `repository` | `AmapKeyRepositoryTest` | 高德 Key 两槽（§2.5）：各槽「用户填的 > 构建时的」、互不回落（含原 `customAmapApiKey_doesNotFallBackToLegacyWebSlot`）；`hasMapKey` / `canShowMap` 四种组合；setter 后立刻更新、只写自己的槽；没有 Android 地图 SDK 的平台不看 Android 槽 | commonTest（JVM + iOS 模拟器） |
|  | `TrackingRepositoryTest` | 追踪会话：新实例读回（标成中断、服务接上后恢复且代际号 +1）、太旧的不读回、旧代际的结果被丢弃、停止后代际号不回退、update 在当前值上算、我上车了用最近一次轮询的车、切程和开始 / 停止清掉定位、换乘组视图；一起等「我上车了」两条线都在站写待选、选了上车代际号 +1、一条线在站直接上、会话带着几条线读回 | commonTest（JVM + iOS 模拟器） |
|  | `WatchTogetherPollerTest` | 一起等取数（第 15 步）：10 条线都在站点结果里只发 1 次站点请求、缺线（不在结果里 / 没车也没状态）才单独请求线路接口、单独请求最多同时 4 个、掌上公交站点接口不带来车就逐条请求、待确认只请求计划里的线、站序不同时平移车辆站序、站点请求失败不兜底 | commonTest（JVM + iOS 模拟器） |
|  | `RealtimeRepositoryLineDetailCacheTest` | 线路详情同 key 并发只请求一次上游、TTL 内复用与过期重拉、目标站顺序不同算不同 key、失败不缓存、取消的调用方不影响其他等待者 | commonTest（JVM + iOS 模拟器） |
|  | `LocationRepositoryTest` | 首页取定位：够新够准直接用、强制刷新 10 秒 / 平时 60 秒、4.5 秒窗口取最准且 ≤100 m 提前结束、超过 10 分钟的旧定位不用、无权限不定位；规划页 lastKnown 取 10 分钟内最准的 | commonTest（JVM + iOS 模拟器） |
|  | `CountyLevelCityMatchTest` | 县级市/县城匹配与省份校验 | commonTest（JVM + iOS 模拟器） |
| `:core:data` `route` | `RoutePlanRepositoryTest` | `DefaultRoutePlanRepository` 缓存（第 17 步）：同城、同坐标、同一 15 分钟时间桶的算路命中缓存，跨时间桶重新算；输入提示按城市 + 关键字缓存，空结果不缓存 | commonTest（JVM + iOS 模拟器） |
| `:core:data` `repository` | `FavoritesRepositoryTest` | 收藏合一（第 14 步）在 Room 内存库上：车和路线合并排序（新车在最前、新路线在最后）、拖动排序（跨类型、只给几条）、改分组、删分组后车和路线都变不分组、旧数据迁入后顺序和分组不变、启动对账（删孤儿行、补缺的）、默认分组（只在从没建过分组时写，删光后重开不再补回来）、清理假数据、并发写不丢 | Robolectric（只 JVM） |
| `:core:data` | `TrackrDatabaseMigrationTest` | 库 1 -> 2：按 `schemas/.../1.json` 手工建版本 1 的库再用正式 builder 打开，`favorite_item` 按原顺序生成（车在前、路线按 sortOrder），分组按收藏页原来的规则补，旧表一行不变，仓库读出的顺序和分组和升级前一样；空库、重开 | Robolectric（只 JVM） |
| `:core:data` `legacy` | `LegacyLibraryMigrationTest` | 旧 SharedPreferences -> Room / DataStore：每个旧 key 一条（收藏、搜索历史、路线、收藏分组、收藏合一索引 `favorite_items_json`、提醒 + 计划触发时刻、6 个设置（聚合优先级不导入）、两个 Key 槽，含 0a4fdab 之前存在 `trackr_prefs` 的 Key 与「Key 文件优先」）；旧文件前后一字不差；损坏 JSON 不崩、只跳过那一项、原文复制到 `legacy_backup/`；已有 `_corrupt_backup_` 原样复制；同一代只跑一次、换代只替换旧文件里有的项；库重建后重新导入 | Robolectric（只 JVM） |
|  | `SettingsMigrationTest` | 设置和 Key 的只读 DataMigration：按类型搬、旧的聚合配置（`engine_mode = ALL` 或没存 + 优先级）落成主数据源（D1）、类型不对跳过、旧 key 不删、同一代不重跑、再导只覆盖旧文件里有的 key、空的旧 Key 不清掉新 Key、Key 不进设置文件 | Robolectric（只 JVM） |
| `:core:database` | `DaoTest` | 各 DAO 在内存库上：收藏合一的组合写入（只换给了的部分、按行改索引、改分组和排序）、整份替换保序、路线连程和候选线读回、删路线不留孤儿行、路线和分组一个事务、提醒改列表时保留计划触发时刻、搜索历史、站表缓存过期清理、迁移标记 | commonTest（Robolectric + iOS 模拟器） |
| `:core:datastore` | `TrackrDataStoresTest` | 设置文件读写重开、文件损坏换成空内容不崩、Key 和设置分两个文件、追踪会话（含路线目标、一起等目标和待选）JSON 读写清空、第 12 步写的会话（没有一起等字段）读回给默认值、会话文件损坏读成没有会话 | androidHostTest（只 JVM） |
| `:core:testing` | `FakesTest` | `FakeClock` 墙钟和单调时钟一起走、改墙钟不动单调时钟；`FakeReminderAlarms`、`FakeTransitCodeLauncher`、`FakeLocationSource` 的行为 | commonTest（JVM + iOS 模拟器） |

---

## 4. 常用测试执行命令

### 运行全量测试

```bash
./gradlew test
```

### 运行特定 ViewModel 测试

```bash
./gradlew :feature:line:testAndroidHostTest --tests "com.trackr.ui.screens.linedetail.LineDetailViewModelTest"
./gradlew :feature:bus:testAndroidHostTest --tests "com.trackr.feature.bus.home.BusHomeViewModelTest"
```

### 运行 Compose UI 测试

```bash
./gradlew :feature:line:testAndroidHostTest --tests "com.trackr.ui.components.ExpressiveRouteRailUiTest"
./gradlew :androidApp:testDebugUnitTest --tests "com.trackr.TrackrAppNavigationTest"
```

### 功能模块的 iOS 检查

```bash
./gradlew :feature:bus:compileKotlinIosSimulatorArm64
./gradlew :feature:bus:linkDebugTestIosSimulatorArm64
```

### 生成 JaCoCo 代码覆盖率报告

```bash
./gradlew jacocoTestReport
```

报告输出路径：
- JaCoCo HTML：`androidApp/build/reports/jacoco/jacocoTestReport/html/index.html`
- 单元测试 HTML：`androidApp/build/reports/tests/testDebugUnitTest/index.html`
