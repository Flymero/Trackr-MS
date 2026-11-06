# 设计稿 v3 代码改造步骤
> **已被 `docs/design/refactor_plan_mad.md`（r2）取代**：本文只管界面改版，执行以 r2 计划为准。

> 依据：`docs/design/redesign_v3.html`、`docs/spec/app_spec_as_is.md`（§7 问题、§8 决策）。
> 规则：**一步一个提交**，每步结束 `./gradlew test` 通过、能装机运行。步骤按依赖排序，前面的不依赖后面的。
> 内存紧张时只跑本步相关测试，最后一步再跑全量。

---

## 第 0 步：开工前

- 工作区现在有别人未提交的改动（`ChelaileApiClient.kt`、`TransitProviderCoordinator.kt` 等 4 个文件）。先提交或暂存，否则第 3 步会和它冲突。
- 提交三份文档：`app_spec_as_is.md`、`redesign_v2.html`（备用）、`redesign_v3.html`、本文件。

---

## A. 地基（小、互不依赖）

### 1. 主题与 Token
- `core/designsystem/theme/Theme.kt`
  - 静态色换成 v3 蓝（`#1F5FD6`）方案，浅色 / 深色各一套。
  - `TrackrTheme(dynamicColor = false)`，删掉壁纸取色分支。
  - `BusSemanticColors` + `ExtendedColors` 合成一个 `TrackrColors`：`go / goContainer / warn / warnContainer / walk`。旧字段名先保留为别名，指向新色，第 17 步再删。
- `Type.kt`：加 `eta`（40sp Bold 等宽数字）、`item`（15sp SemiBold），其余用 M3 默认。
- 新增 `Shape.kt`：small 8 / medium 16 / extraLarge 28。
- 验收：现有测试通过；截图对比颜色已变。

### 2. 到站文字统一（纯逻辑）
- 新增 `domain/arrival/ArrivalText.kt`：输入线路状态，输出 `大字 / 小字 / 色调(go|normal|mute|warn)`，覆盖 v3 的 8 种状态。
- 替换调用处：`BusArrivalBadge`、`StationArrivalContent`、`TransitGroupCardText`、`LiveProgressNotificationHelper`、`BusStopWidgetProvider`。
- 测试：新增 `ArrivalTextTest`（8 种状态 + 边界：0 站、≤3 分钟、末班刚过）；改 `BusArrivalBadgeTest`、`StationArrivalContentTest`、`LiveProgressNotificationHelperTest` 的期望文字。

### 3. D1 去掉聚合
- 删 `AggregatedSourcePriority`、`SearchResultAggregator`、`TransitProviderCoordinator` 里的聚合分支。
- `ITransitRepository` 删 `aggregatedPriority` / `setAggregatedPriority`；`EngineMode` 只剩两个单源。
- 迁移：读到旧的聚合配置，按其主数据源落成单源。
- 改 `MoreScreen` 数据源控件为二选一；删搜索页「双引擎」说明卡。
- 测试：`TransitProviderCoordinatorTest`、`TransitProviderTest`、`MoreViewModelTest`、`FakeTransitRepository`。

### 4. D2 没 Key 不显示规划，去掉示意图
- 删 `RoutePlanVectorMapCanvas`、`drawMarkerBadge`、`drawTransferBadge`、`MapUnavailableNotice`、搜索岛下「配置Key」胶囊。
- `AmapKeyStore` 暴露 `hasMapKey: StateFlow<Boolean>`。
- `MainActivity`：底栏页签由 `hasMapKey` 决定；Key 被清空时若停在规划页，切回首页。
- `AmapApiKeyConfigDialog`、`MapDiagnosticsDialog` 移到 `ui/components/amap/`，入口挪到「更多」页（第 15 步再并入新「我的」页）。保留 `MapLoadTimeoutNotice`（Key 填错时用）。
- 测试：`RoutePlanScreenTest` 删示意图用例；`NavigationFlowTest` 加「有 / 无 Key 时页签数」。

### 5. D3 锁屏
- `LiveProgressNotificationHelper`：设置 `VISIBILITY_PUBLIC`；关闭开关时用 `VISIBILITY_PRIVATE` + `setPublicVersion`（只显示「追客 · 追踪中」）。
- `ITransitRepository` 加 `showOnLockScreen` 开关，默认开。
- 测试：`LiveProgressNotificationHelperTest` 加两种可见性。

---

## B. 数据与追踪服务

### 6. 收藏合一
- `FavoriteStationLine` 加 `groupId: String? = null`、`sortOrder: Int = 0`（有默认值，旧数据能直接读）。
- 新增 `domain/model/FavoriteItem.kt`：`sealed interface FavoriteItem { Stop(FavoriteStationLine) ; Route(TransitGroup) }`。
- 新增 `FavoritesRepository`（或在 `RouteGroupRepository` 里加）：合并两类的 `favoriteItems: StateFlow<List<FavoriteItem>>`、`moveItem`、`setItemGroup`。
- `GroupCategory` 不再给用户选；字段保留，`TEMPORARY` 继续表示临时路线。
- 测试：`RouteGroupRepositoryTest` 加合并排序、改分组、删分组后变「不分组」。

### 7. 一起等多条线（服务）
- `TrackingSessionState` 加 `watchedLines: List<WatchedLine>`（线路、方向、上车站序、当前最近车、ETA、站数），加 `boardChoice: List<String>?`（两辆同时在站时待选）。
- `BusTrackingService`：等车阶段只请求 1 次上车站的站点接口（带该站每条线的来车），条数不设上限，按 ETA 排序；主显示取第一条。
- 启动参数：Intent extras 改成传 `groupId`，服务从仓库读路线，不再塞十几个 extra。
- 站点页「一起等」= 建一个只有 1 程、`isTemporary = true` 的路线，复用路线追踪；没有下车站时，上车后发「已上 325」通知并结束追踪。
- `GroupLegTracker.processWaitingBoard` 改成多线；「我上车了」= 取此刻在上车站的那辆；两辆都在站时写 `boardChoice`。
- `TransferAutoSwitchManager.resolveLineForLeg` → `resolveLinesForLeg`，返回这一程勾上的全部线。
- 通知动作：「我上车了 / 乘车码 / 结束」；`boardChoice` 非空时点「我上车了」打开应用弹「上了哪辆？」。
- 测试（`GroupLegTrackerTest`、`TransferAutoSwitchManagerTest`）：多线取最快、先后顺序变化、只有一辆在站、两辆在站、某条线没数据、只有 1 程无下车站。

---

## C. 导航骨架

### 8. 页签与新页面 Key
- `ScreenKey`：删 `FavoritesGroup` 页签；新增 `RouteDetail(groupId)`、`FavoritesEdit`；`More` 改名 `Me`。
- `MainActivity`：页签改为 公交 / 路线 / 我的；`selectedTab` 改用枚举。
- 拆 `FavoritesGroupViewModel`（1530 行）：
  - `RoutePlanViewModel`：规划、方案、收藏到分组（`RoutePlanScreen` 现在借用收藏页的 VM，改为自己的）。
  - `RouteDetailViewModel`：一条路线的实时、勾线、追踪。
  - `FavoritesEditViewModel`：分组和收藏的增删改排序。
- 这一步只搭空页面和跳转，旧收藏页暂时从首页「编辑」进入，保证功能不断。
- 测试：`NavigationFlowTest`、`NavigationKeyTest`；把 `FavoritesGroupViewModelTest` 按新 VM 拆开。

---

## D. 页面（每页一个提交）

| 步 | 页面 | 主要改动 | 测试 |
| --- | --- | --- | --- |
| 9 | 公交首页 | 标题改城市名；删乘车码卡；新增 `ui/components/TransitCodeFab.kt`（往下滑缩小、往上滑展开，点开默认平台，「每次询问」才弹 Sheet）；收藏列表合一 + 分组 chips + 「编辑」；追踪卡支持一起等 | `HomeViewModelTest`、新增 `TransitCodeFabTest` |
| 10 | 线路详情 | 删 `BottomFloatingToolbar`；`TopLineHeader` 加「换向」和「时刻表 ›」；顶栏时钟 + ☆；☆ 一点即收藏 + Snackbar「换分组」；主卡用 `ArrivalText`；删预警 chips；走向条只留竖向（删 `RouteRailLayoutMode.Horizontal`）；底栏 = 主按钮 + 乘车码。**后续调整**：换向是高频动作，从方向行挪到底栏（主按钮右侧 52dp 圆钮，单程线不占位，换向中转圈禁用），方向行整行留给站名；换向时若正在追踪先自动结束并提示 | `LineDetailViewModelTest`、`StationArrivalContentTest`、`ExpressiveRouteRailUiTest` |
| 11 | 站点详情 | 站台 chips 按方向命名（没有方向数据时退回「站台 1 / 2」）；删「查看全线」；刷新改下拉；选线模式（勾选、条数不限、收藏 / 一起等）；一起等进行中视图；「上了哪辆？」Sheet | `StopDetailViewModelTest` |
| 12 | 路线详情 + 编辑收藏 + 收藏 Sheet | 新页面按 v3 稿；时间轴复用 `LegTimelineItem` 的部分绘制；编辑页拖动排序；收藏 Sheet 只问名字和分组 | 新增 `RouteDetailViewModelTest`、`FavoritesEditViewModelTest` |
| 13 | 搜索 | 删写死推荐词、双引擎卡；线路结果每方向一行直达 | 新增或补 `SearchScreen` 用例 |
| 14 | 规划 | 方案标签固定 最快 / 少换乘 / 少走路；收藏后不自动跳页，Snackbar「去看看」；底栏加乘车码 | `RoutePlanScreenTest` |
| 15 | 我的 | 按 v3 分组重排；删附近站数步进器、测试打开按钮；新增「到站前提醒」全局设置（替代线路页 chips）；定时提醒列表可点开编辑（`ScheduledReminderDialog` 支持传入已有值）；锁屏开关；高德 Key | `MoreViewModelTest`、`ScheduledReminderRepositoryTest` |

---

## E. 收尾

### 16. 文案
- 全局删 emoji 与营销语（`⚙️`、`💡`、`🟢`、`🚩`、`⚡`、「一键秒开」「银联优惠直达」）。
- 按 v3「统一叫法」表替换：路线组 / 同达方案 / 换乘组合 → 路线；定时通勤 → 定时提醒；跟车 → 追踪。
- 检查：`rg -n '⚙|💡|🟢|🚩|⚡|路线组|同达|换乘组合|定时通勤|跟车' androidApp/src/main` 无结果（第 2 步起 `app/` 改名为 `androidApp/`）。

### 17. 清理
- 删旧文件：`FavoritesGroupScreen.kt`、`TransitGroupCard.kt`、`TransitGroupCardText.kt`、`GroupEditDialog.kt`、`ManageFavoriteGroupsBottomSheet.kt`、`BottomFloatingToolbar.kt`、`TransitCodeQuickCard` 等已无引用的组件和对应测试。
- 删第 1 步留下的旧颜色别名。
- 页面内写死的 `fontSize = n.sp`、`RoundedCornerShape(n.dp)`、`Color(0x…)` 清零（`core/designsystem` 除外）。
- 小组件 `widget_bus_stop.xml` 换新颜色。
- 跑全量 `./gradlew test` 和 `./gradlew jacocoTestReport`。

---

## 风险

| 风险 | 应对 |
| --- | --- |
| 旧数据读不出 | 新字段都带默认值；第 3、6 步各加一个「读旧 JSON」测试 |
| 一起等多拉几条线，流量和电量变多 | 同站只发 1 次请求，条数不影响请求数；沿用 `calculateAdaptivePollingInterval` 按距离调频 |
| 第 8 步拆 VM 范围大 | 先搭骨架、旧页面保留可用，后面逐页替换 |
| 方向数据不一定有 | 站台 chips 退回「站台 1 / 2」 |
