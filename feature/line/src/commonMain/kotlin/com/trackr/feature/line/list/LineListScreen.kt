package com.trackr.feature.line.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_search
import com.trackr.core.model.MeishanArea
import com.trackr.core.model.MeishanLine
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_tab_empty
import com.trackr.feature.line.resources.line_tab_error
import com.trackr.feature.line.resources.line_tab_error_retry
import com.trackr.feature.line.resources.line_tab_not_covered
import com.trackr.feature.line.resources.line_tab_search_hint
import com.trackr.feature.line.resources.line_tab_subtitle
import com.trackr.feature.line.resources.line_tab_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 「线路」页签的导航 entry 实现（`:composeApp` 组装）。
 * 搜索命中的第三方线路详情由这里消费并打开（现有 [com.trackr.core.navigation.ScreenKey.LineDetail] 页）。
 */
@Composable
fun LineListRoute(
    viewModel: LineListViewModel,
    navigator: TrackrNavigator
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 搜索命中：打开现有线路详情页（防连点由 ViewModel 的 openingLineId 挡住）
    LaunchedEffect(state.pendingLineDetail) {
        val key = state.pendingLineDetail ?: return@LaunchedEffect
        navigator.open(key)
        viewModel.onEvent(LineListEvent.PendingNavigationConsumed)
    }

    // 一次性提示（未覆盖等）在页面底部短暂显示，进入 / 离开列表时自动清
    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        kotlinx.coroutines.delay(2500)
        viewModel.onEvent(LineListEvent.MessageShown(msg.id))
    }

    LineListScreen(state = state, onEvent = viewModel::onEvent)
}

/**
 * 「线路」页签（眉山特化）：官方 `/line/list` 全量线路列表。
 * 无状态；状态与事件见 [LineListUiState] / [LineListEvent]。
 */
@Composable
fun LineListScreen(
    state: LineListUiState,
    onEvent: (LineListEvent) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // 标题
        Text(
            text = stringResource(LineRes.string.line_tab_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(LineRes.string.line_tab_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 区域切换（眉山 / 仁寿）
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeishanArea.entries.forEach { area ->
                FilterChip(
                    selected = state.area == area,
                    onClick = { onEvent(LineListEvent.AreaSelected(area)) },
                    label = { Text(area.displayName) }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 名称 / 起终点过滤
        OutlinedTextField(
            value = state.query,
            onValueChange = { onEvent(LineListEvent.QueryChanged(it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(LineRes.string.line_tab_search_hint)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            leadingIcon = {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_search),
                    contentDescription = null
                )
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 一次性提示
        val message = state.message
        if (message != null) {
            val text = when (message) {
                is LineListMessage.NotCovered ->
                    stringResource(LineRes.string.line_tab_not_covered, message.lineName)
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        when {
            state.isLoading -> LoadingSection()
            state.loadFailed -> ErrorSection(onRetry = { onEvent(LineListEvent.Retry) })
            state.visibleLines.isEmpty() -> EmptySection()
            else -> LineListSection(
                lines = state.visibleLines,
                openingLineId = state.openingLineId,
                onLineClick = { onEvent(LineListEvent.LineClicked(it)) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LoadingSection() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorSection(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(LineRes.string.line_tab_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(LineRes.string.line_tab_error_retry))
        }
    }
}

@Composable
private fun EmptySection() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(LineRes.string.line_tab_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LineListSection(
    lines: List<MeishanLine>,
    openingLineId: Int?,
    onLineClick: (MeishanLine) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(
            items = lines,
            key = { "${it.area.areaId}_${it.lId}" },
            contentType = { "meishan_line" }
        ) { line ->
            LineRow(
                line = line,
                isOpening = openingLineId == line.lId,
                onClick = { onLineClick(line) }
            )
        }
    }
}

/** 单条线路行：名称 + 起终点（重名线路靠起终点区分，契约 §3.3）+ 首末班 */
@Composable
private fun LineRow(
    line: MeishanLine,
    isOpening: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = line.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (line.primaryText.isNotBlank()) {
                    Text(
                        text = line.primaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                val hours = line.hoursText()
                if (hours != null) {
                    Text(
                        text = hours,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isOpening) {
                CircularProgressIndicator(
                    modifier = Modifier.width(16.dp).height(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/** 首末班展示：缺失就不显示（契约 §3.5），"00:00" 视为缺失 */
private fun MeishanLine.hoursText(): String? {
    val start = startTime?.takeIf { it.isNotBlank() && it != "00:00" }
    val end = endTime?.takeIf { it.isNotBlank() && it != "00:00" }
    if (start == null && end == null) return null
    return buildString {
        if (start != null) append(start)
        append(" – ")
        if (end != null) append(end)
    }.takeIf { it.isNotBlank() && it != " – " }
}
