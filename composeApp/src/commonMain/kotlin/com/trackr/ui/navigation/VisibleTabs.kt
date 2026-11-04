package com.trackr.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.core.data.repository.AmapKeyRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.model.isMeishanLocal
import com.trackr.core.navigation.Navigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.core.navigation.TopLevel

/**
 * 底栏要显示的页签（D2，第 11 步）：按 [AmapKeyRepository.hasMapKey] 决定有没有「路线规划」，
 * 页签集合一变就交给 [Navigator.onTabsChanged]（正停在被去掉的页签上时清栈回首页）。
 *
 * 规则：Android Key 或 Web Key 有一个就显示（Q18：只有 Web Key 也显示，只是不画地图），见 `AmapKeyRules`。
 * 眉山特化：「线路」页签走官方全量线路数据，只在眉山 / 仁寿（[SettingsRepository.selectedCity]）时显示。
 */
@Composable
fun rememberVisibleTabs(
    amapKeys: AmapKeyRepository,
    settings: SettingsRepository,
    navigator: Navigator
): List<ScreenKey> {
    val hasMapKey by amapKeys.hasMapKey.collectAsStateWithLifecycle()
    val selectedCity by settings.selectedCity.collectAsStateWithLifecycle()
    val showLinesTab = selectedCity.isMeishanLocal
    val tabs = remember(hasMapKey, showLinesTab) { TopLevel.visibleTabs(hasMapKey, showLinesTab) }
    LaunchedEffect(navigator, tabs) {
        navigator.onTabsChanged(tabs.toSet())
    }
    return tabs
}
