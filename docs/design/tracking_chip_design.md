# 低版本实时状态可见性：通知卡 + 追踪小条

2026-11-05 · 设计稿，先不改代码 · 对应 [issue #21](https://github.com/5151561/Trackr/issues/21)

> **2026-11-06 决定：暂不做。** 正文保留作为以后翻旧账的依据，不要照着实现。
>
> - 追踪小条（P1）的收益全在「边用别的 App 边瞥一眼」，代价是一次特殊权限引导、国产 ROM 的双开关静默失效、以及用户对悬浮窗的广告联想。对一个工具类小应用，这笔账不平。
> - 通知卡（P0）不是新能力，只是把已有通知排得好看些。`RemoteViews` 在国产 ROM 上被统一成系统样式或被裁切的概率不低，还要多养一套 XML 和一张真机测试矩阵。
> - issue 的原始诉求是「Android 10 也要有灵动岛」，而那个东西的系统实现只存在于 Android 16，任何自建方案都是仿制品。
> - 唯一还值得捡的是 §7 那行通知健康检查（2 小时、零风险、解决「我开了但没显示」），它不依赖本设计，可以单独做。

## 1. 结论

Issue 提的是「加悬浮窗」。需求是真的，但**悬浮窗不是第一步，也不该是默认**。

按性价比排，做三件事：

| | 做什么 | 成本 | 解决什么 |
| --- | --- | --- | --- |
| **P0（必做）** | 把 Android 8–15 的常驻通知换成**自定义卡片**（线路徽标 + 剩几站 + 站点进度点列 + 按钮） | 半天 | 下拉就能看清，不再是一行字 |
| **P0.5（必做）** | 通知「看不到」的排查入口：渠道被降级 / 权限被关时，「我的」那一行标红并一键跳系统设置 | 2 小时 | 最常见的「我开了但没显示」 |
| **P1（可选，默认关）** | 叫「**追踪小条**」的悬浮条：只在追踪中 + App 不在前台时贴边显示 | 1.5–2 天 | 「边刷微信边看还剩几站」——只有它做得到 |

不做：气泡、画中画、无障碍悬浮、上岛样式复刻。

判断依据一句话：**P0 用零权限拿到 80% 的体验，P1 用一次权限引导换剩下 20%，而且必须让用户自己选。**

## 2. 低版本用户现在看到的是什么

先确认现状，不然容易把「已经有的」当成「要做的」。

Android 10 用户追踪时，拿到的是 `LiveProgressNotifier.buildNotification` 建的一条通知：

| 项 | 现在 |
| --- | --- |
| 渠道 | `bus_live_progress`，`IMPORTANCE_LOW`（不响、不震、不横幅） |
| 标题 | `B612 还有 3 站到 科技园` |
| 正文 | `约 6 分钟 · 大冲 → 科技园` |
| 样式 | `ProgressStyle`（`setProgress` 的进度条 + 最多 4 个站点标点） |
| 锁屏 | 默认 `VISIBILITY_PUBLIC`，全内容可见 |
| 按钮 | 我已上车 / 提前 N 站提醒 / 结束 |
| 胶囊 | `SDK_INT < 36` 直接不申请，`setRequestPromotedOngoing` 不调用 |

`ProgressStyle` 在 Android 16 之前**只退化成一个系统进度条**，站点标点、`shortCriticalText` 全丢。所以低版本用户看到的确实是「一行标题 + 一行正文 + 一根细线」，信息密度低，这就是 issue 想解决的。

好消息：`setProgress` 的进度条在所有版本都渲染，**它已经是一个可用的「距离可视化」**，问题是周围没有排版，挤在文字下面像装饰。P0 就是把它变成主体。

## 3. 六条路，一张表

| 方案 | 需要权限 | 何时可见 | 锁屏 | 开发量 | 主要风险 |
| --- | --- | --- | --- | --- | --- |
| **真悬浮窗**（`TYPE_APPLICATION_OVERLAY`） | 悬浮窗（要去系统设置开） | 任何界面之上，追踪中常驻 | ✗ 默认不上锁屏 | 中 | 权限转化率、国产 ROM 后台限制、商店审核、信任成本 |
| **自定义通知卡**（`RemoteViews`） | 无 | 通知栏 / 锁屏 / 下拉 | ✓ | 小 | 高度受限、深浅色要各验一遍；**不能与上岛共存**（见 §5.1） |
| **横幅**（HERO/HIGH 渠道） | 无（通知权限） | 弹 3 秒，之后进通知栏 | ✓ | 很小 | 只在瞬间可见；已有「到站提醒」覆盖，重复做没意义 |
| **气泡**（`BubbleMetadata`，API 29+） | 气泡开关（渠道设置里） | 贴着屏幕边缘浮着 | 部分 | 中 | 国产 ROM 基本砍了这个功能，UX 也是给聊天用的 |
| **画中画**（PiP） | 无 | 离开 App 后浮着 | ✗ | 中 | 窗口比例受限、只有离开 App 才浮起来、国内 ROM 参差 |
| **桌面小组件**（已有 Glance） | 无 | 桌面 | ✗ | 小 | 只在桌面，和「边用别的 App」的场景错开 |

## 4. 为什么悬浮窗不该是默认

不是嫌麻烦，是这五条每条都实打实：

1. **权限拿不到，功能就是零。** `SYSTEM_ALERT_WINDOW` 不是运行时弹窗，要跳 `ACTION_MANAGE_OVERLAY_PERMISSION` 到一个纯系统列表里找开关。国产 ROM 这个开关还常藏在「权限管理 → 特殊权限」第二层，有的机型（部分华为 / vivo）直接没有这个入口。用户第一次看到「请去设置里允许显示在其他应用上层」，大概率直接退出。
2. **国产 ROM 还有第二道门。** MIUI 的「后台弹出界面」、部分 ColorOS / OriginOS 的「允许后台显示」，跟悬浮窗权限是两个开关。少开一个，代码不报错，窗口就是不出现——这种「静默失效」最难排查，也最容易被用户当成 bug。
3. **悬浮窗在国内用户心里是「广告」的同义词。** 抢红包、流氓清理、弹窗广告全都这么干。追客是个工具类小应用，为一个可选项背上这个印象不划算，评论区会先炸。
4. **它绕过了系统的通知体系。** 通知有免打扰、有渠道重要级别、有「历史记录」、有厂商统一的管理面板；悬浮条全都没有。用户想关掉它，只能进 App 找开关——这对「被悬浮窗骚扰过的人」是一种冒犯。
5. **它解决不了锁屏。** `TYPE_APPLICATION_OVERLAY` 默认不上 keyguard。而「车还有几站」最高频的查看时刻恰恰是锁屏掏手机那一下。锁屏能给的只有通知，现在的 `VISIBILITY_PUBLIC` 已经给了。

顺带一条：如果以后上 Google Play，`SYSTEM_ALERT_WINDOW` 属于要单独说明用途的敏感权限，审核周期和驳回率都会上去。现在靠 GitHub 发 APK 没这个问题，但这是个技术债，不该由 P0 承担。

## 5. 但要承认：只有它解决「边刷微信边看」

把话说全。用户真实场景是「在别的 App 里待着，想随时瞥一眼还剩几站」：

- 通知卡：要下拉，打断当前操作。
- 横幅：3 秒就没了，只能保证「不错过」，保证不了「随时看」。
- 桌面小组件：在桌面，不在微信里。

**这个场景确实只有悬浮窗能覆盖。** 所以结论不是「不做」，而是「做成可选、做成不打扰、做在通知已经足够好之后」——用户先被 P0 满足，只有真的需要常驻瞥一眼的人才会去开 P1。

### 5.1 一个必须写下来的硬约束

现有 `logPromotionState` 把 `customViews` 当成上岛条件之一在打日志，AOSP 的 promotable 判定确实排除了自定义视图。所以：

> **自定义布局只能给 `SDK_INT < 36` 用。** Android 16 及以上继续走 `ProgressStyle` + 胶囊，两条路互斥，不能一套通吃。

`buildNotification` 里加分支即可，`allowPromotion` 的调用方不用动。

## 6. P0：低版本通知卡

### 6.1 形态

折叠态（内容区约 64dp，各家 ROM 略有出入，只放两行字 + 一条线）：

```
┌────────────────────────────────────────┐
│ 🚌 追客 · 追踪中            23:12  ⌄   │ ← 系统装饰（DecoratedCustomViewStyle）
├────────────────────────────────────────┤
│ ┃B612┃  还有 3 站 · 约 6 分钟          │
│          大冲 → 科技园 · 粤B·58921      │
│  ●──────●──────◉──────○──────○         │
├────────────────────────────────────────┤
│ 我已上车    提前2站提醒    结束         │ ← 系统渲染的 action
└────────────────────────────────────────┘
```

展开态（`setCustomBigContentView`）多一行：下一站名、换乘提示、信号丢失提示。

### 6.2 内容从哪来

不新造文案。`LiveProgressNotifier.buildTexts()` 已经按阶段（等车 / 乘车 / 单线 / 一起等）算好 `title` / `content` / `subText`，卡片只是换排版：

| 卡片位置 | 取值 |
| --- | --- |
| 线路徽标 | `state.lineName`（长名截 6 字，超了退到线路号） |
| 主行 | `shortCriticalTextFor(state)` 的展开版：`还有 ${stopsRemaining} 站 · ${etaMinutesText}` |
| 次行 | `boardStopName → targetStopName`，有车牌补车牌 |
| 点列 | 复用 `calculateSpanAndMaxProgress` + `calculateCurrentProgress`，点亮比例和进度条一致 |
| 展开区 | `buildTexts` 的 `content`（换乘 / 信号丢失 / 下一程） |

**文案和通知的 `setContentTitle` 保持同时设置**：无障碍朗读、锁屏 public version、以及部分 ROM 回退到默认模板时都靠它。

### 6.3 技术约束

- `RemoteViews` 只吃 XML 布局，不能放 Compose。
- `setStyle(ProgressStyle)` 和自定义视图不能同时用 → `SDK_INT >= 36` 才走 `ProgressStyle`。
- 布局文件两个方向各一套意义不大，深浅色统一用系统属性取色：文字 `?android:attr/textColorPrimary` / `textColorSecondary`，强调色走 `NotificationCompat.Builder.setColor()` 与卡片内的线路色块一致。**浅色 / 深色通知栏各真机验一遍**（Robolectric 验不出来）。
- 按钮栏由 `DecoratedCustomViewStyle` 画，卡片里不自己放按钮，省得两套尺寸对不上。
- 点列用 5 个 `ImageView` 按进度切可见性，比在 `RemoteViews` 里画自定义 View 稳（`RemoteViews` 能调的 API 很少）。

### 6.4 刷新

不新增轮询。`BusTrackingService.render()` 本来就按内容去重（`worker.rendered`），内容变了才 notify。补充一条：进度点列的「点亮个数」是离散的（最多 5 档），比进度条百分比变化次数少，实际刷新频率还会降。

### 6.5 只在什么时候用

```kotlin
val useCustomLayout = Build.VERSION.SDK_INT < 36 && liveNotificationEnabledProvider()
```

`allowPromotion = false` 时（非焦点会话）也可以走自定义布局——不影响。
「实时通知」关掉时保持现状（只发最简通知），不画卡片：省得关不掉。

## 7. P0.5：「看不到」的排查入口

比悬浮窗更值得先做。现在「我的 → 通知」里只有开关，没有「为什么没显示」。

新增一行（`SettingRow`，和「状态栏胶囊」同一形态）：

| 情况 | 文案 | 点击 |
| --- | --- | --- |
| 正常 | `通知正常`（绿点） | 应用通知设置 |
| 通道被降级（`importance <= IMPORTANCE_MIN`）或通知权限被关 | `通知被关闭，可能看不到追踪`（`TrackrTheme.colors.warn`） | 直达该渠道设置 |
| `POST_NOTIFICATIONS` 未授予（Android 13+） | `没给通知权限` | 权限请求 or 应用通知设置 |

判定逻辑放 `LiveProgressNotifier.channelImportance` 旁边，扩成 `NotificationHealth`。这块纯粹是补救，但用户报「没通知」的概率比报「没悬浮窗」高得多。

## 8. P1：追踪小条

### 8.1 名字

**不叫「悬浮窗」**，叫「**追踪小条**」（英文 *Tracking chip*）。理由：权限页那一串字已经够吓人，设置项里再写「悬浮窗」等于劝退；而且它的形态本来也不是「窗」。

设置项文案：

```
追踪小条                                   开 / 关 / 未授权
在别的应用上也看得到剩几站，仅在追踪中出现
```

### 8.2 入口与权限

- 「我的 → 通知」组最后一行，`SettingRow`（不是 `SwitchRow`），因为要先过权限。
- 点「未授权」→ `Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:…")`；`runCatching` 失败退到 `ACTION_APPLICATION_DETAILS_SETTINGS`。
- 回前台复查（`LiveUpdateAccess.status()` 已有同样的模式，照抄）。
- 授权成功后**立刻给一次预览**：如果此刻正在追踪，直接把小条画出来并停 3 秒，用户当场知道它长什么样、在哪。没有预览的权限引导，用户开完不知道发生了什么。

### 8.3 什么时候显示

全部满足才画，任何一条不满足就撤：

| # | 条件 |
| --- | --- |
| 1 | `settings.showTrackingChip == true` |
| 2 | `Settings.canDrawOverlays(context)` |
| 3 | `BusTrackingService` 在前台且至少有一项活跃会话 |
| 4 | **App 不在前台**（`ProcessLifecycleOwner` 的 STARTED 计数为 0） |
| 5 | 屏幕亮着（`PowerManager.isInteractive`） |

第 4 条是关键：回到 App 里小条立刻消失，避免和页面里的追踪卡重复，也顺手省电。第 5 条息屏时窗口本来也不可见，主动撤掉省一层合成。

不满足 3 时**不保留**，直接 `removeView`——追踪结束小条必须消失，不能留一个点不动的残影（窗口属于进程，进程死了会自己消失，但服务还活着时就会一直挂着）。

### 8.4 形态

折叠（贴边，约 40dp 高）：

```
                        ┌───────────────────────┐
                        │ ▮B612   3站 · 6分  ▾ │
                        └───────────────────────┘
```

展开（点一下，约 96dp）：

```
        ┌──────────────────────────────────┐
        │ B612 → 科技园              已等 4分│
        │ ●────●────◉────○────○            │
        │ 还剩 3 站 · 约 6 分钟             │
        │ [我已上车]  [打开追客]         ×  │
        └──────────────────────────────────┘
```

### 8.5 窗口参数

| 项 | 取值 |
| --- | --- |
| type | `TYPE_APPLICATION_OVERLAY`（minSdk 26，不需要 `TYPE_PHONE` 兼容分支） |
| flags | `FLAG_NOT_FOCUSABLE`（能收点击，不吃键盘） |
| 格式 | `PixelFormat.TRANSLUCENT` |
| gravity | `TOP or START`，初始 y = 状态栏高 + 8dp，避开挖孔 |
| 宽高 | 折叠 `wrap_content × 40dp`，展开 `220dp × wrap_content`（长辈版按字体放大，上限 260dp） |
| 圆角 | 20dp，背景 `TrackrTheme` 的主色 + 阴影（`elevation` 在 overlay 里不生效，用 9-patch 或 `outlineProvider` + `ViewOutlineProvider`） |
| Context | `applicationContext` 取 `WindowManager`（overlay 允许非可视 context） |

**不用 Compose。** `WindowManager` 里挂 `ComposeView` 要自己造 `LifecycleOwner` / `SavedStateRegistryOwner`，容易泄漏，而内容就几行字——一个 XML 布局足够，还能和 P0 的卡片共用同一套视觉。

### 8.6 交互

| 手势 | 行为 |
| --- | --- |
| 短按（折叠） | 展开 |
| 短按（展开） | 无 / 点按钮 |
| 拖动 | 跟手，松手 12dp 内吸附左或右边缘，纵坐标记忆 |
| 长按 | 本次追踪不再显示（比「拖到 X 上关掉」省事），下次追踪恢复 |
| 拖到屏幕最上方 24dp 内松手 | 顺带也当隐藏（有手势习惯的人会这么试） |

边框加 8dp 的透明 padding 做「热区」，视觉上是 40dp，摸起来是 56dp。

### 8.7 多会话

现在能同时追多项、每条线一条通知，小条只有一块位置：

- 显示 `foregroundSessionId`（最新开始的那一项）。
- 活跃会话 > 1 时，折叠态右侧加 `+2` 小角标；展开态底部一行「还有 2 项追踪中 ›」，点击打开 App。
- 一起等（等车多条线）按第一条线的数据，和通知的 `specs.first()` 一致。

### 8.8 明确不覆盖

- **锁屏**：默认不显示，也不去 `FLAG_SHOW_WHEN_LOCKED` 硬顶（Android 10+ 限制多，且绕过锁屏隐私）。锁屏交给通知的 `VISIBILITY_PUBLIC`。
- **全屏应用**（视频、游戏）：不躲，用户自己长按隐藏。
- **勿扰**：不读 DND 状态，小条是「静默显示」，不算打扰。

### 8.9 失败降级

| 情况 | 表现 |
| --- | --- |
| 开关开、权限没给 | 静默不画，「我的」那行标红「未授权」 |
| 权限被系统回收（部分 ROM 会在后台回收） | `addView` 抛 `BadTokenException` / `WindowManager.BadTokenException` → catch，标记失效，下次追踪结束前不再重试 |
| 用户把 App 冻结 / ROM 杀了服务 | 进程死，窗口自动消失；不产生残留通知 |

## 9. 设置项改动清单

| 层 | 改动 |
| --- | --- |
| `core/data/…/Repositories.kt` | `SettingsRepository` 加 `showTrackingChip: StateFlow<Boolean>` + `setShowTrackingChip(enabled)`，默认 `false` |
| `core/data/…/local/NewStores.kt`、`LocalStores.kt`、`androidApp/…/local/SharedPrefsStores.kt` | 同一个键，三处（和 `showTransitCodeButton` 同构） |
| `core/testing` | `FakeSettingsRepository` 补字段 |
| `feature/me` `MeViewModel` / `MeScreen` | `MeUiState` 加两项：`showTrackingChip`、`trackingChipStatus`（`Unsupported` / `NotGranted` / `On` / `Off`），新增 `MeEvent.SetShowTrackingChip`、`MeEvent.OpenOverlayPermission` |
| `core/ui` | 新增 `TrackingChipAccess` 接口（`status()` / `openPermissionSettings()`），照 `LiveUpdateAccess` 的形态，iOS 不绑（`null` → 那一行不显示） |
| `feature/me/…/composeResources/values/strings.xml` | `me_tracking_chip`、`me_tracking_chip_desc`、`me_tracking_chip_grant`、`me_tracking_chip_on`、`me_tracking_chip_off` |
| `androidApp/…/AndroidManifest.xml` | `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />` |

`SettingsRepository` 改接口要连带 iOS 侧实现，注意别只改 Android。

## 10. 代码落点

```
androidApp/src/main/
├── AndroidManifest.xml                     + SYSTEM_ALERT_WINDOW
├── res/layout/notification_live_progress.xml        新（P0 折叠态）
├── res/layout/notification_live_progress_big.xml    新（P0 展开态）
├── res/drawable/…                                   新（线路色块、点列形状）
└── java/com/trackr/service/
    ├── LiveProgressNotificationHelper.kt    buildNotification 里按 SDK 分支；新增 buildCompactCard()
    ├── BusTrackingService.kt                render() 里顺带 chip.update()；shutdown/onDestroy 里 chip.remove()
    ├── overlay/TrackingChipPolicy.kt        新（纯 Kotlin：该不该显示、显示什么）
    ├── overlay/TrackingChipController.kt    新（WindowManager 增删改，接口 ChipHost 便于测）
    ├── overlay/TrackingChipPrefs.kt         新（位置、本次隐藏标记，SharedPreferences）
    └── tracking/AppForegroundState.kt       新（ProcessLifecycleOwner 计数）
```

`TrackingChipPrefs` 位置信息**不进 `SettingsRepository`**：这是纯 Android 的窗口状态，iOS 没有对应概念，塞进 KMP 接口只会让另一侧多一个没用的字段。

## 11. 测试

按 `docs/testing.md` 的分层：

| 测什么 | 放哪 | 方式 |
| --- | --- | --- |
| `TrackingChipPolicy.shouldShow` 的五条条件、多会话取焦点 | `androidApp/src/test` | 纯 Kotlin 单测，参数全注入 |
| `TrackingChipPolicy.contentOf` 各阶段文案（等车 / 乘车 / 一起等 / 信号丢失） | 同上 | 复用 `TrackingSessionState` 构造样例 |
| `TrackingChipController` 的 add / remove 幂等、`addView` 抛异常后不再重试 | 同上 | fake `ChipHost`（一个接口 + 计数），不碰真 `WindowManager` |
| 权限分支 | Robolectric | `ShadowSettings.setCanDrawOverlays(true/false)` |
| P0 卡片渲染 | Robolectric | `Notification.extras` 里 `EXTRA_TEMPLATE`、`contentView` 非空；文案和 `buildTexts` 一致 |
| 真机 | 手动 | Android 10 / 12 / 15 各一台，浅色 + 深色 + 大字 + 长辈版；MIUI 单独验悬浮窗 + 后台弹出界面两个开关 |

现有 `LiveProgressNotificationHelperTest` 里的 `allowPromotion` 相关用例不受影响（`SDK_INT` 在 Robolectric 下默认不是 36）。

## 12. 风险

| 风险 | 处理 |
| --- | --- |
| Google Play 对 `SYSTEM_ALERT_WINDOW` 要求说明核心用途 | 现在靠 GitHub 分发，不阻塞；上架前在 Play Console 提交「追踪中显示车距」的说明，或整包按渠道关掉该功能 |
| 国产 ROM 静默失效 | 授权后立刻预览 + 追踪 30 秒内没画出来就在「我的」那行标「未授权」，不猜 |
| 用户开了小条觉得挡事 | 长按隐藏 + 吸附左右边缘 + 回到 App 自动消失，三件事一起做才够 |
| 自定义通知在某些 ROM 上被换成默认模板 | 标题 / 正文照常设置，退化成现在的样子，不是白屏 |
| 通知卡高度被裁 | 折叠态控制在 64dp 以内，多的信息放展开态；真机逐个核 |
| 加了一个开关但没人知道 | P0 做完后「追踪中」的首页追踪卡底部加一句「下拉通知栏可固定查看」；不做弹窗引导 |

## 13. 不做的事

- **气泡通知**：国产 ROM 大面积不支持，且语义是聊天。
- **画中画**：窗口比例受限，只有离开 App 才浮起来，国内 ROM 表现参差。
- **无障碍悬浮**：为了一个可选功能去要一个高风险权限，不值。
- **复刻灵动岛**：位置、尺寸、动效都不由我们决定，做出来是仿制品。
- **默认开启悬浮窗**：任何情况下都不是默认。

## 14. 顺序

1. P0 通知卡（Android 8–15）+ 文案复用，Android 16 路径不动。
2. P0.5 通知健康检查行。
3. P1 小条：先做 `TrackingChipPolicy` + 单测，再上 `TrackingChipController`，最后接设置项与权限引导。
4. 真机清单跑一遍再发版；`docs/design/ui_design_spec.md` 补一节卡片与「我的」新增行的视觉规范。
