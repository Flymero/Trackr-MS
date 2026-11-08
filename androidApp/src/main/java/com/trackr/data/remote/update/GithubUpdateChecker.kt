package com.trackr.data.remote.update

import android.os.Build
import com.trackr.core.domain.platform.UpdateChecker
import com.trackr.core.model.AppUpdateInfo
import com.trackr.core.model.UpdateCheckResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.http.parseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 基于 GitHub / CDN 的应用更新检测器（只在 Android，重构计划 §2.9）
 */
class GithubUpdateChecker(
    private val httpClient: HttpClient,
    private val endpointUrls: List<String> = DEFAULT_ENDPOINTS,
    /** 当前设备支持的 ABI，按优先级排列；用在高德原生库按 ABI 分包后挑对应安装包 */
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }
) : UpdateChecker {

    companion object {
        val DEFAULT_ENDPOINTS = listOf(
            "https://raw.githubusercontent.com/5151561/Trackr/update/update.json",
            "https://fastly.jsdelivr.net/gh/5151561/Trackr@update/update.json",
            "https://cdn.jsdelivr.net/gh/5151561/Trackr@update/update.json"
        )

        /** 更新包下载地址允许的域名白名单（精确匹配，不含子域名） */
        val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "raw.githubusercontent.com",
            "cdn.jsdelivr.net",
            "fastly.jsdelivr.net"
        )

        /**
         * 校验下载地址：必须是 https，且 host 在 [ALLOWED_DOWNLOAD_HOSTS] 白名单内
         */
        fun isAllowedDownloadUrl(url: String): Boolean {
            val trimmed = url.trim()
            // Ktor 解析不带协议的串时会默认补 http；这里要求原串明确以 https:// 开头
            if (!trimmed.startsWith("https://", ignoreCase = true)) return false
            val parsed = parseUrl(trimmed) ?: return false
            return parsed.protocol == URLProtocol.HTTPS && parsed.host.lowercase() in ALLOWED_DOWNLOAD_HOSTS
        }
    }

    override suspend fun checkForUpdates(
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        // 各源的缓存可能不同步；不能把第一个有效响应当成最新版本（#36）。
        // 并发查询避免不可达的源逐个累加超时；同版本保留 endpointUrls 中靠前的源。
        val results = endpointUrls.map { url ->
            async {
                try {
                    Result.success(fetchUpdateInfo(url, currentVersionName))
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Result.failure<AppUpdateInfo>(e)
                }
            }
        }.awaitAll()
        val newest = results.mapNotNull { it.getOrNull() }.maxByOrNull { it.versionCode }

        if (newest != null) {
            return@withContext if (newest.versionCode > currentVersionCode) {
                UpdateCheckResult.HasUpdate(
                    updateInfo = newest,
                    isForceUpdate = newest.forceUpdate || currentVersionCode < newest.minSupportedVersionCode
                )
            } else {
                UpdateCheckResult.UpToDate(currentVersionName)
            }
        }

        val lastException = results.lastOrNull()?.exceptionOrNull()
        UpdateCheckResult.Error(
            message = lastException?.localizedMessage ?: "未能连接到更新服务器，请检查网络后重试",
            cause = lastException
        )
    }

    private suspend fun fetchUpdateInfo(url: String, currentVersionName: String): AppUpdateInfo {
        val response = httpClient.get(url) {
            header("User-Agent", "Trackr-Android-App/$currentVersionName")
            header("Cache-Control", "no-cache")
        }
        if (!response.status.isSuccess()) {
            throw IOException("HTTP ${response.status.value}: ${response.status.description}")
        }

        val bodyString = response.bodyAsText()
        if (bodyString.isBlank()) {
            throw IOException("Empty response body from $url")
        }

        val updateInfo = json.decodeFromString<AppUpdateInfo>(bodyString)
        // 按 ABI 选包；细分地址不合规时回退到通用地址，两者均不合规则排除该源。
        val pickedUrl = updateInfo.pickDownloadUrl(supportedAbis)
        val downloadUrl = when {
            isAllowedDownloadUrl(pickedUrl) -> pickedUrl
            isAllowedDownloadUrl(updateInfo.downloadUrl) -> updateInfo.downloadUrl
            else -> throw IOException("更新下载地址不合规：$pickedUrl")
        }
        return updateInfo.copy(downloadUrl = downloadUrl)
    }
}
