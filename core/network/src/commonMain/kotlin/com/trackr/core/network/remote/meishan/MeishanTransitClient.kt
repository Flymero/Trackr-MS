package com.trackr.core.network.remote.meishan

import com.trackr.core.model.MeishanArea
import com.trackr.core.model.MeishanLine
import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.json.JSONObject
import com.trackr.core.network.remote.common.BaseTransitApiClient
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.takeFrom
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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

    /** 线路列表会话缓存（按区域）：搜索和线路页共用，列表不常变 */
    private val listLock = SynchronizedObject()
    private val lineListCache = HashMap<MeishanArea, List<MeishanLine>>()

    /**
     * 全量线路列表（契约 §4.2）：每行是一个线路记录而非方向，当前全部 state=0；
     * 名称可能重复、起终点 / 时间字段可能缺失，按 [MeishanLine] 的可空字段容错。
     * 会话内缓存，[forceRefresh] 重新拉。
     */
    suspend fun getLineList(area: MeishanArea, forceRefresh: Boolean = false): List<MeishanLine> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                synchronized(listLock) { lineListCache[area] }?.let { return@withContext it }
            }
            val lines = fetchLineList(area)
            synchronized(listLock) { lineListCache[area] = lines }
            lines
        }

    private suspend fun fetchLineList(area: MeishanArea): List<MeishanLine> {
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
        return lines
    }

    /**
     * 线路详情（契约 §4.3）：POST /line/byId，`userId` 传空即可匿名。
     * `siteList` 已按运营顺序返回；`busList` 是请求时刻的车辆快照（含车牌与速度）。
     */
    suspend fun getLineDetail(lId: Int, state: Int, area: MeishanArea): JSONObject =
        withContext(Dispatchers.IO) {
            val response = executeHttpCall {
                meishanPost(
                    "/line/byId",
                    mapOf(
                        "l_id" to lId.toString(),
                        "state" to state.toString(),
                        "userId" to "",
                        "areaId" to area.areaId.toString()
                    )
                )
            }
            if (!response.isSuccessful) {
                throw IOException("眉山线路详情 HTTP ${response.code}（${response.safeUrl}）")
            }
            val data = JSONObject(response.body)
            if (data.optInt("code", -1) != CODE_SUCCESS) {
                throw IOException("眉山线路详情业务失败 code=${data.optInt("code", -1)}（${response.safeUrl}）")
            }
            data.optJSONObject("result")
                ?: throw IOException("眉山线路详情无 result（l_id=$lId state=$state，${response.safeUrl}）")
        }

    /** 附近站点（doc/news 03 §3.4）：`dist` 单位公里；返回 siteInfos 原始 JSON 数组 */
    suspend fun getNearbySites(lat: Double, lon: Double, distKm: Double = 0.6): JSONArray =
        withContext(Dispatchers.IO) {
            val response = executeHttpCall {
                meishanPost(
                    "/find/siteAndRedPacket",
                    mapOf(
                        "dist" to distKm.toString(),
                        "lat" to lat.toString(),
                        "lon" to lon.toString(),
                        "u_id" to "0"
                    )
                )
            }
            if (!response.isSuccessful) {
                throw IOException("眉山附近站点 HTTP ${response.code}（${response.safeUrl}）")
            }
            val data = JSONObject(response.body)
            if (data.optInt("code", -1) != CODE_SUCCESS) {
                throw IOException("眉山附近站点业务失败 code=${data.optInt("code", -1)}（${response.safeUrl}）")
            }
            data.optJSONObject("result")?.optJSONArray("siteInfos") ?: JSONArray()
        }

    /**
     * 同站线路（doc/news 03 §3.3）：POST /line/site，`id` 是站点 `s_id`。
     * 返回经过该站的全部线路，每条含完整 siteList 与 busList（站数计算一次请求拿全）。
     * 返回 result 原始 JSON 数组（可能为空数组=站点无在营线路）。
     */
    suspend fun getStopLines(sId: Int): JSONArray = withContext(Dispatchers.IO) {
        val response = executeHttpCall {
            meishanPost("/line/site", mapOf("id" to sId.toString()))
        }
        if (!response.isSuccessful) {
            throw IOException("眉山同站线路 HTTP ${response.code}（${response.safeUrl}）")
        }
        val data = JSONObject(response.body)
        if (data.optInt("code", -1) != CODE_SUCCESS) {
            throw IOException("眉山同站线路业务失败 code=${data.optInt("code", -1)}（${response.safeUrl}）")
        }
        data.optJSONArray("result") ?: JSONArray()
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

    /** POST [path]，表单编码带旧客户端公共参数（契约 §4.1） */
    private fun HttpRequestBuilder.meishanPost(path: String, params: Map<String, String>) {
        method = HttpMethod.Post
        url {
            takeFrom("$BASE_URL$path")
            parameters.append("m_version", M_VERSION)
            parameters.append("versionCode", VERSION_CODE)
        }
        header("User-Agent", USER_AGENT)
        setBody(FormDataContent(Parameters.build {
            for ((k, v) in params) {
                append(k, v)
            }
        }))
    }
}
