package com.trackr.core.designsystem.theme

import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberSystemThemeColors(): SystemThemeColors? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        SystemThemeColors(
            light = dynamicLightColorScheme(context),
            dark = dynamicDarkColorScheme(context),
            // Fixed tones: switching light/dark must not change the seed itself.
            primarySeed = Color(context.getColor(android.R.color.system_accent1_500)),
            secondarySeed = Color(context.getColor(android.R.color.system_accent2_500)),
            tertiarySeed = Color(context.getColor(android.R.color.system_accent3_500)),
        )
    }
}
