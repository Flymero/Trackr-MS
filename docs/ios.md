# iOS 启动工程

`iosApp/Trackr.xcodeproj` 是薄 UIKit 宿主，根控制器来自 `composeApp` 的 `MainViewController()`；应用主体直接使用已有 `TrackrRoot()` 和各 feature 的 CMP 页面，没有另一套 iOS 界面。

完整适配范围、阶段状态和后续验收要求见 [iOS 完整适配路线图](ios-roadmap.md)。本页保留构建步骤与已有运行记录。

## 在模拟器运行

需要 macOS、完整 Xcode（含 iOS Simulator runtime）、JDK，以及项目现有的 Gradle / Android SDK 环境。本工程提供 `iosArm64` 和 `iosSimulatorArm64`，模拟器使用 Apple Silicon。

1. 先执行 `pod install --project-directory=iosApp`，再用 Xcode 打开 `iosApp/Trackr.xcworkspace`。
2. 选择共享的 **Trackr** scheme 和一台 iPhone 模拟器，点击 Run。
3. 首次构建会通过 Run Script 执行 `:composeApp:embedAndSignAppleFrameworkForXcode`，编译共享代码并复制 Compose 资源。无需手动导入 framework。
4. 首次打开会申请使用期间定位权限。可以拒绝并手动选城市、搜索线路；模拟器附近站查询需要设置模拟坐标，例如执行 `xcrun simctl location booted set 31.2304,121.4737`（上海）。新版 Xcode 可在 Device Hub 中查看模拟器。

命令行构建（不需要开发者签名）：

```sh
xcodebuild -workspace iosApp/Trackr.xcworkspace -scheme Trackr \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath iosApp/DerivedData CODE_SIGNING_ALLOWED=NO build
```

安装到已经启动的模拟器：

```sh
xcrun simctl install booted iosApp/DerivedData/Build/Products/Debug-iphonesimulator/Trackr.app
xcrun simctl launch booted com.github.u5151561.trackr
```

真机需在 Xcode 的 Signing & Capabilities 里设置自己的 Team；不要把个人签名配置提交到仓库。当前 deployment target 为 iOS 17.0，低版本兼容性需要另行真机验证。

## 打包 IPA

Release 配置的 framework 链接（`:composeApp:linkReleaseFrameworkIosArm64`）跑在 Gradle daemon 里，`org.gradle.jvmargs` 低于 6 GB 会直接 `OutOfMemoryError: Java heap space`（Debug 链接的内存峰值低于 Release，所以只在打包时暴露）。`gradle.properties` 已按 6 GB 配置。

```sh
xcodebuild -workspace iosApp/Trackr.xcworkspace -scheme Trackr -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath iosApp/DerivedData CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build

rm -rf /tmp/trackr-ipa && mkdir -p /tmp/trackr-ipa/Payload
ditto iosApp/DerivedData/Build/Products/Release-iphoneos/Trackr.app /tmp/trackr-ipa/Payload/Trackr.app
(cd /tmp/trackr-ipa && zip -qry trackr-<版本>-unsigned.ipa Payload)
```

本机只装了一个 Apple Development 证书和免费个人 Team（`U6U8YSR37T`），没有发行证书和描述文件，因此只能出**未签名** IPA：安装前要用 AltStore / Sideloader 之类重新签名，重签名时主 App 与 `TrackrWidgets` 必须给同一个 App Group，否则小组件和实时活动不可用。要出签名包，得换成付费账号，在 Xcode 里登录并生成 distribution / ad hoc profile，再走 `archive` + `-exportArchive`。

发布资产命名沿用 Android 的规则：`trackr-<版本>-unsigned.ipa`。

## 本轮接通的能力

- 共享公交首页、城市选择、搜索、站点和线路详情、收藏管理、我的与主题设置。
- Ktor Darwin 直连已有公交接口；沿用现有 ViewModel、业务规则和页面轮询。
- Core Location 的前台定位、原生权限申请、跳系统设置，以及与共享层一致的单调时钟。
- Room 保存收藏、路线、搜索历史和站表缓存；DataStore 保存设置与高德 Key。
- `trackr://` 链接由共享 `DeepLinkParser` 校验后交给根导航，支持冷启动和运行中打开。
- 路线页复用共享 CMP 页面。配置高德 Web Key 并同意隐私说明后可使用 Web 算路；未配置时遵循现有规则隐藏路线页签。真机接入 CocoaPods 高德地图 SDK；官方包没有 arm64 模拟器切片，Apple Silicon 模拟器仅使用 Web 规划。

首次没有收藏、定位尚未授权时显示现有空态，不注入演示数据。搜索历史、收藏、城市和主题在重启后保留。

## 平台能力

- `TrackingPoller` 供 Android Service 与 iOS 前台控制器共用；进入非活动状态停止周期查询和定位、保留会话，恢复先判断有效期。不会尝试在后台持续查车。
- `IosNotifications` 负责到站通知和本地定时提醒。首次主动追踪/保存提醒时申请通知授权；提醒到点只通知，打开 App 后由用户开始追踪。一次性提醒记录绝对截止时间，重复提醒使用本地日历，前台恢复/系统时间变化时协调。
- `AVSpeechSynthesizer` 朗读，`SFSpeechRecognizer` 语音搜索（授权失败返回现有提示），支付宝/云闪付 Scheme 跳转。模拟器不能代表实际麦克风、中文声音与第三方 App 的表现。
- `TrackrWidgets` 提供 ActivityKit 实时活动与收藏小组件。App 原子写入 App Group JSON，扩展不打开 Room。暂停、90 秒过期和时间倒退均明确降级；刷新失败不会把旧 ETA 变成“刚刚更新”。

## 本机 Key 与签名

将 `iosApp/Configuration/Local.xcconfig.example` 复制为 `Local.xcconfig`，按需填写 `AMAP_WEB_KEY`、`AMAP_IOS_KEY`、`DEVELOPMENT_TEAM`、`TRACKR_APP_GROUP`。该文件已被 Git 忽略；不要提交个人 Key、Team 或 provisioning profile。

三个 Key 槽互不回落：Web Key 用于地点提示和规划；iOS Key 只给 iOS 地图；Android Key 不用于 iOS。高德 iOS Key 要绑定宿主 Bundle ID。只有 Web Key 时可规划；没有 iOS SDK 的模拟器不显示地图。授权撤回入口位于 iOS 高德 Key 设置页。

真机需同时为 `Trackr` 和 `TrackrWidgets` 配置同一开发团队，并在开发者账户中给两个 Bundle ID 启用相同 App Group。更换 Bundle ID 时同时更新扩展 ID、高德绑定和 App Group。`CODE_SIGNING_ALLOWED=NO` 只验证构建，不能作为真机安装或 App Group 授权通过记录。

高德 `11.2.100` 的官方 Pod 提供 arm64 真机和 x86_64 模拟器切片，显式排除了 arm64 模拟器。项目保留真机 SDK 分支，arm64 模拟器不链接该二进制；没有伪造地图成功状态。地图实测需要带 iOS Key 的 iPhone。

## 装配和存储

- `composeApp/src/iosMain/kotlin/com/trackr/app/di/IosAppRuntime.kt` 初始化本地数据后提供 Koin 平台模块，再安装 `trackrModules`。
- `MainViewController.kt` 在装配期间展示加载状态，初始化失败可重试。
- `Application Support/trackr.db` 与 `Application Support/datastore/trackr_settings.preferences_pb` 是普通应用数据。
- Key 与追踪文件位于 `Application Support/private/`，整个目录设置不参与 iCloud 备份，避免原子写入替换文件后丢失排除标记。
- iOS 不读取 Android 的 SharedPreferences，也不执行 Android 旧数据导入。

## 原生底部页签与安全区

`AppTabBar` 由 Koin 的 `platformUiModule` 注入：Android 使用现有 Material `NavigationBar`，iOS 使用 UIKit `UITabBar`（SF Symbols、原生选中态和 VoiceOver）。页面栈、深链、路线页签的 Key/隐私条件仍由共享 `Navigator` 管理；原生控件只回报点击，不能绕过共享状态自行切页。

iOS 底栏固定显示在标准版根页，子页、长辈版和键盘打开时隐藏。没有手绘仿 iOS 按钮，也没有另建 UIKit 页面栈。UIKit 通过 `sizeThatFits` 提供内容高度，系统底部安全区另外计入一次；共享页面通过 `LocalBottomBarHeight` 让开实际底栏。公交列表、乘车码按钮和提示条、路线面板以及「我的」列表和提示条都使用该值。状态栏、刘海和详情页的底部操作仍使用现有 `WindowInsets`。

组件选择依据：设计系统没有原生页签宿主；Material 底栏无法提供 UIKit 控件语义。因此仅在 iOS 平台实现中使用 `UIKitView` 托管 `UITabBar`，尺寸子类只报告布局结果，不重写按钮布局、点击或无障碍。使用 CMP 的原生 overlay 放置，让系统玻璃材质采样共享页面，安全区透出页面底色，避免深色模式的白色切口（[JetBrains interop overlay 说明](https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html#overlay-placement-for-interop-views)）。安全区依据 [Apple UIView.safeAreaInsets](https://developer.apple.com/documentation/uikit/uiview/safeareainsets)。

回归入口：`BusHomeScreenTest.transitCodeFab_tracksPlatformBarHeight_withoutFixedMaterialClearance` 检查动态高度与悬浮按钮的位置关系；`KoinGraphVerifyTest` 检查平台模块装配；`StartupTests.testNativeTabBarSelectionDetailsAndRotation` 检查原生页签切换、子页隐藏与返回、横屏布局。

本次验证（2026-11-08）：公交、路线、我的、根导航，以及 Android 根导航/Koin 装配共 291 个 JVM 用例通过；iOS 模拟器共享代码编译和 Xcode 宿主构建通过。iOS 27 模拟器使用 `--offline-ui-test` 人工确认页签切换、进入详情页隐藏底栏、返回恢复、横竖屏布局和深色底部白块修复。XCUITest 因 `DTServiceHubClient failed to bless service hub` 无法开始执行，未记为通过；弹层层级、VoiceOver、真机和 iOS 17 仍待验证。

## 验证

```sh
./gradlew test
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64 :feature:line:linkDebugTestIosSimulatorArm64
./gradlew :core:data:iosSimulatorArm64Test
```

手动检查：启动 → 城市 / 搜索 → 线路详情 → 收藏 → 返回首页 → 重启检查收藏；我的 → 主题设置 → 明暗切换；拒绝 / 授予定位后检查附近站；未接入功能应显示明确反馈。

### 基础工程历史验证（2026-11-08，本轮平台适配之前）

- Xcode 27.0 / iOS 27.0 / iPhone 17 模拟器：构建、安装、冷启动通过。
- JVM 回归共 1400 个用例通过；iOS 数据层 104 个用例通过，线路模块 iOS 测试链接通过。所有自动化网络测试使用 Fake / MockEngine。
- 手动使用真实上海 71 路验证搜索、线路详情、选站、收藏、返回首页；打开附近站点详情并返回正常。
- 关闭进程再启动后，收藏、搜索历史和深色主题保留；查询与附近站数据显示正常。
- 追踪按钮显示尚未接入提示，不创建虚假的活动会话。

目前依赖中的 `libicu.icudtl_dat.o` 会提示其构建版本为 iOS Simulator 18.5，高于工程设置的 17.0。iOS 17 的实际兼容性尚未验证，发布前需要补齐对应系统的运行检查。当前运行验证以 iOS 27.0 为准。


## 平台适配验收命令

```sh
./gradlew test
./gradlew :core:data:iosSimulatorArm64Test :core:domain:iosSimulatorArm64Test :core:datastore:iosSimulatorArm64Test
./gradlew :composeApp:linkDebugTestIosSimulatorArm64 :feature:search:linkDebugTestIosSimulatorArm64
xcodebuild -workspace iosApp/Trackr.xcworkspace -scheme Trackr \
  -destination 'platform=iOS Simulator,id=<本机模拟器 UDID>' \
  -derivedDataPath iosApp/DerivedData -parallel-testing-enabled NO CODE_SIGN_IDENTITY=- test
xcodebuild -workspace iosApp/Trackr.xcworkspace -scheme Trackr \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath iosApp/DerivedData CODE_SIGNING_ALLOWED=NO build
```

XCUITest 传入 `--offline-ui-test`，HTTP 发送被拦截，不访问真实接口。`--fail-startup-once` 在存储和平台资源打开后注入一次失败，用于验证错误反馈、释放和重试。普通启动不启用这些测试参数。

## 本轮平台适配验证（2026-11-08）

验证对象为本轮平台适配代码。环境为 Xcode 27 beta、iOS 27 模拟器（Trackr iPhone / iPhone 17），没有可用 iPhone 或 iOS 17 runtime，也没有配置高德 Key。

- `./gradlew test --max-workers=2` 通过：1413 个 Android/JVM 用例，无失败；包含 Android 追踪服务回归。全平台并行验证曾发生 Gradle daemon 退出，拆开平台并降低并发后通过。
- `:core:data:iosSimulatorArm64Test` 117 个、`:core:domain:iosSimulatorArm64Test` 146 个、`:core:datastore:iosSimulatorArm64Test` 1 个用例通过。覆盖前后台暂停/恢复、取消后迟到结果、过期与退避、提醒持久化/权限拒绝/并发上限、三槽隔离，以及备份目录的原子替换与重开。
- `:feature:search:linkDebugTestIosSimulatorArm64`、`:composeApp:linkDebugTestIosSimulatorArm64` 通过。后续设置文案修改另跑 `:feature:me:testAndroidHostTest` 回归。
- 模拟器宿主、WidgetKit 扩展、XCTest 和 XCUITest 测试包构建通过；真机 SDK 分支的无签名构建通过。构建结果不代表原生测试已执行或真机安装通过。
- `xcodebuild test` 在无签名及本地临时签名两种设置下均遇到 `DTServiceHubClient failed to bless service hub for simulator` / `com.apple.instruments.deviceservice.lockdown` 连接错误，未取得 XCTest / XCUITest 执行结果。
- 人工模拟器验证：最新宿主传入 `--offline-ui-test --fail-startup-once`，显示初始化失败；点击重试恢复首页并读回收藏及主题。此前也验证了长辈版/标准版切换回对应首页。离线注入不代表真实公交端到端验收。
- 人工检查发现并修正 iOS 高德页残留 Android 的 SHA-1 说明；iOS 显示 Bundle ID 和独立的 iOS/Web 槽。没有填入或发送个人 Key。

本轮没有把地图实测、通知实际投递、锁屏/灵动岛/小组件展示、实际语音识别或 iOS 17 兼容性记为通过。完整剩余项见路线图及下方检查表。

桌面小组件已按用户要求暂缓；现有代码保留为未验收草稿。模拟器 App Group 容器已创建，但快照文件尚未确认成功写入，后续恢复此项时先排查数据传递与写入。

## 待设备验收检查表

- [ ] iOS 17 实际安装运行；确认 `libicu.icudtl_dat.o` 的 18.5 构建版本警告对应的兼容性。未通过前不可声称已支持全部 iOS 17 设备。
- [ ] 配置 Web/iOS Key 后验证地点提示、规划、保存与编辑路线、原生地图折线/站点/当前位置；切 Key、撤回授权及返回页面释放。
- [ ] 单线、一起等、多路线追踪：上车待选、换乘、停止、重复开始、离线恢复、进后台和进程重建。
- [ ] 本地通知授权拒绝/撤回、声音、定时投递、重复日、跨时区、已过期一次性提醒、通知跳转。
- [ ] 真机中文朗读、语音识别、定位精度/模糊定位/系统关闭、支付宝与云闪付跳转。
- [ ] 签名 App Group 后验证收藏快照、锁屏、灵动岛、小组件、暂停/过期/结束、删除收藏后的旧入口。
- [ ] 标准版与长辈版的 Dynamic Type、VoiceOver、深浅色、横竖屏、安全区、键盘、返回手势和模式切换。
- [ ] 前后台及锁屏功耗。没有 iPhone 时此项不得勾选。
