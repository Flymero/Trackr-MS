package com.trackr.feature.line.elder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.model.BusStop
import com.trackr.core.model.LiveBus
import com.trackr.feature.line.resources.Res as LineRes
import com.trackr.feature.line.resources.line_elder_bus_coming
import com.trackr.feature.line.resources.line_elder_bus_here
import com.trackr.feature.line.resources.line_elder_pick_stop_title
import com.trackr.feature.line.resources.line_elder_picked
import com.trackr.ui.components.elder.ElderButtonHeight
import com.trackr.ui.components.elder.ElderTopBar
import com.trackr.ui.components.elder.ShowTransitCodeButton
import org.jetbrains.compose.resources.stringResource

/** 测试用的 tag */
internal object ElderPickStopTags {
    const val LIST = "elder_pick_stop_list"
    fun stop(order: Int) = "elder_pick_stop_$order"
}

/**
 * 长辈版「换站」（设计稿「在哪一站上车？」）：一行一个站，点一下就选好并返回。
 * 车的位置用文字标出（「车在这」「车快到」），不靠颜色；当前选的站标「现在选的」，打开时滚到它附近。
 *
 * @param onPick 点了某站（调用方选站并回到线路页）
 * @param onBack 左上角「← 返回」：不换站
 * @param showTransitCode 底部「出示乘车码」显不显示（跟着「我的」里的开关；关掉后列表铺满）
 */
@Composable
fun ElderPickStopScreen(
    stops: List<BusStop>,
    buses: List<LiveBus>,
    selectedOrder: Int?,
    onPick: (BusStop) -> Unit,
    onBack: () -> Unit,
    onOpenTransitCode: () -> Unit,
    showTransitCode: Boolean = true,
    modifier: Modifier = Modifier
) {
    val selectedIndex = stops.indexOfFirst { it.order == selectedOrder }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0))
    // 站序 → 这一站上有没有车（已停在站 = 车在这；正开往这一站 = 车快到）
    val busMarks = remember(buses) {
        buses.groupBy { it.order }.mapValues { (_, list) -> list.any { it.isArrived } }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ElderTopBar(title = stringResource(LineRes.string.line_elder_pick_stop_title), onBack = onBack) },
        bottomBar = { if (showTransitCode) ShowTransitCodeButton(onClick = onOpenTransitCode) }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag(ElderPickStopTags.LIST)
        ) {
            itemsIndexed(items = stops, key = { index, stop -> "${index}_${stop.order}" }) { _, stop ->
                val mark = when (busMarks[stop.order]) {
                    true -> stringResource(LineRes.string.line_elder_bus_here)
                    false -> stringResource(LineRes.string.line_elder_bus_coming)
                    null -> null
                }
                ElderStopRow(
                    stop = stop,
                    selected = stop.order == selectedOrder,
                    mark = mark,
                    onClick = { onPick(stop) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ElderStopRow(stop: BusStop, selected: Boolean, mark: String?, onClick: () -> Unit) {
    val dimens = TrackrTheme.dimens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ElderButtonHeight)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = dimens.screenPadding, vertical = dimens.spaceS)
            .testTag(ElderPickStopTags.stop(stop.order)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = stop.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        val tags = listOfNotNull(mark, if (selected) stringResource(LineRes.string.line_elder_picked) else null)
        if (tags.isNotEmpty()) {
            Spacer(modifier = Modifier.width(dimens.spaceS))
            Text(
                text = tags.joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (mark != null) TrackrTheme.colors.go else MaterialTheme.colorScheme.primary
            )
        }
    }
}
