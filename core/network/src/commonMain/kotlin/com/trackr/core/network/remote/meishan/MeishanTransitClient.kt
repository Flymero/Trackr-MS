package com.trackr.core.network.remote.meishan

import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.model.MeishanArea
import com.trackr.core.model.MeishanLine
import com.trackr.core.model.TransitProtocolFailure
import com.trackr.core.model.TransitServiceFailure
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

/** 业务码成功但缺少该接口的有效载荷（如只有 `{"code":101}`）：不是空数据 */
class MeishanPayloadMissingException(message: String) : IOException(message), TransitProtocolFailure

/** 眉山响应内容不是可解析的 JSON。 */
class MeishanResponseFormatException(message: String, cause: Throwable? = null) :
    IOException(message, cause), TransitProtocolFailure

/** 眉山 HTTP / 业务码失败；请求链路可用，但服务没有提供有效业务结果。 */
class MeishanServiceException(message: String) : IOException(message), TransitServiceFailure

/** 服务端返回 403 / 429：暂停请求一段时间，不换身份重试 */
class MeishanThrottledException(message: String) : IOException(message), TransitServiceFailure

/**
 * 眉山公交官方后台客户端（doc/勘误后的参考文档 主文 §3）。
 *
 * 数据来自原「巴士生活」2.7.1 客户端的公开接口，匿名可用；HTTP 明文，域名在
 * `network_security_config.xml` 白名单里。官方后台无长期支持承诺，调用方要容错。
 *
 * 缺失、null 和空数组分开处理：缺载荷抛 [MeishanPayloadMissingException]，空数组才是空数据。
 */
class MeishanTransitClient(
    httpClient: HttpClient,
    private val monotonicClock: MonotonicClock = ProcessMonotonicClock
) : BaseTransitApiClient(httpClient) {

    companion object {
        const val BASE_URL = "http://busapp.superms.cn:8182/msgj"

        /** 主文 §3.1 的公共参数：旧客户端版本号 */
        private const val M_VERSION = "2.7.1"
        private const val VERSION_CODE = "202401070"

        /** 主文 §3.1：业务成功 code == 101 */
        private const val CODE_SUCCESS = 101

        /** 403 / 429 后暂停请求的时长 */
        internal const val THROTTLE_PAUSE_MS = 60_000L

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"
    }

    /** 线路列表会话缓存（按区域）：搜索和线路页共用，列表不常变 */
    private val listLock = SynchronizedObject()
    private val lineListCache = HashMap<MeishanArea, List<MeishanLine>>()

    private val throttleLock = SynchronizedObject()
    private var pausedUntilMs: Long = Long.MIN_VALUE

    /**
     * 全量线路列表（H01）：每行一个线路记录；名称可能重复、起终点 / 时间字段可能缺失，
     * 按 [MeishanLine] 的可空字段容错。会话内缓存，[forceRefresh] 重新拉。缺 `result` 不写缓存。
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
        val data = request("线路列表", area) { meishanGet("/line/list", area, emptyMap()) }
        val result = data.optJSONArray("result")
            ?: throw MeishanPayloadMissingException("眉山线路列表缺少 result 数组")
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
                    area = MeishanArea.fromAreaId(item.optInt("areaId", area.areaId)),
                    state = item.intOrNull("state")
                )
            )
        }
        return lines
    }

    /**
     * 线路详情（H02）：POST /line/byId，`userId` 传空即可匿名。
     * `siteList` 按返回顺序是停靠序列；`busList` 是请求时刻的车辆快照。
     */
    suspend fun getLineDetail(lId: Int, state: Int, area: MeishanArea): JSONObject =
        withContext(Dispatchers.IO) {
            val data = request("线路详情", area) {
                meishanPost(
                    "/line/byId",
                    area,
                    mapOf(
                        "l_id" to lId.toString(),
                        "state" to state.toString(),
                        "userId" to ""
                    )
                )
            }
            data.optJSONObject("result")
                ?: throw MeishanPayloadMissingException("眉山线路详情缺少 result（l_id=$lId state=$state）")
        }

    /**
     * 附近站点（H04）：`dist` 固定传 0.6（单位未确认，返回值与公里吻合）；匿名不发 `u_id`。
     * 返回 siteInfos 原始 JSON 数组；缺少 siteInfos 视为载荷缺失。
     */
    suspend fun getNearbySites(area: MeishanArea, lat: Double, lon: Double): JSONArray =
        withContext(Dispatchers.IO) {
            val data = request("附近站点", area) {
                meishanPost(
                    "/find/siteAndRedPacket",
                    area,
                    mapOf(
                        "dist" to "0.6",
                        "lat" to lat.toString(),
                        "lon" to lon.toString()
                    )
                )
            }
            data.optJSONObject("result")?.optJSONArray("siteInfos")
                ?: throw MeishanPayloadMissingException("眉山附近站点缺少 result.siteInfos")
        }

    /**
     * 同站线路（H03）：POST /line/site，`id` 是站点 `s_id`。
     * 返回经过该站的线路，每条含 siteList 与 busList。空数组 = 站点无在营线路；缺 result 抛错。
     */
    suspend fun getStopLines(area: MeishanArea, sId: Int): JSONArray = withContext(Dispatchers.IO) {
        val data = request("同站线路", area) {
            meishanPost("/line/site", area, mapOf("id" to sId.toString()))
        }
        data.optJSONArray("result")
            ?: throw MeishanPayloadMissingException("眉山同站线路缺少 result 数组（s_id=$sId）")
    }

    /** 发请求并校验 HTTP 状态和业务码；403 / 429 后一段时间内直接失败 */
    private suspend fun request(what: String, area: MeishanArea, block: HttpRequestBuilder.() -> Unit): JSONObject {
        val now = monotonicClock.elapsedMillis()
        val pausedUntil = synchronized(throttleLock) { pausedUntilMs }
        if (now < pausedUntil) {
            throw MeishanThrottledException("眉山官方接口限流，暂停请求（$what）")
        }
        val response = executeHttpCall(block)
        if (response.code == 403 || response.code == 429) {
            synchronized(throttleLock) { pausedUntilMs = monotonicClock.elapsedMillis() + THROTTLE_PAUSE_MS }
            throw MeishanThrottledException("眉山$what HTTP ${response.code}，暂停请求（${response.safeUrl}）")
        }
        if (!response.isSuccessful) {
            throw MeishanServiceException("眉山$what HTTP ${response.code}（${response.safeUrl}，areaId=${area.areaId}）")
        }
        val data = try {
            JSONObject(response.body)
        } catch (e: Exception) {
            throw MeishanResponseFormatException("眉山$what 响应不是 JSON（${response.safeUrl}）", e)
        }
        val code = data.intOrNull("code")
        if (code != CODE_SUCCESS) {
            throw MeishanServiceException("眉山$what 业务失败 code=${code ?: "缺失"}（${response.safeUrl}）")
        }
        return data
    }

    /** GET [path]，query 带公共参数 areaId / m_version / versionCode */
    private fun HttpRequestBuilder.meishanGet(path: String, area: MeishanArea, params: Map<String, String>) {
        method = HttpMethod.Get
        url {
            takeFrom("$BASE_URL$path")
            for ((k, v) in params + commonParams(area)) {
                parameters.append(k, v)
            }
        }
        header("User-Agent", USER_AGENT)
    }

    /** POST [path]，表单带业务参数和公共参数（旧客户端 OkHttpManager 追加到表单） */
    private fun HttpRequestBuilder.meishanPost(path: String, area: MeishanArea, params: Map<String, String>) {
        method = HttpMethod.Post
        url { takeFrom("$BASE_URL$path") }
        header("User-Agent", USER_AGENT)
        setBody(FormDataContent(Parameters.build {
            for ((k, v) in params + commonParams(area)) {
                append(k, v)
            }
        }))
    }

    private fun commonParams(area: MeishanArea): Map<String, String> = mapOf(
        "areaId" to area.areaId.toString(),
        "m_version" to M_VERSION,
        "versionCode" to VERSION_CODE
    )
}

/** 原始数值字段：缺失、null、非数字返回 null，不用 0 冒充。
 *  数字语义与同文件 optInt（兼容层 longOrNull 截断）一致：小数字面量（5.0）截断取值，
 *  超 Int 范围的整数字面量不静默回绕；数值字符串保持原有容错。 */
internal fun JSONObject.intOrNull(key: String): Int? = when (val value = opt(key)) {
    is Int -> value
    is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}
