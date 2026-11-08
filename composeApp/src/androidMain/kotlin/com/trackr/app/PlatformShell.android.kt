package com.trackr.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.trackr.core.navigation.Navigator
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.navigation.ScreenKey
import com.trackr.ui.navigation.LocalAppViewModelStoreOwner
import com.trackr.ui.navigation.dropUnlessResumed
import com.trackr.feature.bus.home.BusHomeViewModel
import com.trackr.ui.screens.routeplan.ProvideAmapMapSession
import org.koin.compose.viewmodel.koinViewModel

/**
 * 始终包一层 [ProvideAmapMapSession]，`enabled` 交给它决定建不建地图会话。
 *
 * 不能写成 `if (enabled) ProvideAmapMapSession(content) else content()`：那样 consent 从 false 翻成 true 时，
 * `content` 的调用位置在两支之间切换，整棵 `TrackrNavApp` 子树被销毁重建，`rememberSaveable` 的返回栈丢失，
 * 「同意并继续」里的 `navigator.selectTab(RoutePlan)` 作用在旧实例上，用户实际停在上一个页面。
 */
@Composable
internal actual fun PlatformHost(enabled: Boolean, content: @Composable () -> Unit) {
    val activity = LocalActivity.current as? ComponentActivity
    val darkTheme = TrackrTheme.isDark
    LaunchedEffect(activity, darkTheme) {
        activity?.let {
            val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme }
            it.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.window.isNavigationBarContrastEnforced = false
            }
        }
    }
    ProvideAmapMapSession(enabled = enabled, content = content)
}

@Composable
internal actual fun bottomTabIcon(key: ScreenKey): ImageVector = when (key) {
    ScreenKey.Home -> Icons.Default.DirectionsBus
    ScreenKey.Lines -> Icons.Default.FormatListNumbered
    ScreenKey.RoutePlan -> Icons.AutoMirrored.Filled.AltRoute
    ScreenKey.More -> Icons.Default.Settings
    else -> error("$key 不是顶层页签")
}

/**
 * 申请定位与通知权限（原 MainActivity 里那段，原样搬来）。
 * 说明对话框和系统权限弹窗都关掉后才组合 [afterOnboarding]（字号提示放这里，不和它们叠在一起）。
 */
@Composable
internal actual fun PermissionOnboarding(afterOnboarding: @Composable () -> Unit) {
    // 首页 ViewModel 是 Activity 级的，和首页拿到的是同一个实例
    val homeViewModel: BusHomeViewModel = koinViewModel(viewModelStoreOwner = LocalAppViewModelStoreOwner.current)
    LocationPermissionOnboarding(
        onLocationGranted = homeViewModel::refreshLocationAndNearby,
        afterOnboarding = afterOnboarding
    )
}

/** [PermissionOnboarding] 去掉 ViewModel 的部分（测试直接用） */
@Composable
internal fun LocationPermissionOnboarding(
    onLocationGranted: () -> Unit,
    afterOnboarding: @Composable () -> Unit
) {
    val context = LocalContext.current

    // 已经发出、还没回来的系统权限申请：系统弹窗盖在上面，后面的对话框先不弹
    var awaitingPermissionResult by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        awaitingPermissionResult = false
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            onLocationGranted()
        }
    }
    val requestPermissions: (Array<String>) -> Unit = { permissions ->
        awaitingPermissionResult = true
        permissionLauncher.launch(permissions)
    }

    var showLocationDisclosureDialog by rememberSaveable { mutableStateOf(false) }
    // 下面的 LaunchedEffect 跑完前还不知道要不要弹说明：先别组合后面的对话框，免得闪一帧
    var checked by remember { mutableStateOf(false) }

    val needsLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    LaunchedEffect(Unit) {
        if (needsLocation) {
            showLocationDisclosureDialog = true
        } else if (needsNotification) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        checked = true
    }

    if (showLocationDisclosureDialog) {
        AlertDialog(
            onDismissRequest = {
                showLocationDisclosureDialog = false
                if (needsNotification) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "位置信息权限说明",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "为了向您展示附近的公交站点、候车站距离以及途经线路的实时到站动态，追客需要在您使用应用期间获取设备的大致或精准位置信息。\n\n我们承诺：位置信息仅在本地用于附近公交站点匹配，不会用于追踪个人身份或与任何第三方共享。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationDisclosureDialog = false
                        val needed = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (needsNotification) {
                            needed.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        requestPermissions(needed.toTypedArray())
                    }
                ) {
                    Text("同意并授权")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLocationDisclosureDialog = false
                        if (needsNotification) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }
                ) {
                    Text("暂不授权")
                }
            }
        )
    }

    if (checked && !showLocationDisclosureDialog && !awaitingPermissionResult) {
        afterOnboarding()
    }
}
