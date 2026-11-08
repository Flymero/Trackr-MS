package com.trackr.core.designsystem.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.trackr.core.model.ThemeMode
import com.trackr.core.model.ThemeColors
import com.trackr.core.model.UiMode

private val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 应用主题。根页面传入配色偏好；未传入时保留 v3 基线，供独立组件与旧预览使用。
 * 长辈版继续使用已有高对比配色，标准版按种子色 / 系统方案生成。
 *
 * 组件只读 `MaterialTheme.colorScheme/typography/shapes`、[TrackrTheme.colors]、[TrackrTheme.dimens]，
 * 不判断 [UiMode]；模式只在这里换 Token。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrackrTheme(
    uiMode: UiMode = UiMode.Standard,
    themeMode: ThemeMode = ThemeMode.System,
    darkTheme: Boolean = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    },
    themeColors: ThemeColors? = null,
    content: @Composable () -> Unit,
) {
    val tokens = tokensFor(uiMode)
    val colorScheme = if (themeColors != null && uiMode == UiMode.Standard) {
        rememberThemeColorScheme(themeColors, darkTheme)
    } else tokens.colorScheme(darkTheme)
    val trackrColors = tokens.colors(darkTheme)
    val busColors = remember(trackrColors, colorScheme) { legacyBusColors(trackrColors, colorScheme) }
    val extendedColors = remember(trackrColors, colorScheme) { legacyExtendedColors(trackrColors, colorScheme) }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalUiMode provides uiMode,
        LocalTrackrColors provides trackrColors,
        LocalTrackrDimens provides tokens.dimens,
        LocalTrackrTypography provides tokens.trackrTypography,
        LocalMinimumInteractiveComponentSize provides tokens.dimens.minTouchTarget,
        LocalBusSemanticColors provides busColors,
        LocalExtendedColors provides extendedColors,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = tokens.motion,
            shapes = tokens.shapes,
            typography = tokens.typography,
            content = content,
        )
    }
}

/** 主题里 M3 之外的 Token 读取入口。 */
object TrackrTheme {
    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalDarkTheme.current

    val colors: TrackrColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTrackrColors.current

    val dimens: TrackrDimens
        @Composable
        @ReadOnlyComposable
        get() = LocalTrackrDimens.current

    val uiMode: UiMode
        @Composable
        @ReadOnlyComposable
        get() = LocalUiMode.current
}

/** 一种界面模式的全部 Token。 */
internal interface ThemeTokens {
    fun colorScheme(darkTheme: Boolean): ColorScheme
    fun colors(darkTheme: Boolean): TrackrColors
    val typography: Typography
    val trackrTypography: TrackrTypography
    val shapes: Shapes
    val dimens: TrackrDimens
    val motion: MotionScheme
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object StandardTokens : ThemeTokens {
    override fun colorScheme(darkTheme: Boolean) = if (darkTheme) DarkColorScheme else LightColorScheme
    override fun colors(darkTheme: Boolean) = if (darkTheme) DarkTrackrColors else LightTrackrColors
    override val typography = StandardTypography
    override val trackrTypography = StandardTrackrTypography
    override val shapes = StandardShapes
    override val dimens = StandardDimens
    override val motion = MotionScheme.expressive()
}

/** 长辈版 Token 见 ElderTokens.kt（高对比、eta 56sp、触控 64dp、MotionScheme.standard()） */
internal fun tokensFor(uiMode: UiMode): ThemeTokens = when (uiMode) {
    UiMode.Standard -> StandardTokens
    UiMode.Elder -> ElderTokens
}

/**
 * 通用 M3E 弹性物理动画规格辅助函数
 */
fun <T> expressiveSpringSpec(
    dampingRatio: Float = Spring.DampingRatioMediumBouncy,
    stiffness: Float = Spring.StiffnessMediumLow
): SpringSpec<T> = spring(
    dampingRatio = dampingRatio,
    stiffness = stiffness
)

/**
 * 公交车徽标两站之间平滑物理推进动画规格
 */
fun <T> busChipSpringSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow
)

/**
 * 换向 180° 旋转弹簧动画规格
 */
fun <T> expressiveRotateSpringSpec(): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessLow
)
