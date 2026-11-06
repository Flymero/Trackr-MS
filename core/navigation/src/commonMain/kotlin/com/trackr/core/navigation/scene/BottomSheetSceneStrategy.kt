package com.trackr.core.navigation.scene

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * 弹层型目的地（重构计划 §3.7）：entry 的 metadata 带 [bottomSheet] 时，叠在上一页上用 [ModalBottomSheet] 显示。
 * 拷自 nav3-recipes 的 bottomsheet recipe（`navigation3-ui` 1.2.0 只自带 `DialogSceneStrategy`）。
 *
 * 用法：`entry<ScreenKey.Xxx>(metadata = BottomSheetSceneStrategy.bottomSheet()) { … }`；
 * 第 21 步「上了哪辆？」、第 22 步「收藏弹层」接上。返回手势 / 下滑 / 点外面都走 [SceneStrategyScope.onBack]，即 `Navigator.back()`。
 *
 * 要排在非弹层的 strategy 前面（NavDisplay 找不到弹层时退回单页显示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
class BottomSheetSceneStrategy<T : Any> : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val lastEntry = entries.lastOrNull() ?: return null
        val properties = lastEntry.metadata[BottomSheetKey] ?: return null
        @Suppress("UNCHECKED_CAST")
        return BottomSheetScene(
            key = lastEntry.contentKey as T,
            previousEntries = entries.dropLast(1),
            overlaidEntries = entries.dropLast(1),
            entry = lastEntry,
            modalBottomSheetProperties = properties,
            onBack = onBack,
        )
    }

    companion object {
        /** 标记 entry 用弹层显示（默认弹层属性；调用方不用 opt-in material3 的实验 API）。 */
        fun bottomSheet(): Map<String, Any> = metadata {
            put(BottomSheetKey, ModalBottomSheetProperties())
        }

        internal object BottomSheetKey : NavMetadataKey<ModalBottomSheetProperties>
    }
}

/** 把一个 entry 放进 [ModalBottomSheet] 的 [OverlayScene]，下面的页照常显示。 */
@OptIn(ExperimentalMaterial3Api::class)
internal data class BottomSheetScene<T : Any>(
    override val key: T,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val entry: NavEntry<T>,
    private val modalBottomSheetProperties: ModalBottomSheetProperties,
    private val onBack: () -> Unit,
) : OverlayScene<T> {

    override val entries: List<NavEntry<T>> = listOf(entry)

    private var sheetState: SheetState? = null

    override val content: @Composable () -> Unit = {
        // 保留半展开锚点（skipPartiallyExpanded = false 的等价写法）
        val state = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
        )
        sheetState = state
        ModalBottomSheet(
            onDismissRequest = onBack,
            properties = modalBottomSheetProperties,
            sheetState = state,
        ) {
            entry.Content()
        }
    }

    /** 从栈里弹出时先播收起动画。 */
    override suspend fun onRemove() {
        sheetState?.hide()
    }
}
