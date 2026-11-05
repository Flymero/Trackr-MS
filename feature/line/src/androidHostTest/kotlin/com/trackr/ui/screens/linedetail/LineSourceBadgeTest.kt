package com.trackr.ui.screens.linedetail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        compose.onNodeWithText("眉山官方").assertIsDisplayed()
        compose.onNodeWithText("TCP").assertDoesNotExist()
        compose.runOnIdle { online.value = true }
        compose.onNodeWithText("眉山官方").assertIsDisplayed()
        compose.onNodeWithText("TCP").assertIsDisplayed()
        compose.runOnIdle { online.value = false }
        compose.onNodeWithText("TCP").assertDoesNotExist()
        compose.onNodeWithText("眉山官方").assertIsDisplayed()
    }

    @Test
    fun thirdPartyBadge_doesNotDisplayTcp() {
        compose.setContent {
            TrackrTheme { LineSourceBadge(DataSourceType.CHELAILE, true) }
        }
        compose.onNodeWithText(DataSourceType.CHELAILE.displayName).assertIsDisplayed()
        compose.onNodeWithText("TCP").assertDoesNotExist()
    }
}
