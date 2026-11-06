package com.trackr.core.network.remote.common

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.model.TransitProtocolFailure
import com.trackr.core.model.TransitServiceFailure
import com.trackr.core.model.sanitizePlateOrFleetNo
import com.trackr.core.network.json.JSONObject
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

private class ResponseProtocolException(message: String, cause: Throwable? = null) :
    IOException(message, cause), TransitProtocolFailure

private class ResponseServiceException(message: String) : IOException(message), TransitServiceFailure

/**
 * 实时公交 API 客户端抽象基类。
 * 封装 Ktor 请求、响应转 JSON、纯地铁站点过滤、测距与车牌清洗等通用算法。
 */
abstract class BaseTransitApiClient(
    protected val httpClient: HttpClient
) {

    /** 一次请求的结果：状态码 + 响应文本 + 去掉 query 的地址（只用于异常信息） */
    protected class TextResponse(val code: Int, val body: String, val safeUrl: String) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    /**
     * 发一次请求并读出响应文本。协程取消时底层请求一起取消（Ktor 自带）。
     *
     * 网络层抛出的异常（超时、连不上等）统一换成只带 scheme + host + path 的 [IOException]：
     * Ktor 的超时异常消息里带完整 URL，会把 key 等 query 参数写进日志。
     */
    protected open suspend fun executeHttpCall(block: HttpRequestBuilder.() -> Unit): TextResponse {
        val builder = HttpRequestBuilder().apply(block)
        val target = safeUrl(builder.url.build())
        return try {
            val response = httpClient.request(builder)
            TextResponse(response.status.value, response.bodyAsText(), target)
        } catch (ce: CancellationException) {
            // 自己被取消：照常往上抛；别处来的取消（如引擎内部超时）按网络错误处理
            currentCoroutineContext().ensureActive()
            throw IOException("HTTP request cancelled for $target")
        } catch (e: Exception) {
            throw NetworkErrors.sanitize(e, target)
        }
    }

    /**
     * 统一读取 response body、校验状态码并安全解析为 [JSONObject]。
     * 异常信息只带 scheme + host + path，不带 query（避免把 key 等敏感参数写进日志）。
     */
    protected open suspend fun executeJson(block: HttpRequestBuilder.() -> Unit): JSONObject {
        val response = executeHttpCall(block)
        return withContext(Dispatchers.IO) {
            val target = response.safeUrl
            if (!response.isSuccessful) {
                throw ResponseServiceException("HTTP ${response.code} error for $target")
            }
            val bodyStr = response.body.ifBlank { throw ResponseProtocolException("Empty response body from $target") }
            try {
                JSONObject(bodyStr)
            } catch (e: Exception) {
                throw ResponseProtocolException("Malformed JSON response from $target", e)
            }
        }
    }

    /**
     * 去掉 query / fragment / 用户信息后的 URL，仅用于异常与日志
     */
    protected fun safeUrl(url: Url): String = NetworkErrors.safeUrl(url)

    /**
     * 统一识别站点名称是否代表纯地铁站（如包含“（地铁站）”、“(地铁站)”、“（M）”、“(M)”等特征）。
     */
    protected open fun isSubwayStationName(stationName: String): Boolean {
        val trimmed = stationName.trim()
        return trimmed.endsWith("（M）", ignoreCase = true) ||
                trimmed.endsWith("(M)", ignoreCase = true) ||
                trimmed.contains("（地铁站）") ||
                trimmed.contains("(地铁站)")
    }

    /**
     * 统一清洗地铁相关后缀。
     */
    protected open fun cleanSubwaySuffix(stationName: String): String {
        return stationName.trim()
            .removeSuffix("（地铁站）")
            .removeSuffix("(地铁站)")
            .removeSuffix("（M）")
            .removeSuffix("(M)")
            .trim()
    }

    /**
     * 统一服务端距离优先、无则自动通过 [RouteProjectionMath.calculateDistanceMeters] 补全的计算逻辑。
     */
    protected open fun resolveDistance(
        serverDistance: Int?,
        userLat: Double?,
        userLng: Double?,
        stationLat: Double,
        stationLng: Double
    ): Int? {
        if (serverDistance != null && serverDistance > 0) {
            return serverDistance
        }
        if (userLat != null && userLng != null && userLat > 0.0 && userLng > 0.0 && stationLat > 0.0 && stationLng > 0.0) {
            return RouteProjectionMath.calculateDistanceMeters(userLat, userLng, stationLat, stationLng)
        }
        return null
    }

    /**
     * 统一清洗车牌号与自编号，去除机器哈希与无效数据。
     */
    protected open fun sanitizeBusPlate(raw: String?): String {
        return sanitizePlateOrFleetNo(raw)
    }
}
