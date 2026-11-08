package com.trackr.feature.line.list

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.MeishanArea
import com.trackr.core.model.MeishanLine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineListScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longOfficialNamesAndNotesStayClickableAndGeneratePreview() {
        // Public /line/list labels observed on 2026-11-09; no live HTTP in the test.
        val lines = listOf(
            MeishanLine(lId = 1, name = "1\u8def", forwardText = "\u7709\u5c71\u4e1c\u7ad9:\u5343\u79be\u5473\u4e1a", area = MeishanArea.MEISHAN),
            MeishanLine(lId = 11, name = "3\u8def\uff086:30-19:15\uff09", forwardText = "\u84ec\u83b1\u5e7f\u573a:\u56db\u5ddd\u5927\u5b66\u7709\u5c71\u6821\u533a", area = MeishanArea.MEISHAN),
            MeishanLine(lId = 127, name = "302\u8def\uff08\u516c\u4ea4\u5ba2\u8fd0\u7ad9-\u7518\u7709\u56ed\u533a\u9996\u672b\u7ad9\uff09",
                forwardText = "\u516c\u4ea4\u5ba2\u8fd0\u7ad9:\u7518\u7709\u56ed\u533a\u9996\u672b\u7ad9\uff08\u5982\u9047\u7ebf\u8def\u4e0d\u6e05\u695a\uff0c\u8bf7\u62e8\u6253028-38224456\uff09", area = MeishanArea.MEISHAN)
        )
        val events = mutableListOf<LineListEvent>()
        compose.mainClock.autoAdvance = false
        compose.setContent { TrackrTheme { LineListScreen(LineListUiState(lines = lines), events::add) } }
        compose.mainClock.advanceTimeBy(500)
        val image = compose.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val file = File("build/screenshots/followup/line-list.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shortBounds = compose.onNodeWithText(lines.first().forwardText!!).fetchSemanticsNode().boundsInRoot
        val longBounds = compose.onNodeWithText(lines.last().forwardText!!).fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(3_000)
        val scrolling = compose.onRoot().captureToImage().asAndroidBitmap()
        assertFalse("Text that fits must stay still", pixelsDiffer(image, scrolling, shortBounds))
        assertTrue("Long official notes must scroll", pixelsDiffer(image, scrolling, longBounds))
        File(file.parentFile, "line-list-scrolling.png").outputStream().use {
            scrolling.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText(lines.last().name).performClick()
        assertEquals(LineListEvent.LineClicked(lines.last()), events.last())
    }

    private fun pixelsDiffer(before: Bitmap, after: Bitmap, bounds: Rect): Boolean {
        val left = bounds.left.toInt().coerceIn(0, before.width - 1)
        val top = bounds.top.toInt().coerceIn(0, before.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, before.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, before.height)
        for (y in top until bottom) for (x in left until right) {
            if (before.getPixel(x, y) != after.getPixel(x, y)) return true
        }
        return false
    }
}
