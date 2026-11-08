package com.trackr.core.domain.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteProjectionMathTest {

    // ---------------------------------------------------------------- 线路名排序

    private fun sorted(vararg names: String): List<String> =
        names.sortedWith { a, b -> RouteProjectionMath.compareLineNames(a, b) }

    @Test
    fun lineNames_pureNumbers_sortNumerically_notLexicographically() {
        // 字典序会把「303路」排在「3路」前（'0' < '路'）：数字段必须按数值比
        assertEquals(
            listOf("1路", "2路", "3路", "4路", "11路", "303路"),
            sorted("1路", "303路", "4路", "11路", "3路", "2路")
        )
    }

    @Test
    fun lineNames_suffixedVariant_staysAtItsNumber() {
        // 用户报告的现场：「3路夜班」曾被旧比较器沉到「303路」后面（2路 → 4路，3 路消失）
        assertEquals(
            listOf("2路", "3路", "3路夜班", "4路", "303路"),
            sorted("4路", "303路", "3路夜班", "2路", "3路")
        )
    }

    @Test
    fun lineNames_suffixedVariantWithNumber_keepsNumberOrder() {
        // 后缀里带数字（区间/支线）：仍然按主号排，不按后缀数字串
        assertEquals(
            listOf("3路", "3路支线", "4路", "23路", "23路B线"),
            sorted("23路B线", "3路", "4路", "23路", "3路支线")
        )
    }

    @Test
    fun lineNames_sameNumber_shorterBaseNameFirst() {
        // 同号：基础名短（无后缀）在前；原文相同则保持稳定（比较器返回 0）
        assertEquals(-1, RouteProjectionMath.compareLineNames("3路", "3路夜班"))
        assertEquals(0, RouteProjectionMath.compareLineNames("3路", "3路"))
    }

    @Test
    fun lineNames_nonNumericNames_sortAfterNumericsByName() {
        // 纯文字/字母开头的专线排在所有数字线路后，彼此按名称排
        assertEquals(
            listOf("3路", "303路", "快3路", "机场专线"),
            sorted("机场专线", "快3路", "303路", "3路")
        )
    }

    @Test
    fun lineNames_dataSourceTag_strippedBeforeCompare() {
        // 数据源标签不参与排序：清洗后同号，基础名短的（无标签）在前
        assertTrue(RouteProjectionMath.compareLineNames("3路", "3路 [掌上公交]") < 0)
        assertTrue(RouteProjectionMath.compareLineNames("3路 [掌上公交]", "3路") > 0)
        assertTrue(RouteProjectionMath.compareLineNames("5路[车来了]", "5路") > 0)
    }

    @Test
    fun lineNames_numberBeatsTextAtSamePosition() {
        // 同位置数字优先于文字：K 开头专线排在数字线路后
        assertTrue(RouteProjectionMath.compareLineNames("1路", "K1路") < 0)
        assertTrue(RouteProjectionMath.compareLineNames("K1路", "机场线") < 0)
    }
}
