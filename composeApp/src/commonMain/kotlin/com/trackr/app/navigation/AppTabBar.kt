package com.trackr.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.trackr.core.navigation.ScreenKey

/** 平台注入的底栏。选择状态由 Navigator 持有，原生控件只上报点击。 */
internal interface AppTabBar {
    val collapsesOnScroll: Boolean
    val initialContentHeight: Dp

    @Composable
    fun Content(
        tabs: List<ScreenKey>,
        selected: ScreenKey,
        onSelect: (ScreenKey) -> Unit,
        modifier: Modifier,
    )
}
