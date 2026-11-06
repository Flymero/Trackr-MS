package com.trackr.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import com.trackr.core.navigation.deeplink.DeepLink

/**
 * 长辈版的页面范围（§8.4）：没有底部页签和地图规划，只有一条栈，根页是首页。
 */
object ElderRoutes {

    /** 长辈版唯一一条栈的根页（和标准版首页同一个 Key，第 32 步起放长辈版页面） */
    val root: ScreenKey = ScreenKey.Home

    /**
     * 长辈版不支持的页面：地图规划、编辑收藏、编辑提醒（§8.4），外加早已删掉页面、只剩 Key 的旧收藏页。
     * 打开它们一律落到首页。
     */
    fun isSupported(key: ScreenKey): Boolean = when (key) {
        ScreenKey.RoutePlan,
        ScreenKey.FavoritesEdit,
        is ScreenKey.ReminderEdit,
        ScreenKey.FavoritesGroup -> false
        else -> true
    }

    /** 不支持的 Key 换成首页，其余原样 */
    fun map(key: ScreenKey): ScreenKey = if (isSupported(key)) key else root
}

/**
 * 长辈版导航（§8.4，Q9）：一条栈，栈底总是 [ElderRoutes.root]。纯逻辑，`ElderNavigatorTest` 直接测。
 *
 * - 返回：有子页就退一页；在首页返回 false，交给系统退出（单栈天然 exit-through-home）。
 * - 不支持的页面（[ElderRoutes.isSupported]）不压栈：退回首页，并亮起 [unsupportedNotice]，
 *   由壳显示「长辈版不支持此功能」，用户点「知道了」才关（[dismissUnsupportedNotice]）。
 * - Deep link 和标准版用同一个解析结果，落成 `[首页] + 支持的 keys`。
 */
class ElderNavigator(private val stack: NavBackStack<NavKey>) : TrackrNavigator {

    init {
        if (stack.firstOrNull() != ElderRoutes.root) {
            stack.clear()
            stack.add(ElderRoutes.root)
        }
    }

    /** 刚才要打开的页面长辈版不支持（提示还没关）。 */
    var unsupportedNotice: Boolean by mutableStateOf(false)
        private set

    /** 当前显示的页面。 */
    val currentKey: NavKey? get() = stack.lastOrNull()

    override fun open(key: ScreenKey) {
        if (!ElderRoutes.isSupported(key)) {
            popToRoot()
            unsupportedNotice = true
            return
        }
        if (key == ElderRoutes.root) popToRoot() else stack.pushIfNotOnTop(key)
    }

    /** 系统返回。返回 false 表示已在首页，交给系统退出。 */
    fun back(): Boolean = if (stack.size > 1) {
        stack.removeLastOrNull()
        true
    } else {
        false
    }

    override fun popChildPage() {
        if (stack.size > 1) stack.removeLastOrNull()
    }

    override fun dismiss(key: ScreenKey) {
        val index = stack.lastIndexOf(key)
        if (index > 0) stack.removeAt(index)
    }

    /** 只有一条栈，[tab] 不管；[key] 栈里有就退到它，没有就回首页再压 */
    override fun showInTab(tab: ScreenKey, key: ScreenKey) {
        if (!ElderRoutes.isSupported(key) || key == ElderRoutes.root) {
            open(key)
            return
        }
        val index = stack.indexOf(key)
        if (index >= 0) {
            while (stack.size > index + 1) stack.removeLastOrNull()
        } else {
            popToRoot()
            stack.add(key)
        }
    }

    /**
     * 落一条 deep link（§3.6、§8.4）：栈重置为 `[首页, keys…]`，页签不管；不支持的 Key 去掉并提示，
     * 首页和连续重复的 Key 跳过。同一条链接重放结果不变。
     */
    fun applyDeepLink(link: DeepLink.Stack) {
        popToRoot()
        var dropped = false
        link.keys.forEach { key ->
            when {
                !ElderRoutes.isSupported(key) -> dropped = true
                key != ElderRoutes.root -> stack.pushIfNotOnTop(key)
            }
        }
        if (dropped) unsupportedNotice = true
    }

    /** 「长辈版不支持此功能」提示点了「知道了」 */
    fun dismissUnsupportedNotice() {
        unsupportedNotice = false
    }

    private fun MutableList<NavKey>.pushIfNotOnTop(key: ScreenKey) {
        if (lastOrNull() != key) add(key)
    }

    private fun popToRoot() {
        while (stack.size > 1) stack.removeLastOrNull()
    }
}

/**
 * 记住长辈版那条栈（跨配置变更和进程重建恢复，§3.8）。存档 key 固定，不和标准版的页签栈混。
 * 恢复出来的栈底不是首页、或含长辈版不支持的页面（旧存档）就丢掉，从首页重来。
 */
@Composable
fun rememberElderBackStack(
    configuration: SavedStateConfiguration = TrackrSavedStateConfiguration,
): NavBackStack<NavKey> {
    val saver = remember(configuration) { navBackStackSaver(configuration) }
    val stack = key("elder") {
        rememberSaveable(saver = saver) { NavBackStack<NavKey>(ElderRoutes.root) }
    }
    remember(stack) {
        val valid = stack.firstOrNull() == ElderRoutes.root &&
            stack.all { it is ScreenKey && ElderRoutes.isSupported(it) }
        if (!valid) {
            stack.clear()
            stack.add(ElderRoutes.root)
        }
    }
    return stack
}
