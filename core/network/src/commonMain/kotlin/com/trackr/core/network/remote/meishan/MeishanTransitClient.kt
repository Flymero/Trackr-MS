package com.trackr.core.network.remote.meishan

import com.trackr.core.model.MeishanArea
import com.trackr.core.model.MeishanLine
import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.json.JSONObject
import com.trackr.core.network.remote.common.BaseTransitApiClient
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.takeFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

/**
 * 眉山公交官方后台客户端（doc/眉山公交数据链路实测与重写契约 §4）。
 *
 * 数据来自原「巴士生活」2.7.1 客户端的公开接口，匿名可用；HTTP 明文，域名在
 * `network_security_config.xml` 白名单里。官方后台无长期支持承诺，调用方要容错。
 */
class MeishanTransitClient(
    httpClient: HttpClient
) : BaseTransitApiClient(httpClient) {

    companion object {
        const val BASE_URL = "http://busapp.superms.cn:8182/msgj"

        /** 契约 §4.1 的公共参数：旧客户端版本号 */
        private const val M_VERSION = "2.7.1"
        private const val VERSION_CODE = "202401070"

        /** 契约 §4.1：业务成功 code == 101 */
        private const val CODE_SUCCESS = 101

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"
    }

    /**
     * 全量线路列表（契约 §4.2）：每行是一个线路记录而非方向，当前全部 state=0；
     * 名称可能重复、起终点 / 时间字段可能缺失，按 [MeishanLine] 的可空字段容错。
     */
    suspend fun getLineList(area: MeishanArea): List<MeishanLine> = withContext(Dispatchers.IO) {
        val response = executeHttpCall { meishanGet("/line/list", mapOf("areaId" to area.areaId.toString())) }
        if (!response.isSuccessful) {
            throw IOException("眉山线路列表 HTTP ${response.code}（${response.safeUrl}）")
        }
        val data = JSONObject(response.body)
        if (data.optInt("code", -1) != CODE_SUCCESS) {
            throw IOException("眉山线路列表业务失败 code=${data.optInt("code", -1)}（${response.safeUrl}）")
        }
        val result: JSONArray = data.optJSONArray("result") ?: JSONArray()
        val lines = ArrayList<MeishanLine>(result.length())
        for (i in 0 until result.length()) {
            val item = result.optJSONObject(i) ?: continue
            val lId = item.optInt("l_id", -1)
            val name = item.optString("l_name").trim()
            if (lId < 0 || name.isBlank()) continue
            lines.add(
                MeishanLine(
                    lId = lId,
                    name = name,
                    forwardText = item.optString("l_sxpz").trim().ifBlank { null },
                    backwardText = item.optString("l_xxpz").trim().ifBlank { null },
                    copyId = item.optInt("l_copy_id", 0),
                    startTime = item.optString("start_time").trim().ifBlank { null },
                    endTime = item.optString("end_time").trim().ifBlank { null },
                    backwardStartTime = item.optString("xx_start_time").trim().ifBlank { null },
                    backwardEndTime = item.optString("xx_end_time").trim().ifBlank { null },
                    area = MeishanArea.fromAreaId(item.optInt("areaId", area.areaId))
                )
            )
        }
        lines
    }

    /** GET [path]，query 带旧客户端公共参数（契约 §4.1） */
    private fun HttpRequestBuilder.meishanGet(path: String, params: Map<String, String>) {
        method = HttpMethod.Get
        url {
            takeFrom("$BASE_URL$path")
            parameters.append("m_version", M_VERSION)
            parameters.append("versionCode", VERSION_CODE)
            for ((k, v) in params) {
                parameters.append(k, v)
            }
        }
        header("User-Agent", USER_AGENT)
    }
}
