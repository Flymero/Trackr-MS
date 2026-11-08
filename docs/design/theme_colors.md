# 主题与颜色

入口：「我的 → 显示 → 主题与颜色」。设置立即应用，通过设置仓库排队持久化，无需重启。

## 选择模型

- 明暗：系统 / 浅色 / 深色，沿用 `ThemeMode`。
- 来源：系统 / 预设 / 自定义；切换来源保留各自上次的选择。
- 系统：Android 12+ 可原样使用系统完整方案，或选系统主色、辅助色、点缀色作为种子重新生成；种子取固定 tone，不随应用明暗切换。其他平台回落到上次预设，不保存壁纸颜色快照。
- 预设：蓝、青绿、紫、粉、橙、灰。
- 自定义：六位 RGB HEX；无效输入只显示错误，不覆盖已应用颜色。取色对话框提供二维渐变色盘、彩虹色相条、RGB 通道和 HEX 输入，所有控件同步，确认后应用。
- 风格：Tonal Spot / Neutral / Vibrant / Expressive / Fidelity / Content / Monochrome / Rainbow / Fruit Salad。原样跟随系统时隐藏应用风格选择；全部九种风格直接展示。

MaterialKolor 5.0.1（发布的稳定版本）使用 MCU `SPEC_2021`，与确认的设计稿保持一致。整个 `ColorScheme` 一起生成，不单独替换主色。到站绿、停运橙等 `TrackrColors` 继续独立；长辈版沿用既有高对比颜色，配色偏好保留到标准版恢复。

`ThemeColors` 在 `:core:model`；设置仓库和 Fake 暴露同一接口。完整偏好序列化为单个 `theme_colors` 设置值，在 DataStore 和旧 SharedPreferences 都支持；只读迁移也覆盖此键。文件名和数据库结构不变。

## 组件计划与例外

| 元素 | 组件 / 来源 | 行为 |
| --- | --- | --- |
| 设置入口 | 现有 `SettingRow` | Navigator 打开独立页面 |
| 页面 / 返回 | M3 `Scaffold`、`TopAppBar`、`IconButton` | 生命周期感知导航 |
| 明暗 / 来源 | M3 `SegmentedButton`、`PrimaryTabRow` | 单选、选中语义 |
| 系统方案 | M3 `OutlinedCard`、`ListItem`、`RadioButton` | 原样跟随 |
| 种子色 | M3 `FilledIconButton` | 命名、选中勾、RadioButton 语义 |
| 风格 | M3 `OutlinedCard` | 选中边框、RadioButton 语义 |
| 输入 / 取色 | M3 `OutlinedTextField`、`AlertDialog`、`Slider` | 错误提示、独立草稿、RGB / HEX 输入、彩虹色相条 |
| 公交预览 | 共享 `LineBadge`、`ArrivalTrailing`，M3 卡片、按钮、导航栏 | 预览中的操作禁用 |

风格卡内部的装饰性配色缩略图采用自定义绘制布局：检索 `:core:designsystem` 和 M3 后没有对应的配色样本组件。用只读 `Surface` / `Box` 显示生成后的颜色，点击、焦点和无障碍仍由外层 M3 卡片承担。种子色展示值属于用户数据，不作为硬编码 UI 令牌。

二维色盘例外：已检索 `:core:designsystem`、项目依赖及 M3 组件，均无二维颜色选择 API；候选 colorpicker-compose 的 HSV 控件为圆盘。为匹配常见的矩形色盘，仅自绘饱和度 / 明度渐变、光标及二维定位，弹窗、色相滑条、输入和按钮仍使用 M3。提供键盘方向键、无障碍调整动作、颜色状态描述和 RGB / HEX 精确输入，并验证点击、拖动、同步、取消及草稿恢复。

## 验证

- `ThemeSettingsViewModelTest`：连续操作、切换来源保留偏好、重开、无效颜色、长辈版。
- `ThemeColorPickerTest`：RGB / HEX 同步、二维色盘点击和拖动、无效输入、取消不应用、草稿恢复。
- `ThemeSettingsScreenTest`：选种子、全部九种风格直接可用、系统方案与种子模式切换、无效输入。
- `ThemeColorSchemeTest`：九种风格与六种预设在深浅色下的文字对比度、系统完整方案、低版本回退、业务语义色、长辈版。
- `ThemeModeTest`：真实设置事件经根主题更新明暗和系统栏。
- `UiModeSettingsTest`：新旧存储重开、旧到新迁移不删除旧键、损坏偏好回退。
- `NavigationStateRestorationTest`：新页面参与完整返回栈序列化恢复。
