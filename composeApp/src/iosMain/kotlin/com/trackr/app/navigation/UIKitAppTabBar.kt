@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.trackr.app.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.trackr.app.bottomTabLabel
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.navigation.ScreenKey
import kotlinx.cinterop.useContents
import platform.darwin.NSObject
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.CGRectMake
import platform.UIKit.*

/** UIKit 负责页签布局、SF Symbols、VoiceOver 和系统版本对应的外观。 */
internal class UIKitAppTabBar : AppTabBar {
    override val collapsesOnScroll = false
    override val initialContentHeight = 49.dp

    @Composable
    override fun Content(tabs: List<ScreenKey>, selected: ScreenKey, onSelect: (ScreenKey) -> Unit, modifier: Modifier) {
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        var contentHeight by remember { mutableStateOf(initialContentHeight) }
        val callbacks = tabs.associateWith { key -> dropUnlessResumed { onSelect(key) } }
        val currentCallbacks by rememberUpdatedState(callbacks)
        val currentSelected by rememberUpdatedState(selected)
        val items = remember(tabs) {
            tabs.mapIndexed { index, key ->
                UITabBarItem(title = bottomTabLabel(key), image = UIImage.systemImageNamed(key.symbol()), tag = index.toLong())
            }
        }
        val delegate = remember(tabs, items) {
            object : NSObject(), UITabBarDelegateProtocol {
                override fun tabBar(tabBar: UITabBar, didSelectItem: UITabBarItem) {
                    // UIKit 先选中再通知。先恢复共享状态，避免隐私弹窗拦截后原生栏留在错误页签。
                    tabBar.selectedItem = items.getOrNull(tabs.indexOf(currentSelected))
                    tabs.getOrNull(didSelectItem.tag.toInt())?.let { currentCallbacks[it]?.invoke() }
                }
            }
        }
        val colors = MaterialTheme.colorScheme
        val darkTheme = TrackrTheme.isDark
        UIKitView(
            factory = {
                MeasuringTabBar { height -> contentHeight = height.toFloat().dp }
            },
            modifier = modifier.fillMaxWidth().height(contentHeight + bottomInset),
            update = { bar ->
                bar.delegate = delegate
                if (bar.items != items) bar.setItems(items, animated = false)
                bar.selectedItem = items.getOrNull(tabs.indexOf(selected))
                bar.tintColor = colors.primary.uiColor()
                // 叠在 Compose 上方，让系统玻璃材质采样页面；安全区透出页面底色。
                bar.backgroundColor = UIColor.clearColor
                bar.overrideUserInterfaceStyle = if (darkTheme) {
                    UIUserInterfaceStyle.UIUserInterfaceStyleDark
                } else UIUserInterfaceStyle.UIUserInterfaceStyleLight
            },
            onRelease = { it.delegate = null; it.onContentHeight = null },
            properties = UIKitInteropProperties(
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
                isNativeAccessibilityEnabled = true,
                placedAsOverlay = true,
            ),
        )
    }
}

/** 使用 UIKit 的实际尺寸，跟随横屏、安全区和系统布局变化，不把小白条重复算进内容高度。 */
private class MeasuringTabBar(var onContentHeight: ((Double) -> Unit)?) : UITabBar(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    override fun layoutSubviews() {
        super.layoutSubviews()
        val idealHeight = sizeThatFits(bounds.useContents { CGSizeMake(size.width, size.height) }).useContents { height }
        val bottom = safeAreaInsets.useContents { bottom }
        if (idealHeight > bottom) onContentHeight?.invoke(idealHeight - bottom)
    }
}

private fun ScreenKey.symbol(): String = when (this) {
    ScreenKey.Home -> "bus"
    ScreenKey.RoutePlan -> "point.topleft.down.to.point.bottomright.curvepath"
    ScreenKey.More -> "person.crop.circle"
    else -> error("$this 不是顶层页签")
}

private fun Color.uiColor() = UIColor(red = red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = alpha.toDouble())
