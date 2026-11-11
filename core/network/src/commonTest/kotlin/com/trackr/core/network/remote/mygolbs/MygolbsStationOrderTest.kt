package com.trackr.core.network.remote.mygolbs

import com.trackr.core.network.testing.MockReply
import com.trackr.core.network.testing.mockHttpClient
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * issue #41「出现 900 站」：CMD=105 的站序是拿 stationID 推的，有的城市 stationID 是站点编号。
 * 站点详情要按 CMD=103 站表的站名核对。
 */
class MygolbsStationOrderTest {

    private fun cmd105(vararg lines: Pair<String, String>): String {
        val items = lines.joinToString(",") { (lineName, stationId) ->
            """{"lineName":"$lineName","upperOrDown":"1","stationID":"$stationId","neartext":"3站"}"""
        }
        return """{"status":"1","data":[$items]}"""
    }

    private fun cmd103(vararg names: String): String {
        val items = names.withIndex().joinToString(",") { (i, name) ->
            """{"stationName":"$name","stationOrder":${i + 1}}"""
        }
        return """{"status":"1","data":[$items]}"""
    }

    private fun client(cmd105: String, cmd103: (lineName: String) -> String) = MygolbsApiClient(
        mockHttpClient { request ->
            val cmd = Regex("CMD=(\\d+)").find(request.body)?.groupValues?.get(1)
            val lineName = Regex("LINENAME=([^&]*)").find(request.body)?.groupValues?.get(1).orEmpty()
            when (cmd) {
                "105" -> MockReply(cmd105)
                "103" -> MockReply(cmd103(lineName))
                else -> error("unexpected CMD=$cmd")
            }
        }
    )

    @Test
    fun stopLines_stationIdIsNotAnOrdinal_orderComesFromStopList() = runBlocking {
        val stop = client(cmd105("1路" to "899")) { cmd103("起点站", "人民路", "市政广场", "终点站") }
            .getStopLines("玉林", "市政广场")

        assertEquals(3, stop.lines.single().targetOrder)
    }

    @Test
    fun stopLines_stationIdMatchesStopList_isKept() = runBlocking {
        val stop = client(cmd105("1路" to "2")) { cmd103("起点站", "人民路", "市政广场", "终点站") }
            .getStopLines("玉林", "市政广场")

        assertEquals(3, stop.lines.single().targetOrder)
    }

    @Test
    fun stopLines_nameNotOnStopList_bogusOrderBecomesUnknown_validOrderKept() = runBlocking {
        val stop = client(cmd105("1路" to "899", "2路" to "1")) { cmd103("起点站", "人民路", "别的名字", "终点站") }
            .getStopLines("玉林", "市政广场")

        assertEquals(listOf(0, 2), stop.lines.map { it.targetOrder })
    }

    @Test
    fun stopLines_stopListUnavailable_keepsOriginalOrder() = runBlocking {
        val stop = client(cmd105("1路" to "899")) { """{"status":"0","msg":"error"}""" }
            .getStopLines("玉林", "市政广场")

        assertEquals(900, stop.lines.single().targetOrder)
    }
}
