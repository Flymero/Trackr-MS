package com.trackr.ui.screens.linedetail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.DataSourceType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LineSourceBadgeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun officialBadge_tcpAppearsOnlyWhileOnline() {
        val online = mutableStateOf(false)
        compose.setContent {
            TrackrTheme { LineSourceBadge(DataSourceType.MEISHAN, online.value) }
        }
        compose.onNodeWithText("TCP").assertDoesNotExist()
        compose.runOnIdle { online.value = true }
        compose.onNodeWithText("TCP").assertIsDisplayed()
        compose.runOnIdle { online.value = false }
        // 淡出动画 120ms：等它放完再断言消失
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithText("TCP").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun thirdPartyBadge_doesNotDisplayTcp() {
        compose.setContent {
            TrackrTheme { LineSourceBadge(DataSourceType.CHELAILE, true) }
        }
        compose.onNodeWithText(DataSourceType.CHELAILE.displayName).assertDoesNotExist()
        compose.onNodeWithText("TCP").assertDoesNotExist()
    }
}
