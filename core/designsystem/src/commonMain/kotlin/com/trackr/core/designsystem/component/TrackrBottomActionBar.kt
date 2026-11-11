package com.trackr.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 子页面 `Scaffold(bottomBar = …)` 用的底部操作栏（保存、开始追踪等）。
 *
 * 应用是 edge-to-edge 的（透明导航栏、不强制对比度），而 `Scaffold` 的 `contentWindowInsets` 只管内容区、
 * 不管 `bottomBar`。所以贴底的操作栏必须自己让开系统导航栏和软键盘：手势导航时导航栏只有一条细线看不出来，
 * 三键虚拟导航栏时按钮就被盖住了（#46）。背景色仍铺到屏幕底边，只有按钮往上让。
 */
@Composable
fun TrackrBottomActionBar(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(10.dp),
    content: @Composable RowScope.() -> Unit
) {
    Surface(modifier = modifier, color = color) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = horizontalArrangement,
            content = content
        )
    }
}
