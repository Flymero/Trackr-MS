package com.trackr.ui.screens.favoritesedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 拖动排序的位移计算（编辑收藏页）：拖动中「被拖那行跟手 + 中间的行各让一格」，
 * 松手后「各行静态位置就是落点，只有被拖那行补一点零头」。行高固定 100，方便看数。
 */
class ReorderShiftTest {

    private val row = 100f

    @Test
    fun draggedRow_followsTheFinger() {
        // 从第 1 行往下拖了 37px：被拖的那行就画在 37px，别的行还没跨过一整行，不动
        assertEquals(37f, dragShiftPx(index = 1, fromIndex = 1, toIndex = 1, dragging = true, dragOffsetPx = 37f, rowHeightPx = row))
        assertEquals(0f, dragShiftPx(index = 0, fromIndex = 1, toIndex = 1, dragging = false, dragOffsetPx = 37f, rowHeightPx = row))
        assertEquals(0f, dragShiftPx(index = 2, fromIndex = 1, toIndex = 1, dragging = false, dragOffsetPx = 37f, rowHeightPx = row))
    }

    @Test
    fun rowsBetweenStartAndTarget_shiftUp_whenDraggingDown() {
        // 第 1 行拖到第 3 行：原来 2、3 行各上移一格
        assertEquals(-row, dragShiftPx(2, fromIndex = 1, toIndex = 3, dragging = false, dragOffsetPx = 200f, rowHeightPx = row))
        assertEquals(-row, dragShiftPx(3, fromIndex = 1, toIndex = 3, dragging = false, dragOffsetPx = 200f, rowHeightPx = row))
        // 起止之外的行不动
        assertEquals(0f, dragShiftPx(0, fromIndex = 1, toIndex = 3, dragging = false, dragOffsetPx = 200f, rowHeightPx = row))
        assertEquals(0f, dragShiftPx(4, fromIndex = 1, toIndex = 3, dragging = false, dragOffsetPx = 200f, rowHeightPx = row))
    }

    @Test
    fun rowsBetweenStartAndTarget_shiftDown_whenDraggingUp() {
        // 第 3 行拖到第 1 行：原来 1、2 行各下移一格
        assertEquals(row, dragShiftPx(1, fromIndex = 3, toIndex = 1, dragging = false, dragOffsetPx = -200f, rowHeightPx = row))
        assertEquals(row, dragShiftPx(2, fromIndex = 3, toIndex = 1, dragging = false, dragOffsetPx = -200f, rowHeightPx = row))
        assertEquals(0f, dragShiftPx(0, fromIndex = 3, toIndex = 1, dragging = false, dragOffsetPx = -200f, rowHeightPx = row))
        assertEquals(0f, dragShiftPx(4, fromIndex = 3, toIndex = 1, dragging = false, dragOffsetPx = -200f, rowHeightPx = row))
    }

    @Test
    fun afterDrop_onlyTheDraggedRowStillHasAResidual() {
        // 1 -> 3：松手那一帧布局已经按新顺序站好了，2、3 行的静态位置就在落点（位移 0）；
        // 被拖那行手指停在 2.2 格，离落点还差 0.2 格，只补这 20px 回 0
        assertEquals(20f, dropResidualPx(offsetPx = 220f, fromIndex = 1, toIndex = 3, rowHeightPx = row))

        // 往上拖反过来：3 -> 1，手指停在 0.8 格（-220px），离落点还差 -20px
        assertEquals(-20f, dropResidualPx(offsetPx = -220f, fromIndex = 3, toIndex = 1, rowHeightPx = row))
    }

    @Test
    fun droppedExactlyOnTheSlot_hasNoResidual() {
        // 正好落在一格上：松手即到位，一点动画都不该有
        assertEquals(0f, dropResidualPx(offsetPx = 200f, fromIndex = 1, toIndex = 3, rowHeightPx = row))
        assertEquals(0f, dropResidualPx(offsetPx = -200f, fromIndex = 3, toIndex = 1, rowHeightPx = row))
    }

    @Test
    fun droppedInPlace_settlesBackFromWhereTheFingerIs() {
        // 没换位置也算一次落位：从手指停的地方（0.37 格）滑回自己格子里
        assertEquals(37f, dropResidualPx(offsetPx = 37f, fromIndex = 2, toIndex = 2, rowHeightPx = row))
        assertEquals(-37f, dropResidualPx(offsetPx = -37f, fromIndex = 2, toIndex = 2, rowHeightPx = row))
        // 拖动中也一样：没跨过格子就没人让位
        for (index in 0..3) {
            assertEquals(
                0f,
                dragShiftPx(index, fromIndex = 2, toIndex = 2, dragging = false, dragOffsetPx = 0f, rowHeightPx = row)
            )
        }
    }

    // ---- 边缘自动翻页 ----

    @Test
    fun autoScroll_isStillInTheMiddle() {
        // 视口 0..800、边缘 72：手指在 400 就是不动
        assertEquals(0f, edgeScrollStepPx(400f, viewportTop = 0f, viewportBottom = 800f, edgePx = 72f, maxStepPx = 15f))
        assertEquals(0f, edgeScrollStepPx(728f, viewportTop = 0f, viewportBottom = 800f, edgePx = 72f, maxStepPx = 15f))
        assertEquals(0f, edgeScrollStepPx(72f, viewportTop = 0f, viewportBottom = 800f, edgePx = 72f, maxStepPx = 15f))
    }

    @Test
    fun autoScroll_scrollsDownNearTheBottomEdge() {
        // 越靠下越快：刚进边缘区是一点点，贴到底就是满速
        val justInside = edgeScrollStepPx(730f, viewportTop = 0f, viewportBottom = 800f, edgePx = 72f, maxStepPx = 15f)
        val atEdge = edgeScrollStepPx(800f, viewportTop = 0f, viewportBottom = 800f, edgePx = 72f, maxStepPx = 15f)
        val outside = edgeScrollStepPx(900f, viewportTop = 0f, viewportBottom = 800f, edgePx = 72f, maxStepPx = 15f)
        assertTrue(justInside > 0f && justInside < 15f)
        assertEquals(15f, atEdge)
        // 出了视口也封顶，别越拖越快
        assertEquals(15f, outside)
    }

    @Test
    fun autoScroll_scrollsUpNearTheTopEdge() {
        val justInside = edgeScrollStepPx(60f, viewportTop = 0f, viewportBottom = 800f, edgePx = 72f, maxStepPx = 15f)
        val atEdge = edgeScrollStepPx(0f, viewportTop = 0f, viewportBottom = 800f, edgePx = 72f, maxStepPx = 15f)
        assertTrue(justInside < 0f && justInside > -15f)
        assertEquals(-15f, atEdge)
    }

    @Test
    fun autoScroll_doesNothingBeforeTheListIsMeasured() {
        // 还没布局（viewport 是空的）时别乱滚
        assertEquals(0f, edgeScrollStepPx(400f, viewportTop = 0f, viewportBottom = 0f, edgePx = 72f, maxStepPx = 15f))
        assertEquals(0f, edgeScrollStepPx(400f, viewportTop = 100f, viewportBottom = 800f, edgePx = 0f, maxStepPx = 15f))
    }
}
