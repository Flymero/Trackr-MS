package com.trackr.ui.screens.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.domain.platform.AppUpdateInstaller
import com.trackr.core.domain.platform.UpdateChecker
import com.trackr.core.model.AppUpdateInfo
import com.trackr.core.model.AppUpdateInstallState
import com.trackr.core.model.UpdateCheckResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 关于页版本更新 UI 状态
 */
sealed interface AboutUpdateUiState {
    data object Idle : AboutUpdateUiState
    data object Checking : AboutUpdateUiState
    data class HasUpdate(val updateInfo: AppUpdateInfo, val isForce: Boolean) : AboutUpdateUiState
    data class UpToDate(val currentVersionName: String) : AboutUpdateUiState
    data class Error(val message: String) : AboutUpdateUiState
}

/**
 * @param canCheckUpdates 有没有应用内「检查更新」：只有 Android 有（§2.9，iOS 走 App Store）
 * @param showLiveUpdateNote 显示「实时通知支持哪些系统」那段说明（只对 Android 有意义，跟 [canCheckUpdates] 同一个平台条件）
 * @param install 应用内下载安装的进度；iOS 不会变化
 */
data class AboutUiState(
    val versionName: String = "",
    val canCheckUpdates: Boolean = false,
    val showLiveUpdateNote: Boolean = false,
    val update: AboutUpdateUiState = AboutUpdateUiState.Idle,
    val install: AppUpdateInstallState = AppUpdateInstallState.Idle
)

sealed interface AboutEvent {
    data object CheckForUpdates : AboutEvent
    data object DismissUpdateDialog : AboutEvent
    data object Back : AboutEvent

    /** 下载安装包并调系统安装器 */
    data object StartInstall : AboutEvent

    /** 取消下载；已经下了一半的文件删掉 */
    data object CancelInstall : AboutEvent

    /** 打开系统的「安装未知应用」授权页 */
    data object OpenInstallSettings : AboutEvent

    /** 已经下载好时再调一次系统安装器（不重新下载） */
    data object RetryInstall : AboutEvent

    /** 从授权页回来：只在真的缺权限时自动接着装，避免从系统安装界面返回又立刻弹一次 */
    data object ResumeInstall : AboutEvent
}

/** 项目主页 */
const val PROJECT_URL: String = "https://github.com/5151561/Trackr"

/**
 * @param updateChecker 应用内更新检查；平台没有（iOS）时为 null，页面不显示「检查更新」
 * @param updateInstaller 应用内下载安装；平台没有（iOS）时为 null，更新弹窗只给「下载」按钮
 * @param versionProvider 当前安装包的版本号（code to name）；Koin 里由 `AppVersionInfo` 给出
 *   （Android：androidApp 的 `androidAppVersion(context)`，即原 `getDefaultAppVersion`）
 */
class AboutViewModel(
    private val updateChecker: UpdateChecker?,
    private val updateInstaller: AppUpdateInstaller? = null,
    private val versionProvider: () -> Pair<Int, String> = { 1 to "0.0.1" }
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AboutUiState(
            versionName = versionProvider().second,
            // 眉山特化：暂时关闭应用内「检查更新」——更新通道指向原版仓库的 release，
            // 对这个 fork 无意义（会把用户引回原版包），等有自己的发布通道再开
            canCheckUpdates = false,
            showLiveUpdateNote = updateChecker != null
        )
    )
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    private var installJob: Job? = null

    /** 上一次写进状态里的下载百分比；-1 表示还没写过。用来少发几次状态，别让 Compose 每 64 KB 重组一次 */
    private var lastProgressPercent: Int = -1
    private var lastProgressBytes: Long = 0L

    fun onEvent(event: AboutEvent) {
        when (event) {
            AboutEvent.CheckForUpdates -> checkForUpdates()
            AboutEvent.DismissUpdateDialog -> dismissUpdateDialog()
            AboutEvent.Back -> Unit
            AboutEvent.StartInstall -> startInstall()
            AboutEvent.CancelInstall -> cancelInstall()
            AboutEvent.OpenInstallSettings -> updateInstaller?.openInstallPermissionSettings()
            AboutEvent.RetryInstall -> retryInstall()
            AboutEvent.ResumeInstall -> resumeInstallAfterPermission()
        }
    }

    fun checkForUpdates() {
        val checker = updateChecker ?: return
        if (_uiState.value.update is AboutUpdateUiState.Checking) return
        setUpdate(AboutUpdateUiState.Checking)

        viewModelScope.launch {
            val (currentVersionCode, currentVersionName) = versionProvider()
            val result = checker.checkForUpdates(
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName
            )

            setUpdate(
                when (result) {
                    is UpdateCheckResult.HasUpdate ->
                        AboutUpdateUiState.HasUpdate(result.updateInfo, result.isForceUpdate)
                    is UpdateCheckResult.UpToDate ->
                        AboutUpdateUiState.UpToDate(result.currentVersionName)
                    is UpdateCheckResult.Error ->
                        AboutUpdateUiState.Error(result.message)
                }
            )
        }
    }

    fun dismissUpdateDialog() {
        setUpdate(AboutUpdateUiState.Idle)
    }

    /**
     * 下载并安装。没有「安装未知应用」权限时停在 [AppUpdateInstallState.AwaitingPermission]，
     * 不会自己跳设置页。
     */
    fun startInstall() {
        val installer = updateInstaller ?: return
        val updateInfo = (_uiState.value.update as? AboutUpdateUiState.HasUpdate)?.updateInfo ?: return
        if (installJob?.isActive == true) return

        lastProgressPercent = -1
        lastProgressBytes = 0L
        setInstall(AppUpdateInstallState.Downloading(0, 0))
        installJob = viewModelScope.launch {
            val result = installer.downloadAndInstall(updateInfo) { downloaded, total ->
                publishProgress(downloaded, total)
            }
            setInstall(result)
        }
    }

    /** 取消下载，回到「发现新版本」 */
    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        updateInstaller?.discardPending()
        setInstall(AppUpdateInstallState.Idle)
    }

    /** 从设置页回来 / 再点一次安装：有下好的包就接着装，没给权限就还停在原状态 */
    fun retryInstall() {
        val installer = updateInstaller ?: return
        val current = _uiState.value.install
        if (current !is AppUpdateInstallState.AwaitingPermission && current !is AppUpdateInstallState.Launched) return
        setInstall(installer.retryPendingInstall())
    }

    /** 页面回到前台：只有卡在「等授权」时才自动继续 */
    fun resumeInstallAfterPermission() {
        if (_uiState.value.install !is AppUpdateInstallState.AwaitingPermission) return
        retryInstall()
    }

    private fun publishProgress(downloadedBytes: Long, totalBytes: Long) {
        if (totalBytes > 0) {
            val percent = ((downloadedBytes * 100) / totalBytes).toInt()
            if (percent == lastProgressPercent) return
            lastProgressPercent = percent
        } else {
            // 服务器没给总长度：每 512 KB 报一次
            if (downloadedBytes - lastProgressBytes < PROGRESS_STEP_BYTES) return
            lastProgressBytes = downloadedBytes
        }
        setInstall(AppUpdateInstallState.Downloading(downloadedBytes, totalBytes))
    }

    override fun onCleared() {
        installJob?.cancel()
        installJob = null
        // 离开「关于」页就不再留着几十兆的安装包
        updateInstaller?.discardPending()
        super.onCleared()
    }

    private fun setUpdate(update: AboutUpdateUiState) {
        _uiState.update { it.copy(update = update) }
    }

    private fun setInstall(install: AppUpdateInstallState) {
        _uiState.update { it.copy(install = install) }
    }

    private companion object {
        const val PROGRESS_STEP_BYTES = 512L * 1024L
    }
}
