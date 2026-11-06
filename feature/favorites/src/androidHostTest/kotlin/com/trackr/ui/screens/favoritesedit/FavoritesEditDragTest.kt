package com.trackr.ui.screens.favoritesedit

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.FavoriteStationLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 拖动排序的手势接线：长按把手往下拖一行，松手后交出来的是「这一行插到新位置」的顺序。
 * 位移计算看 [ReorderShiftTest]（纯函数），这里只管手势到事件这一段是通的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp")
class FavoritesEditDragTest {

    @get:Rule
    val rule = createComposeRule()

    private val group = FavoriteGroup(id = "work", name = "上班", sortOrder = 0)

    private fun stop(id: String, line: String, stopName: String) = FavoriteItem.Stop(
        FavoriteStationLine(
            id = id,
            cityId = "034",
            cityName = "上海",
            lineId = "L$line",
            lineName = line,
            startStop = "火车站",
            endStop = stopName,
            targetStopId = "s$id",
            targetStopName = stopName,
            targetOrder = 3
        ),
        groupId = null,
        sortOrder = 0
    )

    @Test
    fun draggingFirstRowDownOneSlot_movesItBehindTheSecond() {
        val items = listOf(stop("a", "325", "科技园站"), stop("b", "M194", "深大北门"), stop("c", "19", "高新园"))
        // 假装是仓库：松手后把新顺序推回来
        val current = mutableStateOf(items)
        rule.setContent {
            TrackrTheme {
                FavoritesEditScreen(
                    items = current.value,
                    favoriteGroups = listOf(group),
                    onEvent = { event ->
                        if (event is FavoritesEditEvent.MoveItems) {
                            val byKey = current.value.associateBy { it.key }
                            current.value = event.keys.mapNotNull { byKey[it] }
                        }
                    }
                )
            }
        }

        // 把手：索引 0 是分组「上班」，收藏从 1 开始；行是 clickable 合并语义，要拿图标本体
        val handle = rule.onAllNodesWithContentDescription("拖动排序", useUnmergedTree = true)[1]

        // 长按的超时走 mainClock，得手动推
        rule.mainClock.autoAdvance = false
        handle.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
        // 往下拖过一整行（行高 ≈72dp，这里给 100px）
        handle.performTouchInput { moveBy(Offset(0f, 100f)) }
        rule.mainClock.advanceTimeBy(250)
        handle.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        assertEquals(listOf("stop_b", "stop_a", "stop_c"), current.value.map { it.key })
    }

    /**
     * 落位的位移只该作用在被拖那行上：让过位的行静态位置已经到了落点，仓库回写后不该再动一丝。
     * 前一版把残留的位移继续套在「第几行」上、再补间回 0，真机上看着就是松手后又插了一次。
     */
    @Test
    fun releasedRowsDoNotMoveAgainWhenTheRepositoryWritesBack() {
        val items = listOf(stop("a", "325", "科技园站"), stop("b", "M194", "深大北门"), stop("c", "19", "高新园"))
        val byKey = items.associateBy { it.key }
        val current = mutableStateOf(items)
        rule.setContent {
            TrackrTheme {
                FavoritesEditScreen(
                    items = current.value,
                    favoriteGroups = listOf(group),
                    onEvent = { event ->
                        // 真仓库也是这样：松手后几乎立刻回写
                        if (event is FavoritesEditEvent.MoveItems) {
                            current.value = event.keys.mapNotNull { byKey[it] }
                        }
                    }
                )
            }
        }

        val handle = rule.onAllNodesWithContentDescription("拖动排序", useUnmergedTree = true)[1]

        rule.mainClock.autoAdvance = false
        handle.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
        handle.performTouchInput { moveBy(Offset(0f, 100f)) }
        rule.mainClock.advanceTimeBy(250)
        handle.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(32)

        assertEquals(listOf("stop_b", "stop_a", "stop_c"), current.value.map { it.key })

        // 回写之后让过位的这两行就该定住了（落位动画只该作用在被拖那行上）
        val rightAfterWriteBack = listOf("stop_b", "stop_c").associateWith { rowTop(it) }
        rule.mainClock.advanceTimeBy(300)
        listOf("stop_b", "stop_c").forEach { key ->
            assertEquals(
                "仓库回写后 $key 又动了一下：回写时 ${rightAfterWriteBack.getValue(key)}，之后 ${rowTop(key)}",
                rightAfterWriteBack.getValue(key),
                rowTop(key),
                2f
            )
        }

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        // 被拖的那行最终停在 b 下面
        assertTrue("落位后 a 应该在 b 下面", rowTop("stop_a") > rowTop("stop_b"))
    }

    /** 一行在屏幕上的上沿（含拖动位移） */
    private fun rowTop(key: String): Float =
        rule.onNodeWithTag("favorite_row_$key").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun draggingToTheBottomEdge_scrollsTheListOnItsOwn() {
        // 20 条，列表比一屏长：不自动翻页的话，拖到底最多也就走十来行
        val items = (1..20).map { stop("s$it", "L$it", "$it 站") }
        val current = mutableStateOf(items)
        rule.setContent {
            TrackrTheme {
                FavoritesEditScreen(
                    items = current.value,
                    favoriteGroups = listOf(group),
                    onEvent = { event ->
                        if (event is FavoritesEditEvent.MoveItems) {
                            val byKey = current.value.associateBy { it.key }
                            current.value = event.keys.mapNotNull { byKey[it] }
                        }
                    }
                )
            }
        }

        val handle = rule.onAllNodesWithContentDescription("拖动排序", useUnmergedTree = true)[1]

        rule.mainClock.autoAdvance = false
        handle.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
        // 拖到屏幕底部边缘，然后手指停住不动 —— 让自动翻页自己滚（给足了虚拟时间，应该滚到列表末尾）
        handle.performTouchInput { moveBy(Offset(0f, 600f)) }
        repeat(300) { rule.mainClock.advanceTimeBy(16) }
        handle.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(400)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        val landed = current.value.indexOfFirst { it.key == "stop_s1" }
        assertTrue("自动翻页应该把第一条滚到列表末尾，实际落在第 $landed 位（共 ${current.value.size} 条）", landed >= 18)
    }
}
