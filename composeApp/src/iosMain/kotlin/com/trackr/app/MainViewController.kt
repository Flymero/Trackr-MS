package com.trackr.app

import androidx.compose.foundation.layout.*
import platform.Foundation.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.trackr.app.di.IosAppRuntime
import com.trackr.app.di.trackrModules
import com.trackr.app.platform.IosTransitCodeLauncher
import com.trackr.core.navigation.deeplink.DeepLink
import com.trackr.core.navigation.deeplink.DeepLinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration

private val pendingLink = MutableStateFlow<String?>(null)

fun handleOpenUrl(url: String) {
    pendingLink.value = url
}

fun MainViewController() = ComposeUIViewController {
    var runtime by remember { mutableStateOf<IosAppRuntime?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        try {
            runtime = IosAppRuntime.open()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            println("iOS startup failed: ${error.message}")
            failed = true
        }
    }
    val app = runtime
    if (app == null) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(24.dp),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (failed) {
                        Text("暂时无法打开本地数据，请重试")
                        Button(onClick = { attempt++ }) { Text("重试") }
                    } else {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("正在打开追客")
                    }
                }
            }
        }
    } else {
        DisposableEffect(app) { onDispose { app.close() } }
        val configuration = remember(app) { koinConfiguration { modules(app.module, *trackrModules.toTypedArray()) } }
        KoinApplication(configuration = configuration) {
            val rawLink by pendingLink.collectAsState()
            val link = remember(rawLink) {
                val raw = rawLink
                val resolved = if (raw?.startsWith("trackr://favorite/") == true) {
                    val decoded = NSURLComponents.componentsWithString(raw)?.path?.removePrefix("/")
                    decoded?.takeIf { it.length <= 256 }?.let(app.resolveFavorite)
                } else raw
                DeepLinkParser.parse(resolved)
            }
            LaunchedEffect(rawLink) { if (rawLink != null && link == null) pendingLink.value = null }
            LaunchedEffect(link) {
                (link as? DeepLink.TransitCode)?.let {
                    IosTransitCodeLauncher.open(it.provider)
                    pendingLink.value = null
                }
            }
            TrackrRoot(deepLink = link?.takeUnless { it is DeepLink.TransitCode },
                onDeepLinkConsumed = { pendingLink.value = null })
        }
    }
}
