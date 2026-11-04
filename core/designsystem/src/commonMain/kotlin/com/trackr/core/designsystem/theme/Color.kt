package com.trackr.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 设计稿 v3 的语义色（docs/design/redesign_v3.html 「颜色」一节）。
 * 只有 5 个有意思的颜色：蓝 = 可点 / 品牌（`colorScheme.primary`）；绿 [go] = 车快到了；
 * 橙 [warn] = 今天坐不上了；红（`colorScheme.error`）= 结束和删除；灰 [walk] = 已过站、步行。
 * 通过 `TrackrTheme.colors` 读取，随深浅色切换。
 */
@Immutable
data class TrackrColors(
    /** 已到站 / ≤ 1 站 / ≤ 3 分钟。 */
    val go: Color,
    val goContainer: Color,
    /** 末班已过 / 停运 / 没数据。 */
    val warn: Color,
    val warnContainer: Color,
    /** 已过站、步行段、次要说明。 */
    val walk: Color,
)

val LocalTrackrColors = staticCompositionLocalOf { LightTrackrColors }

internal val LightTrackrColors = TrackrColors(
    go = Color(0xFF12924F),            // --go
    goContainer = Color(0xFFDDF5E7),   // --go-c
    warn = Color(0xFFB45F00),          // --warn
    warnContainer = Color(0xFFFFEBCC), // --warn-c
    walk = Color(0xFF8A929D),          // --text3
)

internal val DarkTrackrColors = TrackrColors(
    go = Color(0xFF4CD38A),
    goContainer = Color(0xFF13361F),
    warn = Color(0xFFFFB547),
    warnContainer = Color(0xFF3D2A00),
    walk = Color(0xFF6B7380),
)

/**
 * v3 蓝 `#1F5FD6`，浅色。不用动态取色。
 * secondary / tertiary 也用蓝：v3 只有一个品牌色（chip 选中、底栏指示器等 M3 组件读 secondaryContainer）。
 */
internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF1F5FD6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A2A6B),
    inversePrimary = Color(0xFF8AB0FF),
    secondary = Color(0xFF1F5FD6),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6FF),
    onSecondaryContainer = Color(0xFF0A2A6B),
    tertiary = Color(0xFF1F5FD6),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE6FF),
    onTertiaryContainer = Color(0xFF0A2A6B),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF15181D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15181D),
    surfaceVariant = Color(0xFFEDF0F4),
    onSurfaceVariant = Color(0xFF5B6470),
    surfaceTint = Color(0xFF1F5FD6),
    inverseSurface = Color(0xFF2A2F36),
    inverseOnSurface = Color(0xFFEFF1F4),
    error = Color(0xFFD93025),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE3E1),
    onErrorContainer = Color(0xFF8C1D18),
    outline = Color(0xFFD6DBE2),
    outlineVariant = Color(0xFFEDF0F4),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4E7EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F4F7),
    surfaceContainerHighest = Color(0xFFEDF0F4),
)

/** v3 蓝，深色。 */
internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF8AB0FF),
    onPrimary = Color(0xFF082561),
    primaryContainer = Color(0xFF1D3B80),
    onPrimaryContainer = Color(0xFFDCE6FF),
    inversePrimary = Color(0xFF1F5FD6),
    secondary = Color(0xFF8AB0FF),
    onSecondary = Color(0xFF082561),
    secondaryContainer = Color(0xFF1D3B80),
    onSecondaryContainer = Color(0xFFDCE6FF),
    tertiary = Color(0xFF8AB0FF),
    onTertiary = Color(0xFF082561),
    tertiaryContainer = Color(0xFF1D3B80),
    onTertiaryContainer = Color(0xFFDCE6FF),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE7EAEE),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFE7EAEE),
    surfaceVariant = Color(0xFF22262E),
    onSurfaceVariant = Color(0xFF9AA3AF),
    surfaceTint = Color(0xFF8AB0FF),
    inverseSurface = Color(0xFFE7EAEE),
    inverseOnSurface = Color(0xFF181B21),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A1612),
    errorContainer = Color(0xFF4A1612),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF343A44),
    outlineVariant = Color(0xFF22262E),
    surfaceBright = Color(0xFF2A2E36),
    surfaceDim = Color(0xFF0F1115),
    surfaceContainerLowest = Color(0xFF0F1115),
    surfaceContainerLow = Color(0xFF181B21),
    surfaceContainer = Color(0xFF181B21),
    surfaceContainerHigh = Color(0xFF1D2027),
    surfaceContainerHighest = Color(0xFF22262E),
)

/**
 * 数据源标识专属色彩体系
 * 车来了（绿色调）、掌上公交（橙色调）、眉山官方（蓝色调）
 */
val DataSourceChelaileContainer = Color(0xFFE8F5E9)
val DataSourceChelaileContent = Color(0xFF2E7D32)
val DataSourceMygolbsContainer = Color(0xFFFFF3E0)
val DataSourceMygolbsContent = Color(0xFFE65100)
val DataSourceMeishanContainer = Color(0xFFE3F2FD)
val DataSourceMeishanContent = Color(0xFF1565C0)
