package com.trackr.feature.bus.citypicker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.TransitCity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// 默认图形模式下 Robolectric 不真量字宽，大字体换行的用例要 NATIVE
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        composeTestRule.onNodeWithText("当前").assertIsDisplayed()
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

    /** #44：大字体 + 窄屏时标签多，城市名不能被挤成一字一行，标签换到下一行。 */
    @Test
    fun cityPickerItemRow_largeFontNarrowWidth_keepsCityNameOnOneLine() {
        val city = TransitCity(
            cityId = "0771",
            cityName = "南宁",
            province = "广西壮族自治区",
            mygolbsCityCode = "450100"
        )

        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                TrackrTheme {
                    Box(modifier = Modifier.width(300.dp)) {
                        CityPickerItemRow(
                            city = city,
                            isCurrent = true,
                            isLocated = true,
                            onClick = {}
                        )
                    }
                }
            }
        }

        val name = composeTestRule.onNodeWithText("南宁", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val nameWidth = name.right - name.left
        val nameHeight = name.bottom - name.top
        assertTrue("城市名被挤窄了：宽 $nameWidth，高 $nameHeight", nameWidth > nameHeight)
        composeTestRule.onNodeWithText("掌上公交").assertIsDisplayed()
    }
}
