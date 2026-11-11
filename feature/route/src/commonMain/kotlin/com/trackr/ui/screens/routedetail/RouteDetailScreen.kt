@file:OptIn(ExperimentalMaterial3Api::class)

package com.trackr.ui.screens.routedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.trackr.core.designsystem.component.TrackrBottomActionBar
import com.trackr.core.designsystem.resources.Res
import com.trackr.ui.components.message.HostMessage
import com.trackr.ui.components.message.UserMessageHost
import com.trackr.core.designsystem.resources.action_add
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.action_boarded
import com.trackr.core.designsystem.resources.action_cancel
import com.trackr.core.designsystem.resources.action_delete
import com.trackr.core.designsystem.resources.action_end
import com.trackr.core.designsystem.resources.action_end_tracking
import com.trackr.core.designsystem.resources.action_favorite
import com.trackr.core.designsystem.resources.action_save
import com.trackr.core.designsystem.resources.action_start_tracking
import com.trackr.core.designsystem.resources.group_none
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_bus
import com.trackr.core.designsystem.resources.ic_check
import com.trackr.core.designsystem.resources.ic_chevron_right
import com.trackr.core.designsystem.resources.ic_more
import com.trackr.core.designsystem.resources.ic_plus
import com.trackr.core.designsystem.resources.ic_walk
import com.trackr.core.designsystem.resources.ic_warning
import com.trackr.core.designsystem.resources.more_actions
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.CandidateLineRealtimeInfo
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupTrackingSessionState
import com.trackr.core.model.LegPhase
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import com.trackr.core.navigation.TrackrNavigator
import com.trackr.core.navigation.ScreenKey
import com.trackr.feature.route.resources.Res as RouteRes
import com.trackr.feature.route.resources.route_detail_add_line_hint
import com.trackr.feature.route.resources.route_detail_add_line_title
import com.trackr.feature.route.resources.route_detail_board_at
import com.trackr.feature.route.resources.route_detail_change_group
import com.trackr.feature.route.resources.route_detail_connecting
import com.trackr.feature.route.resources.route_detail_delete
import com.trackr.feature.route.resources.route_detail_delete_body
import com.trackr.feature.route.resources.route_detail_delete_title
import com.trackr.feature.route.resources.route_detail_gone
import com.trackr.feature.route.resources.route_detail_hint_riding
import com.trackr.feature.route.resources.route_detail_hint_waiting
import com.trackr.feature.route.resources.route_detail_leg_tracking
import com.trackr.feature.route.resources.route_detail_line_detail
import com.trackr.feature.route.resources.route_detail_line_name
import com.trackr.feature.route.resources.route_detail_more_lines
import com.trackr.feature.route.resources.route_detail_name
import com.trackr.feature.route.resources.route_detail_next_leg
import com.trackr.feature.route.resources.route_detail_no_candidates
import com.trackr.feature.route.resources.route_detail_no_realtime
import com.trackr.feature.route.resources.route_detail_pick_group
import com.trackr.feature.route.resources.route_detail_rename
import com.trackr.feature.route.resources.route_detail_reverse
import com.trackr.feature.route.resources.route_detail_save_name
import com.trackr.feature.route.resources.route_detail_save_title
import com.trackr.feature.route.resources.route_detail_stops_to
import com.trackr.feature.route.resources.route_detail_title
import com.trackr.feature.route.resources.route_detail_waiting
import com.trackr.feature.route.resources.route_detail_walk_to
import com.trackr.feature.route.resources.route_minutes
import com.trackr.feature.route.resources.route_selected
import com.trackr.feature.route.resources.route_unselected
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** 路线详情页的动作（页面无状态，Route 翻译成 ViewModel 调用与导航） */
sealed interface RouteDetailEvent {
    data object Back : RouteDetailEvent
    data object StartTracking : RouteDetailEvent
    data object ConfirmBoarded : RouteDetailEvent
    data object NextLeg : RouteDetailEvent
    data object StopTracking : RouteDetailEvent
    data class SelectLine(val legIndex: Int, val lineId: String) : RouteDetailEvent
    data class AddLine(val legIndex: Int, val lineName: String) : RouteDetailEvent
    data class OpenLine(val candidate: CandidateLine) : RouteDetailEvent
    data class SaveTemporary(val name: String, val favoriteGroupId: String?) : RouteDetailEvent
    data object CreateReverse : RouteDetailEvent
    data object Delete : RouteDetailEvent
    data class Rename(val name: String) : RouteDetailEvent
    data class MoveToGroup(val favoriteGroupId: String?) : RouteDetailEvent
}

/**
 * 路线详情（v3 第 22 步）：每程的候选线都列出来，勾上的线一起等；追踪中显示这一程的实时状态。
 * 底部主按钮跟着追踪阶段变：开始追踪 → 我上车了 → 下一程 → 结束。
 */
@Composable
fun RouteDetailScreen(
    group: TransitGroup?,
    activeSession: GroupTrackingSessionState?,
    realtimeSummaries: Map<String, String>,
    candidateRealtimeMap: Map<String, CandidateLineRealtimeInfo>,
    selectedLineKeys: Map<String, String>,
    favoriteGroups: List<FavoriteGroup>,
    message: String?,
    onMessageShown: () -> Unit,
    onEvent: (RouteDetailEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMore by remember { mutableStateOf(false) }
    var showAddLineForLeg by remember { mutableStateOf<Int?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showMoveGroup by remember { mutableStateOf(false) }

    val session = activeSession?.takeIf { it.groupId == group?.id && !it.isCompleted }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            UserMessageHost(
                message = message?.let { HostMessage(key = it, text = it) },
                onResult = { _, _ -> onMessageShown() }
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = group?.name ?: stringResource(RouteRes.string.route_detail_title),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onEvent(RouteDetailEvent.Back) }) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.ic_back),
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                actions = {
                    if (group != null && !group.isTemporary) {
                        Box {
                            IconButton(onClick = { showMore = true }) {
                                Icon(
                                    imageVector = vectorResource(Res.drawable.ic_more),
                                    contentDescription = stringResource(Res.string.more_actions)
                                )
                            }
                            DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(RouteRes.string.route_detail_rename)) },
                                    onClick = { showMore = false; showRename = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(RouteRes.string.route_detail_change_group)) },
                                    onClick = { showMore = false; showMoveGroup = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(RouteRes.string.route_detail_reverse)) },
                                    onClick = { showMore = false; onEvent(RouteDetailEvent.CreateReverse) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(RouteRes.string.route_detail_delete), color = MaterialTheme.colorScheme.error) },
                                    onClick = { showMore = false; showDelete = true }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (group != null) {
                BottomBar(
                    group = group,
                    session = session,
                    onEvent = onEvent,
                    onSave = { showSave = true }
                )
            }
        }
    ) { innerPadding ->
        if (group == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(RouteRes.string.route_detail_gone),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "meta") {
                Text(
                    text = metaText(group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            if (session != null) {
                item(key = "tracking") {
                    TrackingCard(session = session)
                }
            }

            group.legs.forEachIndexed { index, leg ->
                item(key = "leg_${leg.legIndex}") {
                    LegBlock(
                        group = group,
                        leg = leg,
                        index = index,
                        selectedLineId = selectedLineKeys["${group.id}_${leg.legIndex}"],
                        infoOf = { candidate ->
                            candidateRealtimeMap["${group.id}_${leg.legIndex}_${candidate.lineId}"]
                                ?: candidateRealtimeMap["${candidate.lineId}_${candidate.toStopOrder}"]
                        },
                        fallbackEtaOf = { candidate ->
                            realtimeSummaries["${candidate.lineId}_${candidate.toStopOrder}"]
                        },
                        onSelect = { lineId -> onEvent(RouteDetailEvent.SelectLine(leg.legIndex, lineId)) },
                        onAdd = { showAddLineForLeg = leg.legIndex },
                        onOpenLine = { onEvent(RouteDetailEvent.OpenLine(it)) }
                    )
                }
                if (index < group.legs.lastIndex) {
                    item(key = "walk_${leg.legIndex}") {
                        WalkBlock(legs = listOf(leg, group.legs[index + 1]))
                    }
                }
            }

            item(key = "hint") {
                Text(
                    text = if (session == null) {
                        stringResource(RouteRes.string.route_detail_hint_waiting)
                    } else {
                        stringResource(RouteRes.string.route_detail_hint_riding)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }
    }

    val legForAdd = showAddLineForLeg
    if (legForAdd != null) {
        AddLineDialog(
            legIndex = legForAdd,
            onDismiss = { showAddLineForLeg = null },
            onConfirm = { name ->
                onEvent(RouteDetailEvent.AddLine(legForAdd, name))
                showAddLineForLeg = null
            }
        )
    }

    if (showDelete && group != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(RouteRes.string.route_detail_delete_title)) },
            text = { Text(stringResource(RouteRes.string.route_detail_delete_body, group.name)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onEvent(RouteDetailEvent.Delete)
                }) {
                    Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }

    if (showRename && group != null) {
        var name by remember(group.name) { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(RouteRes.string.route_detail_name)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRename = false
                        onEvent(RouteDetailEvent.Rename(name))
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(Res.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text(stringResource(Res.string.action_cancel)) }
            }
        )
    }

    if (showMoveGroup && group != null) {
        GroupPickerDialog(
            title = stringResource(RouteRes.string.route_detail_pick_group),
            groups = favoriteGroups,
            selectedId = group.customGroupId,
            onDismiss = { showMoveGroup = false },
            onPick = { id ->
                showMoveGroup = false
                onEvent(RouteDetailEvent.MoveToGroup(id))
            }
        )
    }

    if (showSave && group != null) {
        SaveRouteDialog(
            group = group,
            favoriteGroups = favoriteGroups,
            onDismiss = { showSave = false },
            onConfirm = { name, favoriteGroupId ->
                showSave = false
                onEvent(RouteDetailEvent.SaveTemporary(name, favoriteGroupId))
            }
        )
    }
}

/** 底部操作栏：没追踪时「开始追踪」（临时方案多一个「收藏」）；追踪中按阶段换按钮 */
@Composable
private fun BottomBar(
    group: TransitGroup,
    session: GroupTrackingSessionState?,
    onEvent: (RouteDetailEvent) -> Unit,
    onSave: () -> Unit
) {
    TrackrBottomActionBar {
        if (session == null) {
            if (group.isTemporary) {
                TextButton(onClick = onSave, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(Res.string.action_favorite))
                }
            }
            Button(
                onClick = { onEvent(RouteDetailEvent.StartTracking) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(stringResource(Res.string.action_start_tracking))
            }
        } else {
            val waiting = session.trackingSessionState?.legPhase != LegPhase.ON_BOARD
            if (waiting) {
                Button(
                    onClick = { onEvent(RouteDetailEvent.ConfirmBoarded) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(stringResource(Res.string.action_boarded))
                }
            } else if (session.activeLegIndex < session.totalLegs - 1) {
                Button(
                    onClick = { onEvent(RouteDetailEvent.NextLeg) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(stringResource(RouteRes.string.route_detail_next_leg))
                }
            } else {
                Button(
                    onClick = { onEvent(RouteDetailEvent.StopTracking) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(stringResource(Res.string.action_end_tracking))
                }
            }
            TextButton(
                onClick = { onEvent(RouteDetailEvent.StopTracking) },
                modifier = Modifier.height(48.dp)
            ) {
                Text(stringResource(Res.string.action_end), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TrackingCard(session: GroupTrackingSessionState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(RouteRes.string.route_detail_leg_tracking, session.activeLegIndex + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            val state = session.trackingSessionState
            val etaMinutes = state?.etaSeconds?.takeIf { it > 0 }?.let { (it + 59) / 60 }
            Text(
                text = if (etaMinutes != null) stringResource(RouteRes.string.route_minutes, etaMinutes) else stringResource(RouteRes.string.route_detail_waiting),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = state?.statusSummary ?: stringResource(RouteRes.string.route_detail_connecting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            if (state != null && state.stopsRemaining >= 0) {
                Text(
                    text = stringResource(RouteRes.string.route_detail_stops_to, state.stopsRemaining, state.boardStopName.ifBlank { state.targetStopName }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun LegBlock(
    group: TransitGroup,
    leg: TransitLeg,
    index: Int,
    selectedLineId: String?,
    infoOf: (CandidateLine) -> CandidateLineRealtimeInfo?,
    fallbackEtaOf: (CandidateLine) -> String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenLine: (CandidateLine) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            LegMarker(index = index, last = index == group.legs.lastIndex)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(RouteRes.string.route_detail_board_at, leg.fromStopName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (leg.candidateLines.isEmpty()) {
                    Text(
                        text = stringResource(RouteRes.string.route_detail_no_candidates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                leg.candidateLines.forEach { candidate ->
                    val info = infoOf(candidate)
                    val selected = candidate.lineId == selectedLineId
                    CandidateRow(
                        candidate = candidate,
                        info = info,
                        fallbackEta = fallbackEtaOf(candidate),
                        selected = selected,
                        onClick = { onSelect(candidate.lineId) },
                        onOpenLine = { onOpenLine(candidate) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Row(
                    modifier = Modifier
                        .clickable(onClick = onAdd)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = vectorResource(Res.drawable.ic_plus),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(RouteRes.string.route_detail_more_lines),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = rideText(leg, selectedLineId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: CandidateLine,
    info: CandidateLineRealtimeInfo?,
    fallbackEta: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onOpenLine: () -> Unit
) {
    val eta = info?.shortChipEta?.takeIf { it.isNotBlank() }
        ?: info?.etaText?.takeIf { it.isNotBlank() }
        ?: fallbackEta
        ?: stringResource(RouteRes.string.route_detail_no_realtime)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_check),
                contentDescription = if (selected) stringResource(RouteRes.string.route_selected) else stringResource(RouteRes.string.route_unselected),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = candidate.lineName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = eta,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = vectorResource(Res.drawable.ic_chevron_right),
                contentDescription = stringResource(RouteRes.string.route_detail_line_detail),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onOpenLine)
            )
        }
    }
}

@Composable
private fun LegMarker(index: Int, last: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (index == 0) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_bus),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        if (!last) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
        }
    }
}

@Composable
private fun WalkBlock(legs: List<TransitLeg>) {
    val to = legs.getOrNull(1) ?: return
    val meters = to.walkingDistanceMeters
    if (meters <= 0) return
    Row(modifier = Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = vectorResource(Res.drawable.ic_walk),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(RouteRes.string.route_detail_walk_to, meters, to.fromStopName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun metaText(group: TransitGroup): String {
    val walk = group.legs.sumOf { it.walkingDistanceMeters }
    return buildString {
        append("${group.legs.size} 程")
        if (group.legs.size > 1) append(" · 换乘 ${group.legs.size - 1} 次")
        if (walk > 0) append(" · 步行 $walk 米")
        if (group.category.displayName.isNotBlank()) append(" · ${group.category.displayName}")
    }
}

private fun rideText(leg: TransitLeg, selectedLineId: String?): String {
    val line = leg.candidateLines.firstOrNull { it.lineId == selectedLineId } ?: leg.primaryLine
    if (line == null) return "到 ${leg.toStopName} 下车"
    val stops = line.toStopOrder - line.fromStopOrder + 1
    return if (stops > 0) "坐 $stops 站，到 ${leg.toStopName} 下车" else "到 ${leg.toStopName} 下车"
}

/** 添加候选线：手输线路名，站序由仓库对齐真实线路 */
@Composable
private fun AddLineDialog(
    legIndex: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(RouteRes.string.route_detail_add_line_title, legIndex + 1)) },
        text = {
            Column {
                Text(
                    text = stringResource(RouteRes.string.route_detail_add_line_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(RouteRes.string.route_detail_line_name)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_add_line")
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(Res.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

@Composable
private fun GroupPickerDialog(
    title: String,
    groups: List<FavoriteGroup>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                PickerRow(label = stringResource(Res.string.group_none), selected = selectedId == null) { onPick(null) }
                groups.forEach { group ->
                    PickerRow(label = group.name, selected = selectedId == group.id) { onPick(group.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } }
    )
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = vectorResource(Res.drawable.ic_check),
                contentDescription = stringResource(RouteRes.string.route_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 收藏临时方案：只问名字和分组（v3 收藏弹层） */
@Composable
private fun SaveRouteDialog(
    group: TransitGroup,
    favoriteGroups: List<FavoriteGroup>,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit
) {
    var name by remember(group.name) { mutableStateOf(group.name) }
    var favoriteGroupId by remember { mutableStateOf(group.customGroupId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(RouteRes.string.route_detail_save_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(RouteRes.string.route_detail_save_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(RouteRes.string.route_detail_pick_group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column {
                    PickerRow(label = stringResource(Res.string.group_none), selected = favoriteGroupId == null) { favoriteGroupId = null }
                    favoriteGroups.forEach { group ->
                        PickerRow(label = group.name, selected = favoriteGroupId == group.id) {
                            favoriteGroupId = group.id
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim(), favoriteGroupId) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("btn_confirm_save_route")
            ) {
                Text(stringResource(Res.string.action_favorite))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        }
    )
}

/**
 * 路线详情 Route：从共享的 [RouteDetailViewModel] 取这条路线和实时状态，把事件翻成 ViewModel 调用 / 导航。
 */
@Composable
fun RouteDetailRoute(
    groupId: String,
    viewModel: RouteDetailViewModel,
    navigator: TrackrNavigator,
    modifier: Modifier = Modifier
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val session by viewModel.activeGroupSession.collectAsStateWithLifecycle()
    val summaries by viewModel.legRealtimeSummary.collectAsStateWithLifecycle()
    val candidateRealtime by viewModel.candidateRealtimeMap.collectAsStateWithLifecycle()
    val selectedLines by viewModel.selectedLineKeys.collectAsStateWithLifecycle()
    val favoriteGroups by viewModel.favoriteGroups.collectAsStateWithLifecycle()
    val message by viewModel.trackingMessage.collectAsStateWithLifecycle()

    val group = groups.firstOrNull { it.id == groupId }
    val back = dropUnlessResumed { navigator.popChildPage() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val openLine: (CandidateLine) -> Unit = { candidate ->
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            navigator.open(
                ScreenKey.LineDetail(
                    cityId = candidate.cityId,
                    lineId = candidate.lineId,
                    targetOrder = if (candidate.fromStopOrder > 0) candidate.fromStopOrder else candidate.toStopOrder,
                    stopId = "",
                    source = candidate.source,
                    lineName = candidate.lineName
                )
            )
        }
    }

    RouteDetailScreen(
        group = group,
        activeSession = session,
        realtimeSummaries = summaries,
        candidateRealtimeMap = candidateRealtime,
        selectedLineKeys = selectedLines,
        favoriteGroups = favoriteGroups,
        message = message,
        onMessageShown = { viewModel.clearTrackingMessage() },
        onEvent = { event ->
            when (event) {
                RouteDetailEvent.Back -> back()
                RouteDetailEvent.StartTracking -> group?.let { viewModel.startGroupTracking(it) }
                RouteDetailEvent.ConfirmBoarded -> viewModel.confirmBoarded()
                RouteDetailEvent.NextLeg -> viewModel.switchToNextLeg()
                RouteDetailEvent.StopTracking -> viewModel.stopGroupTracking()
                is RouteDetailEvent.SelectLine -> group?.let {
                    viewModel.selectCandidateLine(it.id, event.legIndex, event.lineId)
                }
                is RouteDetailEvent.AddLine -> group?.let {
                    val leg = it.legs.getOrNull(event.legIndex)
                    val primary = leg?.primaryLine
                    viewModel.addCandidateLineToLeg(
                        groupId = it.id,
                        legIndex = event.legIndex,
                        candidateLine = CandidateLine(
                            cityId = primary?.cityId ?: "",
                            cityName = primary?.cityName ?: "",
                            lineId = "line_${it.id}_${event.legIndex}_${event.lineName}_${kotlin.random.Random.nextInt(10_000)}",
                            lineName = event.lineName,
                            source = primary?.source ?: com.trackr.core.model.DataSourceType.CHELAILE,
                            direction = primary?.direction ?: 0,
                            fromStopOrder = 0,
                            toStopOrder = 0,
                            startStopName = leg?.fromStopName.orEmpty(),
                            endStopName = leg?.toStopName.orEmpty()
                        )
                    )
                }
                is RouteDetailEvent.OpenLine -> openLine(event.candidate)
                is RouteDetailEvent.SaveTemporary -> group?.let {
                    viewModel.saveTemporaryGroup(it.id, event.name, event.favoriteGroupId)
                }
                RouteDetailEvent.CreateReverse -> group?.let { viewModel.createReverseGroup(it) }
                RouteDetailEvent.Delete -> group?.let {
                    viewModel.deleteGroup(it.id)
                    back()
                }
                is RouteDetailEvent.Rename -> group?.let { viewModel.renameGroup(it.id, event.name) }
                is RouteDetailEvent.MoveToGroup -> group?.let {
                    viewModel.moveGroupToFavoriteGroup(it.id, event.favoriteGroupId)
                }
            }
        },
        modifier = modifier
    )
}
