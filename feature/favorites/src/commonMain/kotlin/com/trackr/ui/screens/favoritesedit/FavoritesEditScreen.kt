@file:OptIn(ExperimentalMaterial3Api::class)

package com.trackr.ui.screens.favoritesedit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.action_cancel
import com.trackr.core.designsystem.resources.action_delete
import com.trackr.core.designsystem.resources.action_done
import com.trackr.core.designsystem.resources.action_save
import com.trackr.core.designsystem.resources.group_none
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_check
import com.trackr.core.designsystem.resources.ic_delete
import com.trackr.core.designsystem.resources.ic_drag
import com.trackr.core.designsystem.resources.ic_edit
import com.trackr.core.designsystem.resources.ic_plus
import com.trackr.core.designsystem.resources.ic_route
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.FavoriteItem
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.displayLineName
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.feature.favorites.resources.Res as FavoritesRes
import com.trackr.feature.favorites.resources.fav_edit_create
import com.trackr.feature.favorites.resources.fav_edit_delete_favorite
import com.trackr.feature.favorites.resources.fav_edit_delete_group_body
import com.trackr.feature.favorites.resources.fav_edit_delete_group_empty
import com.trackr.feature.favorites.resources.fav_edit_delete_group_title
import com.trackr.feature.favorites.resources.fav_edit_delete_route_body
import com.trackr.feature.favorites.resources.fav_edit_delete_route_title
import com.trackr.feature.favorites.resources.fav_edit_delete_this_group
import com.trackr.feature.favorites.resources.fav_edit_drag
import com.trackr.feature.favorites.resources.fav_edit_empty
import com.trackr.feature.favorites.resources.fav_edit_group_actions
import com.trackr.feature.favorites.resources.fav_edit_group_dialog_title
import com.trackr.feature.favorites.resources.fav_edit_group_name
import com.trackr.feature.favorites.resources.fav_edit_group_name_hint
import com.trackr.feature.favorites.resources.fav_edit_group_picker_title
import com.trackr.feature.favorites.resources.fav_edit_hint
import com.trackr.feature.favorites.resources.fav_edit_new_group
import com.trackr.feature.favorites.resources.fav_edit_no_routes
import com.trackr.feature.favorites.resources.fav_edit_route_count
import com.trackr.feature.favorites.resources.fav_edit_section_favorites
import com.trackr.feature.favorites.resources.fav_edit_section_groups
import com.trackr.feature.favorites.resources.fav_edit_title
import com.trackr.ui.components.arrival.LineBadge
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import kotlin.math.roundToInt

/** 编辑收藏页的动作 */
sealed interface FavoritesEditEvent {
    data object Back : FavoritesEditEvent
    data object Done : FavoritesEditEvent
    data class CreateGroup(val name: String) : FavoritesEditEvent
    data class RenameGroup(val groupId: String, val name: String) : FavoritesEditEvent
    data class DeleteGroup(val groupId: String) : FavoritesEditEvent
    data class ReorderGroups(val ids: List<String>) : FavoritesEditEvent

    /** 把一条收藏（车或路线）移到 [groupId]；null = 不分组 */
    data class SetGroup(val key: String, val groupId: String?) : FavoritesEditEvent

    /** 删掉一条收藏，[key] 是 [FavoriteItem.key] */
    data class DeleteItem(val key: String) : FavoritesEditEvent

    /** 拖动排序，[keys] 是这一列的新先后 */
    data class MoveItems(val keys: List<String>) : FavoritesEditEvent
}

/**
 * 编辑收藏（v3 第 22 步，第 33 步修正）：分组和收藏都在这一页管，拖动排序、改名、删除、改分组。
 *
 * 收藏区是首页「收藏」的同一份合一列表：车（线路 + 候车站）和路线排在一起，顺序也一致。
 * 删分组时里面的收藏变成「不分组」；点一条收藏改分组。
 */
@Composable
fun FavoritesEditScreen(
    items: List<FavoriteItem>,
    favoriteGroups: List<FavoriteGroup>,
    onEvent: (FavoritesEditEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateGroup by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<FavoriteGroup?>(null) }
    var deletingKey by remember { mutableStateOf<String?>(null) }
    var groupingKey by remember { mutableStateOf<String?>(null) }

    val groupNames = remember(favoriteGroups) { favoriteGroups.associate { it.id to it.name } }
    val itemByKey = remember(items) { items.associate { it.key to it } }

    // 拖动到屏幕上下边缘时自动翻页（收藏多的时候能一路拖下去）
    val listState = rememberLazyListState()
    val autoScroll = rememberEdgeAutoScroller(listState)
    var viewport by remember { mutableStateOf(Rect.Zero) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(FavoritesRes.string.fav_edit_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(FavoritesEditEvent.Back) }) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_back),
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { onEvent(FavoritesEditEvent.Done) }) {
                        Text(stringResource(Res.string.action_done))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // 视口边界：判断手指是不是拖到了上下边缘
                .onGloballyPositioned { viewport = it.boundsInWindow() },
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "groups_title") {
                SectionTitle(stringResource(FavoritesRes.string.fav_edit_section_groups))
            }

            item(key = "groups_card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column {
                        ReorderableColumn(
                            ids = favoriteGroups.map { it.id },
                            onReorder = { onEvent(FavoritesEditEvent.ReorderGroups(it)) },
                            autoScroll = autoScroll,
                            viewport = viewport
                        ) { id, handle ->
                            val group = favoriteGroups.firstOrNull { it.id == id } ?: return@ReorderableColumn
                            GroupRow(
                                group = group,
                                count = items.count { it.groupId == group.id },
                                handle = handle,
                                onEdit = { editingGroup = group }
                            )
                        }

                        if (favoriteGroups.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showCreateGroup = true }
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("btn_create_favorite_group"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = vectorResource(Res.drawable.ic_plus),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(FavoritesRes.string.fav_edit_new_group),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            item(key = "favorites_title") {
                SectionTitle(stringResource(FavoritesRes.string.fav_edit_section_favorites))
            }

            if (items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(FavoritesRes.string.fav_edit_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else {
                item(key = "favorites_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        ReorderableColumn(
                            ids = items.map { it.key },
                            onReorder = { onEvent(FavoritesEditEvent.MoveItems(it)) },
                            autoScroll = autoScroll,
                            viewport = viewport
                        ) { key, handle ->
                            val item = itemByKey[key] ?: return@ReorderableColumn
                            FavoriteRow(
                                item = item,
                                groupName = groupNames[item.groupId],
                                handle = handle,
                                onClick = { groupingKey = key },
                                onDelete = { deletingKey = key }
                            )
                        }
                    }
                }
            }

            item(key = "hint") {
                Text(
                    text = stringResource(FavoritesRes.string.fav_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }

    if (showCreateGroup) {
        NameDialog(
            title = stringResource(FavoritesRes.string.fav_edit_new_group),
            initial = "",
            confirmLabel = stringResource(FavoritesRes.string.fav_edit_create),
            onDismiss = { showCreateGroup = false },
            onConfirm = { name ->
                showCreateGroup = false
                onEvent(FavoritesEditEvent.CreateGroup(name))
            }
        )
    }

    editingGroup?.let { group ->
        EditGroupDialog(
            group = group,
            count = items.count { it.groupId == group.id },
            onDismiss = { editingGroup = null },
            onRename = { name ->
                editingGroup = null
                onEvent(FavoritesEditEvent.RenameGroup(group.id, name))
            },
            onDelete = {
                editingGroup = null
                onEvent(FavoritesEditEvent.DeleteGroup(group.id))
            }
        )
    }

    val groupingItem = groupingKey?.let(itemByKey::get)
    if (groupingItem != null) {
        GroupPickerDialog(
            currentGroupId = groupingItem.groupId,
            groups = favoriteGroups,
            onDismiss = { groupingKey = null },
            onPick = { groupId ->
                groupingKey = null
                onEvent(FavoritesEditEvent.SetGroup(groupingItem.key, groupId))
            }
        )
    }

    val deletingItem = deletingKey?.let(itemByKey::get)
    if (deletingItem != null) {
        AlertDialog(
            onDismissRequest = { deletingKey = null },
            title = { Text(stringResource(FavoritesRes.string.fav_edit_delete_route_title)) },
            text = { Text(stringResource(FavoritesRes.string.fav_edit_delete_route_body, deletingItem.title())) },
            confirmButton = {
                TextButton(onClick = {
                    deletingKey = null
                    onEvent(FavoritesEditEvent.DeleteItem(deletingItem.key))
                }) {
                    Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingKey = null }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }
}

/** 一条收藏在列表里的主文案：车是「科技园站上车」，路线是名字（没名字时用起终点） */
private fun FavoriteItem.title(): String = when (this) {
    is FavoriteItem.Stop -> "${stop.targetStopName}上车"
    is FavoriteItem.Route -> route.title()
}

private fun TransitGroup.title(): String =
    name.trim().ifBlank { "$originStopName → $destinationStopName" }

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun GroupRow(
    group: FavoriteGroup,
    count: Int,
    handle: Modifier,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DragHandle(handle)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                // 名字长也不换行：拖动排序按「第一行高度」算，行高得一致
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (count == 0) stringResource(FavoritesRes.string.fav_edit_no_routes) else stringResource(FavoritesRes.string.fav_edit_route_count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.testTag("btn_edit_group_${group.id}")) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_edit),
                contentDescription = stringResource(FavoritesRes.string.fav_edit_group_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 一行收藏：把手 | 线路号（车）或路线图标 | 主副文案 | 删除 */
@Composable
private fun FavoriteRow(
    item: FavoriteItem,
    groupName: String?,
    handle: Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("favorite_row_${item.key}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DragHandle(handle)
        Spacer(modifier = Modifier.width(8.dp))
        when (item) {
            // 老收藏里可能没存到名字：退回线路号，避免只剩一个空徽标（issue #20）
            is FavoriteItem.Stop -> LineBadge(name = item.stop.displayLineName)
            is FavoriteItem.Route -> Icon(
                imageVector = vectorResource(Res.drawable.ic_route),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (item is FavoriteItem.Route) {
                        append("${item.route.originStopName} → ${item.route.destinationStopName}")
                        append(" · ")
                    }
                    append(groupName ?: stringResource(Res.string.group_none))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.testTag("btn_delete_favorite_${item.key}")) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_delete),
                contentDescription = stringResource(FavoritesRes.string.fav_edit_delete_favorite),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 拖动把手：长按后上下拖。热区 36dp，比图标本身宽一点，好按 */
@Composable
private fun DragHandle(modifier: Modifier) {
    Box(modifier = modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = vectorResource(Res.drawable.ic_drag),
            contentDescription = stringResource(FavoritesRes.string.fav_edit_drag),
            // 拖拽是这一页唯一的排序入口，别用 outlineVariant（浅到看不见）
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .size(20.dp)
                .alpha(0.9f)
        )
    }
}

/** 拖动中的一次手势：从 [fromIndex] 抓起，手指走到 [offsetPx] */
private data class DragSession(
    val id: String,
    val fromIndex: Int,
    val offsetPx: Float,
    val rowHeightPx: Float,
    val lastIndex: Int
) {
    /** 夹在列表范围内：拖到第一行 / 最后一行就不动了，别整行飞出卡片 */
    private val clampedOffsetPx: Float
        get() = offsetPx.coerceIn(-fromIndex * rowHeightPx, (lastIndex - fromIndex) * rowHeightPx)

    /** 现在松手会落在哪一行 */
    val targetIndex: Int
        get() = (fromIndex + (clampedOffsetPx / rowHeightPx).roundToInt()).coerceIn(0, lastIndex)

    /** 被拖那一行画在哪：跟手指走（夹过边界） */
    val visualOffsetPx: Float
        get() = clampedOffsetPx
}

/** 松手后的这一小段：视觉已经按新顺序站好，只剩被拖那行的零头要补间，等仓库回写后撤掉 */
private data class PendingReorder(
    /** 松手前的顺序：仓库还没回写时画出来的还是它 */
    val before: List<String>,
    /** 松手后的顺序：松手那一帧就按它画 */
    val order: List<String>,
    /** 被拖的那行，这时候只有它还有位移 */
    val id: String,
    /** 静态位置和手指位置还差多少 px（半行以内），补间到 0 */
    val residualPx: Float
)

/**
 * 一行在拖动中要往哪挪（px）。
 *
 * 被拖的那行跟手指走；夹在起止之间的行各让出一格，看着就是「实时插进去」。
 * 行高取第一行的高度（这一页的行都是单行 / 两行小字，高度一致）。
 */
internal fun dragShiftPx(
    index: Int,
    fromIndex: Int,
    toIndex: Int,
    dragging: Boolean,
    dragOffsetPx: Float,
    rowHeightPx: Float
): Float = when {
    dragging -> dragOffsetPx
    fromIndex < index && index <= toIndex -> -rowHeightPx
    toIndex <= index && index < fromIndex -> rowHeightPx
    else -> 0f
}

/**
 * 松手时手指离落点还差多少（px，半行以内）。
 *
 * 松手那一帧列表的静态顺序就换成新顺序了：让过位的行静态位置正好落在落点上（位移直接归零），
 * 只有被拖那行还差「手指停在两个格子中间」的那点零头，从它补间回 0 就是一次干净的落位。
 * 别让其它行再补间一次 —— 它们的位移目标从 ±一行变成 0 的这 130ms，看着就是「松手后又插了一次」。
 */
internal fun dropResidualPx(offsetPx: Float, fromIndex: Int, toIndex: Int, rowHeightPx: Float): Float =
    offsetPx - (toIndex - fromIndex) * rowHeightPx

/** 让位 / 落位的动画时长：短一点才跟得上手指 */
private const val REORDER_ANIM_MS = 130

/** 落位动画走完再撤 pending 的余量，免得抬起的样子在落位途中掉下去 */
private const val SETTLE_HOLD_MS = REORDER_ANIM_MS + 32

/** 离边缘多近开始自动翻页，以及最慢 / 最快每帧滚多少（以 dp 给，按屏幕密度换算） */
private val AUTO_SCROLL_EDGE = 72.dp
private val AUTO_SCROLL_MAX_STEP = 15.dp

/**
 * 手指离视口上下边缘有多近 → 这一帧该滚多少（px，正 = 往下翻）。
 * 越靠边越快，中间是 0；[viewportBottom] 不合法时（还没布局）不动。
 */
internal fun edgeScrollStepPx(
    pointerY: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgePx: Float,
    maxStepPx: Float
): Float {
    if (viewportBottom <= viewportTop || edgePx <= 0f) return 0f
    val topZoneEnd = viewportTop + edgePx
    val bottomZoneStart = viewportBottom - edgePx
    return when {
        pointerY < topZoneEnd ->
            -maxStepPx * ((topZoneEnd - pointerY) / edgePx).coerceIn(0f, 1f)
        pointerY > bottomZoneStart ->
            maxStepPx * ((pointerY - bottomZoneStart) / edgePx).coerceIn(0f, 1f)
        else -> 0f
    }
}

/**
 * 拖动时的边缘自动翻页：按速度逐帧 [LazyListState.scrollBy]，再把**实际滚了多少**回调出去。
 *
 * 回调很重要：列表往上滚了 5px，被拖的那行在内容里也要往下挪 5px，不然就从手指底下溜走了。
 *
 * [stepPx] 为 0 时外面不会起这个循环（空闲时一帧都不占）：拖到列表头尾滚不动了也会自己停下。
 */
@Stable
private class EdgeAutoScroller(private val listState: LazyListState) {

    /** 每帧滚多少 px（正 = 往下翻），拖动时按手指离边缘的远近算出来 */
    var stepPx by mutableStateOf(0f)
        private set

    /** 实际滚了多远（px），拖动那边拿去补 offset */
    var onScrolled: ((Float) -> Unit)? = null

    fun drive(step: Float) {
        if (stepPx != step) stepPx = step
    }

    fun stop() {
        if (stepPx != 0f) stepPx = 0f
    }

    suspend fun run() {
        var lastFrameNanos = 0L
        while (stepPx != 0f) {
            val now = withFrameNanos { it }
            val step = stepPx
            if (step == 0f) break
            // 按真实帧间隔折算，掉帧时不会滚得忽快忽慢
            val dtMs = if (lastFrameNanos == 0L) 16f else ((now - lastFrameNanos) / 1_000_000f).coerceIn(1f, 32f)
            lastFrameNanos = now
            val consumed = listState.scrollBy(step * dtMs / 16f)
            // 已经到顶 / 到底：别再空转
            if (consumed == 0f) {
                stop()
                break
            }
            onScrolled?.invoke(consumed)
        }
    }
}

@Composable
private fun rememberEdgeAutoScroller(listState: LazyListState): EdgeAutoScroller {
    val scroller = remember(listState) { EdgeAutoScroller(listState) }
    // 只有真的要滚的时候才起循环：平时（包括空闲的编辑页）不占帧
    val running = scroller.stepPx != 0f
    LaunchedEffect(scroller, running) {
        if (running) scroller.run()
    }
    return scroller
}

/**
 * 一列可拖动排序的行（v3 编辑收藏、第 33 步改成实时让位）。
 *
 * 拖动时只有被拖的那行跟手指走，其余行立刻挪开一格（带 130ms 动画），松手才把新顺序交出去。
 *
 * 松手那一帧**就按新顺序画**（[order] 取 `pending.order`）：让过位的行静态位置正好落在落点上，
 * 位移直接归零；只有被拖那行还差「手指停在两格之间」的零头（[dropResidualPx]），补间回 0。
 * 等仓库把 `ids` 换成这个顺序、落位也走完了，再撤掉 pending —— 两边的顺序已经一样，画面一丝不动。
 *
 * 注意位移和动画状态必须按行（[key] + `remember`）走：以前挂在「第几行」上，顺序一变，
 * 残留的位移就套到了别人头上，松手后看着又插了一次。
 */
@Composable
private fun ReorderableColumn(
    ids: List<String>,
    onReorder: (List<String>) -> Unit,
    autoScroll: EdgeAutoScroller,
    viewport: Rect,
    row: @Composable (id: String, handle: Modifier) -> Unit
) {
    val density = LocalDensity.current
    var rowHeightPx by remember { mutableFloatStateOf(with(density) { 56.dp.toPx() }) }
    var drag by remember { mutableStateOf<DragSession?>(null) }
    var pending by remember { mutableStateOf<PendingReorder?>(null) }
    // 拖动把手的节点坐标：手指位置换成窗口坐标，才知道离屏幕上下边还有多远
    var handleCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val edgePx = with(density) { AUTO_SCROLL_EDGE.toPx() }
    val maxStepPx = with(density) { AUTO_SCROLL_MAX_STEP.toPx() }

    // 画出来的顺序：松手那一帧就换成新顺序，各行静态位置立刻到位
    val order = pending?.order ?: ids

    // 仓库回写了（或者数据被别处改过）：等落位动画走完再撤 pending，抬起的样子不会中途掉下去
    LaunchedEffect(ids, pending) {
        val reorder = pending ?: return@LaunchedEffect
        when (ids) {
            reorder.order -> {
                delay(SETTLE_HOLD_MS.toLong())
                if (pending === reorder) pending = null
            }
            // 还没回写：先按落位画着，别闪回旧顺序
            reorder.before -> Unit
            // 数据被别处改过（比如同时删了一条）：这次落位作废，回到数据里的顺序
            else -> pending = null
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        order.forEachIndexed { index, itemId ->
            // 每行的位移 / 动画都按 id 走（key + remember 各一份），顺序一变不会串到别的行上
            key(itemId) {
                val session = drag
                val dragging = session?.id == itemId
                // 刚松手、还在落位的那行；其它行的静态位置已经到头了，不用再动
                val settling = if (session == null) pending?.takeIf { it.id == itemId } else null

                // 拖动中「让出位置」的位移：只有被拖那行和目标之间的行各挪一格
                val lettingWay = if (session != null && !dragging) {
                    dragShiftPx(
                        index = index,
                        fromIndex = session.fromIndex,
                        toIndex = session.targetIndex,
                        dragging = false,
                        dragOffsetPx = 0f,
                        rowHeightPx = rowHeightPx
                    )
                } else {
                    0f
                }
                // 让位走 130ms 缓动，才看得出「插进去了」；松手后这套位移就作废，不看它
                val lettingWayShift = remember { Animatable(0f) }
                LaunchedEffect(lettingWay) {
                    lettingWayShift.animateTo(lettingWay, tween(REORDER_ANIM_MS))
                }

                // 落位进度 0 -> 1：0 是松手那一帧（位移 = 零头），1 是站好（位移 0）。
                // 空闲时始终压在 0，下次松手才接得上，不会从上一轮的 1 掉下来
                val settleProgress = remember { Animatable(0f) }
                LaunchedEffect(settling) {
                    settleProgress.snapTo(0f)
                    if (settling != null) settleProgress.animateTo(1f, tween(REORDER_ANIM_MS))
                }

                val shift = when {
                    settling != null -> settling.residualPx * (1f - settleProgress.value)
                    session == null -> 0f
                    dragging -> session.visualOffsetPx
                    else -> lettingWayShift.value
                }
                val lifted = dragging || settling != null
                val dragElevation = with(density) { 6.dp.toPx() }
                // 手势里要用的「现在第几行 / 现在什么顺序」：顺序变了也不重启手势检测器，
                // 免得刚落位就抓另一行时，拖动被打断
                val currentIndex by rememberUpdatedState(index)
                val currentOrder by rememberUpdatedState(order)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged {
                            if (index == 0 && it.height > 0) rowHeightPx = it.height.toFloat()
                        }
                        .zIndex(if (lifted) 1f else 0f)
                        // graphicsLayer 要在 background 外面：拖动时底色和内容一起走
                        .graphicsLayer {
                            translationY = shift
                            shadowElevation = if (lifted) dragElevation else 0f
                        }
                        .background(
                            if (lifted) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
                        )
                ) {
                    row(
                        itemId,
                        Modifier
                            .onGloballyPositioned { handleCoords = it }
                            .pointerInput(itemId) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        // 上一轮的落位状态作废，按当前顺序重新算
                                        pending = null
                                        drag = DragSession(
                                            id = itemId,
                                            fromIndex = currentIndex,
                                            offsetPx = 0f,
                                            rowHeightPx = rowHeightPx,
                                            lastIndex = currentOrder.lastIndex
                                        )
                                        autoScroll.stop()
                                        // 滚了多少就给被拖那行补多少，别让它从手指底下溜走
                                        autoScroll.onScrolled = { consumed ->
                                            drag = drag?.let { it.copy(offsetPx = it.offsetPx + consumed) }
                                        }
                                    },
                                    onDrag = { change, dragAmount: Offset ->
                                        change.consume()
                                        drag = drag?.let { it.copy(offsetPx = it.offsetPx + dragAmount.y) }
                                        // 靠近上下边缘就开着自动翻页，越靠边越快
                                        val y = handleCoords?.localToWindow(change.position)?.y
                                        autoScroll.drive(
                                            if (y == null) {
                                                0f
                                            } else {
                                                edgeScrollStepPx(
                                                    pointerY = y,
                                                    viewportTop = viewport.top,
                                                    viewportBottom = viewport.bottom,
                                                    edgePx = edgePx,
                                                    maxStepPx = maxStepPx
                                                )
                                            }
                                        )
                                    },
                                    onDragEnd = {
                                        autoScroll.stop()
                                        autoScroll.onScrolled = null
                                        val ended = drag
                                        drag = null
                                        if (ended == null) return@detectDragGesturesAfterLongPress
                                        val to = ended.targetIndex
                                        val drawn = currentOrder
                                        val next = drawn.toMutableList()
                                            .apply { add(to, removeAt(ended.fromIndex)) }
                                        // 松手这一帧就按新顺序画：让过位的行静态位置正好在落点，
                                        // 只有被拖那行还要把「半格以内」的零头补间回 0
                                        pending = PendingReorder(
                                            before = drawn,
                                            order = next,
                                            id = ended.id,
                                            residualPx = dropResidualPx(
                                                offsetPx = ended.visualOffsetPx,
                                                fromIndex = ended.fromIndex,
                                                toIndex = to,
                                                rowHeightPx = ended.rowHeightPx
                                            )
                                        )
                                        if (to != ended.fromIndex) onReorder(next)
                                    },
                                    onDragCancel = {
                                        autoScroll.stop()
                                        autoScroll.onScrolled = null
                                        drag = null
                                    }
                                )
                            }
                    )
                }
            }
        }
    }
}

/** 改分组：不分组 + 各个分组，当前那个打勾 */
@Composable
private fun GroupPickerDialog(
    currentGroupId: String?,
    groups: List<FavoriteGroup>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(FavoritesRes.string.fav_edit_group_picker_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                GroupOption(
                    name = stringResource(Res.string.group_none),
                    selected = currentGroupId == null,
                    testTag = "group_option_none",
                    onClick = { onPick(null) }
                )
                groups.forEach { group ->
                    GroupOption(
                        name = group.name,
                        selected = currentGroupId == group.id,
                        testTag = "group_option_${group.id}",
                        onClick = { onPick(group.id) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

@Composable
private fun GroupOption(
    name: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(FavoritesRes.string.fav_edit_group_name)) },
                placeholder = { Text(stringResource(FavoritesRes.string.fav_edit_group_name_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_group_name")
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

@Composable
private fun EditGroupDialog(
    group: FavoriteGroup,
    count: Int,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(group.id) { mutableStateOf(group.name) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(FavoritesRes.string.fav_edit_delete_group_title, group.name)) },
            text = {
                Text(
                    if (count == 0) stringResource(FavoritesRes.string.fav_edit_delete_group_empty)
                    else stringResource(FavoritesRes.string.fav_edit_delete_group_body, count)
                )
            },
            confirmButton = {
                TextButton(onClick = onDelete) {
                    Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(FavoritesRes.string.fav_edit_group_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(FavoritesRes.string.fav_edit_group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(stringResource(FavoritesRes.string.fav_edit_delete_this_group), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) {
                    Text(stringResource(Res.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }
}

/** 编辑收藏 Route：读状态、翻译事件 */
@Composable
fun FavoritesEditRoute(
    viewModel: FavoritesEditViewModel,
    navigator: TrackrNavigator,
    modifier: Modifier = Modifier
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val favoriteGroups by viewModel.favoriteGroups.collectAsStateWithLifecycle()
    val back = dropUnlessResumed { navigator.popChildPage() }

    FavoritesEditScreen(
        items = items,
        favoriteGroups = favoriteGroups,
        onEvent = { event ->
            when (event) {
                FavoritesEditEvent.Back, FavoritesEditEvent.Done -> back()
                is FavoritesEditEvent.CreateGroup -> viewModel.createFavoriteGroup(event.name)
                is FavoritesEditEvent.RenameGroup -> viewModel.renameFavoriteGroup(event.groupId, event.name)
                is FavoritesEditEvent.DeleteGroup -> viewModel.deleteFavoriteGroup(event.groupId)
                is FavoritesEditEvent.ReorderGroups -> viewModel.reorderFavoriteGroups(event.ids)
                is FavoritesEditEvent.SetGroup -> viewModel.setItemGroup(event.key, event.groupId)
                is FavoritesEditEvent.DeleteItem -> viewModel.removeItem(event.key)
                is FavoritesEditEvent.MoveItems -> viewModel.moveItems(event.keys)
            }
        },
        modifier = modifier
    )
}
