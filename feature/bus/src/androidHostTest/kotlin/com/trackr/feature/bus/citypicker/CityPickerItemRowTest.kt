package com.trackr.feature.bus.citypicker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.TransitCity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CityPickerItemRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cityPickerItemRow_rendersLabelsAndHandlesClick() {
        val city = TransitCity(
            cityId = "0755",
            cityName = "深圳",
            province = "广东省",
            mygolbsCityCode = "440300"
        )
        var clicked = false

        composeTestRule.setContent {
            TrackrTheme {
                CityPickerItemRow(
                    city = city,
                    isCurrent = true,
                    isLocated = true,
                    onClick = { clicked = true },
                    showProvinceBadge = true
                )
            }
        }

        composeTestRule.onNodeWithText("深圳").assertIsDisplayed()
        composeTestRule.onNodeWithText("广东省").assertIsDisplayed()
        // 「当前」徽章已去掉：当前城市靠加粗 + 主色强调，不再有文字徽章
        composeTestRule.onNodeWithText("当前").assertDoesNotExist()
        composeTestRule.onNodeWithText("定位").assertIsDisplayed()
        composeTestRule.onNodeWithText("车来了").assertIsDisplayed()
        composeTestRule.onNodeWithText("掌上公交").assertIsDisplayed()

        composeTestRule.onNodeWithText("深圳").performClick()
        assertTrue(clicked)
    }

    @Test
    fun cityPickerItemRow_withoutProvinceBadge_doesNotShowProvince() {
        val city = TransitCity(
            cityId = "010",
            cityName = "北京",
            province = "直辖市"
        )

        composeTestRule.setContent {
            TrackrTheme {
                CityPickerItemRow(
                    city = city,
                    isCurrent = false,
                    isLocated = false,
                    onClick = {},
                    showProvinceBadge = false
                )
            }
        }

        composeTestRule.onNodeWithText("北京").assertIsDisplayed()
        composeTestRule.onNodeWithText("车来了").assertIsDisplayed()
        composeTestRule.onNodeWithText("直辖市").assertDoesNotExist()
        composeTestRule.onNodeWithText("当前").assertDoesNotExist()
        composeTestRule.onNodeWithText("定位").assertDoesNotExist()
    }
}
