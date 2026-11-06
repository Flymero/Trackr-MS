package com.trackr.ui.components

import com.trackr.core.model.BusStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 竖排站名的括号（#23）：括号换成横版括号（按行高画出来，不再竖着写），其余字照常竖着排。
 */
class RailVerticalNameTest {

    @Test
    fun parentheses_becomeVerticalBrackets() {
        listOf('（', '）', '(', ')').forEach { ch ->
            assertTrue(railCharIsVerticalBracket(ch), "「$ch」竖排时要换成横版括号")
        }
    }

    @Test
    fun otherBracketShapes_becomeVerticalBrackets() {
        listOf('【', '】', '［', '］', '[', ']', '｛', '｝', '{', '}', '〔', '〕').forEach { ch ->
            assertTrue(railCharIsVerticalBracket(ch), "「$ch」竖排时要换成横版括号")
        }
    }

    @Test
    fun ordinaryCharacters_stayUpright() {
        "人民东路学海路南汇大学城站".forEach { ch ->
            assertFalse(railCharIsVerticalBracket(ch), "「$ch」不该换")
        }
        // 已经是竖排形式的括号不再换（换了又变回普通括号）
        listOf('︵', '︶', '﹁', '﹂', '、', '，', '·').forEach { ch ->
            assertFalse(railCharIsVerticalBracket(ch), "「$ch」不该换")
        }
    }

    @Test
    fun bracketNameKeepsOneCharacterPerLine() {
        // 换不换都还是一字一行，站名高度仍按字数算
        val stops = listOf(
            BusStop(stopId = "1", name = "人民东路学海路（南汇大学城）", order = 1),
            BusStop(stopId = "2", name = "人民东路", order = 2)
        )

        assertEquals(14, railNameLines(stops))
        assertEquals("人民东路学海路（南汇大学城）".length, railNameLines(stops))
    }

    @Test
    fun verticalText_putsPlaceholderOnBracketRows() {
        // 括号那几行换全角空格占位，等画出来的横版括号盖上；其余字还是一字一行
        assertEquals("人\n民\n广\n场\n　\n武\n胜\n路\n　", railVerticalNameText("人民广场（武胜路）"))
        assertEquals("人\n民\n广\n场", railVerticalNameText("人民广场"))
        assertEquals("　\n测\n试\n　", railVerticalNameText("【测试】"))
    }
}
