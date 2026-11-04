package com.trackr.ui.screens.me

import com.trackr.core.model.AppUpdateInfo
import com.trackr.core.model.AppUpdateInstallState
import com.trackr.core.model.UpdateCheckResult
import com.trackr.core.testing.FakeAppUpdateInstaller
import com.trackr.core.testing.FakeUpdateChecker
import com.trackr.core.testing.MainDispatcherHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {

    private val main = MainDispatcherHolder()

    @BeforeTest
    fun setup() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.reset()
    }

    @Test
    fun initialState_isIdle() {
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.UpToDate("0.0.2"))
        val viewModel = AboutViewModel(
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        assertEquals(AboutUpdateUiState.Idle, viewModel.uiState.value.update)
        // 眉山特化：检查更新暂时关闭（更新通道指向原版仓库），有 checker 也不显示这一行
        assertFalse(viewModel.uiState.value.canCheckUpdates)
        assertTrue(viewModel.uiState.value.showLiveUpdateNote)
        assertEquals("0.0.2", viewModel.uiState.value.versionName)
    }

    @Test
    fun noUpdateChecker_hidesCheckUpdates_andCheckDoesNothing() = runTest(main.dispatcher) {
        // iOS 没有应用内更新（§2.9）：不显示「检查更新」，调了也不变
        val viewModel = AboutViewModel(updateChecker = null, versionProvider = { 2 to "0.0.2" })

        assertFalse(viewModel.uiState.value.canCheckUpdates)
        assertFalse(viewModel.uiState.value.showLiveUpdateNote)
        viewModel.onEvent(AboutEvent.CheckForUpdates)
        advanceUntilIdle()
        assertEquals(AboutUpdateUiState.Idle, viewModel.uiState.value.update)
    }

    @Test
    fun checkForUpdates_whileChecking_doesNotStartSecondCheck() = runTest(main.dispatcher) {
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.UpToDate("0.0.2"))
        val viewModel = AboutViewModel(updateChecker = fakeChecker, versionProvider = { 2 to "0.0.2" })

        viewModel.onEvent(AboutEvent.CheckForUpdates)
        viewModel.onEvent(AboutEvent.CheckForUpdates)
        advanceUntilIdle()

        assertEquals(1, fakeChecker.checkCount)
    }

    @Test
    fun checkForUpdates_whenHasUpdate_updatesStateToHasUpdate() = runTest(main.dispatcher) {
        val updateInfo = AppUpdateInfo(
            versionCode = 3,
            versionName = "0.0.3",
            changelog = "全新发布",
            downloadUrl = "https://github.com/5151561/Trackr/releases",
            forceUpdate = false
        )
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.HasUpdate(updateInfo, isForceUpdate = false))
        val viewModel = AboutViewModel(
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()

        val state = viewModel.uiState.value.update
        assertTrue(state is AboutUpdateUiState.HasUpdate)
        val hasUpdate = state as AboutUpdateUiState.HasUpdate
        assertEquals("0.0.3", hasUpdate.updateInfo.versionName)
        assertEquals(false, hasUpdate.isForce)
        assertEquals(1, fakeChecker.checkCount)
    }

    @Test
    fun checkForUpdates_whenUpToDate_updatesStateToUpToDate() = runTest(main.dispatcher) {
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.UpToDate("0.0.2"))
        val viewModel = AboutViewModel(
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()

        val state = viewModel.uiState.value.update
        assertTrue(state is AboutUpdateUiState.UpToDate)
        assertEquals("0.0.2", (state as AboutUpdateUiState.UpToDate).currentVersionName)
    }

    @Test
    fun checkForUpdates_whenError_updatesStateToError() = runTest(main.dispatcher) {
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.Error("网络连接超时"))
        val viewModel = AboutViewModel(
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()

        val state = viewModel.uiState.value.update
        assertTrue(state is AboutUpdateUiState.Error)
        assertEquals("网络连接超时", (state as AboutUpdateUiState.Error).message)
    }

    @Test
    fun dismissUpdateDialog_resetsStateToIdle() = runTest(main.dispatcher) {
        val fakeChecker = FakeUpdateChecker(UpdateCheckResult.UpToDate("0.0.2"))
        val viewModel = AboutViewModel(
            updateChecker = fakeChecker,
            versionProvider = { 2 to "0.0.2" }
        )

        viewModel.checkForUpdates()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.update is AboutUpdateUiState.UpToDate)

        viewModel.dismissUpdateDialog()
        assertEquals(AboutUpdateUiState.Idle, viewModel.uiState.value.update)
    }

    @Test
    fun startInstall_downloadsAndLaunchesSystemInstaller() = runTest(main.dispatcher) {
        val installer = FakeAppUpdateInstaller(downloadResult = AppUpdateInstallState.Launched)
        val viewModel = viewModelWithUpdate(installer)

        viewModel.startInstall()
        advanceUntilIdle()

        assertEquals(AppUpdateInstallState.Launched, viewModel.uiState.value.install)
        assertEquals(1, installer.downloadCount)
    }

    @Test
    fun startInstall_reportsProgress_whileDownloading() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val installer = FakeAppUpdateInstaller(
            downloadResult = AppUpdateInstallState.AwaitingPermission,
            progress = listOf(0L to 100L, 25L to 100L, 100L to 100L)
        ).apply { downloadGate = gate }
        val viewModel = viewModelWithUpdate(installer)

        viewModel.startInstall()
        advanceUntilIdle()

        // 下载还没结束：界面拿到进度和总长度
        assertEquals(AppUpdateInstallState.Downloading(100L, 100L), viewModel.uiState.value.install)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(AppUpdateInstallState.AwaitingPermission, viewModel.uiState.value.install)
    }

    @Test
    fun cancelInstall_stopsDownload_andDeletesPackage() = runTest(main.dispatcher) {
        val installer = FakeAppUpdateInstaller(progress = listOf(10L to 100L)).apply {
            downloadGate = CompletableDeferred()
        }
        val viewModel = viewModelWithUpdate(installer)

        viewModel.startInstall()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.install is AppUpdateInstallState.Downloading)

        viewModel.onEvent(AboutEvent.CancelInstall)
        advanceUntilIdle()

        assertEquals(AppUpdateInstallState.Idle, viewModel.uiState.value.install)
        assertEquals(1, installer.discardCount)
        // 「发现新版本」弹窗还在，用户可以重新点下载
        assertTrue(viewModel.uiState.value.update is AboutUpdateUiState.HasUpdate)
    }

    @Test
    fun retryInstall_afterPermissionGranted_installsWithoutDownloadingAgain() = runTest(main.dispatcher) {
        val installer = FakeAppUpdateInstaller(
            downloadResult = AppUpdateInstallState.AwaitingPermission,
            retryResult = AppUpdateInstallState.Launched
        )
        val viewModel = viewModelWithUpdate(installer)

        viewModel.startInstall()
        advanceUntilIdle()
        assertEquals(AppUpdateInstallState.AwaitingPermission, viewModel.uiState.value.install)

        // 页面 resume（从设置页回来）：权限给了就接着装
        viewModel.onEvent(AboutEvent.ResumeInstall)
        advanceUntilIdle()

        assertEquals(AppUpdateInstallState.Launched, viewModel.uiState.value.install)
        assertEquals(1, installer.retryCount)
        assertEquals(1, installer.downloadCount)
    }

    @Test
    fun resumeInstall_whenInstallerAlreadyOpened_doesNotReopenIt() = runTest(main.dispatcher) {
        // 从系统安装界面返回时不能又弹一次安装界面，否则用户出不去
        val installer = FakeAppUpdateInstaller(downloadResult = AppUpdateInstallState.Launched)
        val viewModel = viewModelWithUpdate(installer)

        viewModel.startInstall()
        advanceUntilIdle()
        viewModel.onEvent(AboutEvent.ResumeInstall)
        advanceUntilIdle()

        assertEquals(0, installer.retryCount)
    }

    @Test
    fun retryInstall_whenIdle_doesNothing() = runTest(main.dispatcher) {
        val installer = FakeAppUpdateInstaller()
        val viewModel = viewModelWithUpdate(installer)

        viewModel.onEvent(AboutEvent.RetryInstall)
        advanceUntilIdle()

        assertEquals(AppUpdateInstallState.Idle, viewModel.uiState.value.install)
        assertEquals(0, installer.retryCount)
    }

    @Test
    fun startInstall_whenDownloadFails_showsMessage() = runTest(main.dispatcher) {
        val installer = FakeAppUpdateInstaller(
            downloadResult = AppUpdateInstallState.Failed("下载失败（HTTP 404）")
        )
        val viewModel = viewModelWithUpdate(installer)

        viewModel.startInstall()
        advanceUntilIdle()

        assertEquals(AppUpdateInstallState.Failed("下载失败（HTTP 404）"), viewModel.uiState.value.install)
    }

    @Test
    fun openInstallSettings_asksPlatform() = runTest(main.dispatcher) {
        val installer = FakeAppUpdateInstaller()
        val viewModel = viewModelWithUpdate(installer)

        viewModel.onEvent(AboutEvent.OpenInstallSettings)

        assertEquals(1, installer.openSettingsCount)
    }

    @Test
    fun startInstall_withoutInstaller_doesNothing() = runTest(main.dispatcher) {
        // iOS：没有安装器，点了也不崩
        val viewModel = AboutViewModel(
            updateChecker = FakeUpdateChecker(UpdateCheckResult.UpToDate("0.0.2")),
            updateInstaller = null,
            versionProvider = { 2 to "0.0.2" }
        )

        viewModel.onEvent(AboutEvent.StartInstall)
        advanceUntilIdle()

        assertEquals(AppUpdateInstallState.Idle, viewModel.uiState.value.install)
    }

    /** 已经检查到新版本的 ViewModel */
    private fun TestScope.viewModelWithUpdate(installer: FakeAppUpdateInstaller): AboutViewModel {
        val updateInfo = AppUpdateInfo(
            versionCode = 3,
            versionName = "0.0.3",
            changelog = "修了下载",
            downloadUrl = "https://github.com/5151561/Trackr/releases/download/v0.0.3/trackr-0.0.3-arm64-v8a.apk"
        )
        val viewModel = AboutViewModel(
            updateChecker = FakeUpdateChecker(UpdateCheckResult.HasUpdate(updateInfo, isForceUpdate = false)),
            updateInstaller = installer,
            versionProvider = { 2 to "0.0.2" }
        )
        viewModel.checkForUpdates()
        advanceUntilIdle()
        return viewModel
    }
}
