package com.trackr.core.network.remote.chelaile

import com.trackr.core.network.testing.mockHttpClient
import com.trackr.core.network.testing.mockResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 车来了个别线路上游只给 `lineNo`、`name` 给空串。
 *
 * 以前 `name` 原样带出去，线路号徽标画出来是个空块、收藏一条线之后首页只剩一个空白圆角块（issue #20）。
 * 现在解析时就用线路号顶上。
 */
class ChelaileLineNameFallbackTest {

    private val blankNameDetail = """
        {
          "jsonr": {
            "data": {
              "stationList": [
                {
                  "sn": "人民广场",
                  "sId": "stn_1",
                  "physicalStId": "phys_1",
                  "lat": 31.2304,
                  "lng": 121.4737,
                  "lines": [
                    {
                      "line": {
                        "lineId": "cl_line_blank",
                        "name": "",
                        "lineNo": "M200",
                        "direction": 0,
                        "startSn": "科技园",
                        "endSn": "人民广场"
                      },
                      "targetStation": { "order": 3, "sId": "stn_1", "sn": "人民广场" },
                      "stnStates": []
                    }
                  ]
                }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun stopDetail_whenLineNameIsBlank_fallsBackToLineNo() = runBlocking {
        val client = ChelaileApiClient(mockHttpClient { mockResponse(it, blankNameDetail) })

        val line = client.getStopDetail(cityId = "034", physicalStId = "phys_1")
            .single()
            .lines
            .single()
            .line

        assertEquals("M200", line.name)
        assertEquals("M200", line.lineNo)
    }

    @Test
    fun lineDetail_whenLineNameIsBlank_fallsBackToLineNo() = runBlocking {
        val body = """
            {
              "jsonr": {
                "data": {
                  "line": {
                    "lineId": "cl_line_blank",
                    "name": "",
                    "lineNo": "M200",
                    "direction": 0,
                    "startSn": "科技园",
                    "endSn": "人民广场"
                  },
                  "stops": []
                }
              }
            }
        """.trimIndent()
        val client = ChelaileApiClient(mockHttpClient { mockResponse(it, body) })

        val detail = client.getLineDetail(cityId = "034", lineId = "cl_line_blank")

        assertEquals("M200", detail.line.name)
    }
}
