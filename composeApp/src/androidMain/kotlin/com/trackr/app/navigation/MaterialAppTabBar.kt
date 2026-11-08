package com.trackr.app.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trackr.app.bottomTabIcon
import com.trackr.app.bottomTabLabel
import com.trackr.core.navigation.ScreenKey

internal class MaterialAppTabBar : AppTabBar {
    override val collapsesOnScroll = true
    override val initialContentHeight = 80.dp

    @Composable
    override fun Content(tabs: List<ScreenKey>, selected: ScreenKey, onSelect: (ScreenKey) -> Unit, modifier: Modifier) {
        NavigationBar(modifier) {
            tabs.forEach { key ->
                val label = bottomTabLabel(key)
                NavigationBarItem(
                    selected = selected == key,
                    onClick = dropUnlessResumed { onSelect(key) },
                    icon = { Icon(bottomTabIcon(key), contentDescription = label) },
                    label = { Text(label) },
                )
            }
        }
    }
}
