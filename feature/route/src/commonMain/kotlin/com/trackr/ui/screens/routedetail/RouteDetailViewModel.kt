package com.trackr.ui.screens.routedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.core.data.repository.RealtimeRepository
import com.trackr.core.data.repository.SettingsRepository
import com.trackr.core.data.repository.TrackingRepository
import com.trackr.core.data.route.RouteAlignmentService
import com.trackr.core.domain.arrival.ArrivalState
import com.trackr.core.domain.arrival.ArrivalText
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.CandidateLineRealtimeInfo
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.FavoriteGroup
import com.trackr.core.model.GroupCategory
import com.trackr.core.model.GroupTrackingSessionState
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitGroup
import com.trackr.data.repository.IRouteGroupRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 路线的实时到站与追踪（第 17 步从 `FavoritesGroupViewModel` 拆出，逻辑原样搬）：
 * 分组 ETA 刷新（路线变化后防抖 500 ms、新一轮取消旧一轮）、每程候选线的实时信息与「选中的线」、
 * 给某一程加候选线、开始 / 我上车了 / 下一程 / 结束追踪、临时追踪的路线。
 *
 * 第 17 步仍取 Activity 级的一个实例，收藏页和规划页共用（规划页「开始追踪」用的是收藏页卡片上选中的线，
 * 追踪提示也是同一个），和原来一样；第 22 步做路线详情页时改成按路线一个实例。
 */
@OptIn(FlowPreview::class)
class RouteDetailViewModel(
    private val groupRepository: IRouteGroupRepository,
    private val settings: SettingsRepository,
    private val realtime: RealtimeRepository,
    private val alignment: RouteAlignmentService,
    /** 追踪会话（换乘组视图、临时追踪卡读它） */
    private val trackingRepository: TrackingRepository,
    /** 追踪命令入口（开始 / 我已上车 / 下一程 / 结束） */
    private val trackingController: TrackingController,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    enableAutoRefresh: Boolean = true
) : ViewModel() {

    companion object {
        /** 路线变化触发实时刷新的防抖 */
        const val ETA_REFRESH_DEBOUNCE_MS = 500L
    }

    val groups: StateFlow<List<TransitGroup>> = groupRepository.groups

    val activeGroupSession: StateFlow<GroupTrackingSessionState?> = trackingRepository.groupSession

    /**
     * 正在追踪、但不在已保存列表里的路线（如规划页"开始追踪"的临时方案）。
     * 收藏页在列表最上方显示为"临时追踪"卡片。
     */
    val temporaryTrackingGroup: StateFlow<TransitGroup?> = combine(
        trackingRepository.session,
        trackingRepository.groupSession,
        groups
    ) { trackingSession, session, savedGroups ->
        // 中断待恢复的会话当作没在追踪
        val activeGroup = trackingSession?.takeIf { !it.interrupted }?.group
        activeGroup?.takeIf { group ->
            session != null && !session.isCompleted && session.groupId == group.id &&
                savedGroups.none { it.id == group.id }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _legRealtimeSummary = MutableStateFlow<Map<String, String>>(emptyMap())
    val legRealtimeSummary: StateFlow<Map<String, String>> = _legRealtimeSummary.asStateFlow()

    private val _candidateRealtimeMap = MutableStateFlow<Map<String, CandidateLineRealtimeInfo>>(emptyMap())
    val candidateRealtimeMap: StateFlow<Map<String, CandidateLineRealtimeInfo>> = _candidateRealtimeMap.asStateFlow()

    private val _userSelectedLines = MutableStateFlow<Map<String, String>>(emptyMap())

    val selectedLineKeys: StateFlow<Map<String, String>> = combine(
        groups,
        _candidateRealtimeMap,
        _userSelectedLines
    ) { groupList, realtimeMap, userSelected ->
        val result = mutableMapOf<String, String>()
        for (group in groupList) {
            for (leg in group.legs) {
                val legKey = "${group.id}_${leg.legIndex}"
                val userChoice = userSelected[legKey]
                if (userChoice != null && leg.candidateLines.any { it.lineId == userChoice }) {
                    result[legKey] = userChoice
                } else {
                    val fastestLine = leg.candidateLines
                        .mapNotNull { line ->
                            val info = realtimeMap["${group.id}_${leg.legIndex}_${line.lineId}"]
                                ?: realtimeMap["${line.lineId}_${line.toStopOrder}"]
                            if (info != null && info.stopsAway < Int.MAX_VALUE) line to info.stopsAway else null
                        }
                        .minByOrNull { it.second }?.first
                    result[legKey] = fastestLine?.lineId ?: leg.primaryLine?.lineId.orEmpty()
                }
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 实时刷新任务：新一轮开始时取消旧的一轮，避免旧结果覆盖新结果 */
    private var etaRefreshJob: Job? = null

    /** 追踪相关的一次性提示（如未能对齐真实站序时） */
    private val _trackingMessage = MutableStateFlow<String?>(null)
    val trackingMessage: StateFlow<String?> = _trackingMessage.asStateFlow()

    fun clearTrackingMessage() {
        _trackingMessage.value = null
    }

    init {
        if (enableAutoRefresh) {
            viewModelScope.launch {
                // 防抖合并连续变更；新一轮开始时取消旧一轮（refreshRealtimeEta 内部也会取消上一个任务）
                groups.debounce(ETA_REFRESH_DEBOUNCE_MS).collectLatest { groupList ->
                    refreshRealtimeEta(groupList).join()
                }
            }
            // 换乘引擎的下车站定位辅助判定需要精度与定位时刻，由拿到 Location 的地方直接调用
            // TrackingController.updateUserFix；仓库里的纯经纬度不再转发（无法判断是否过期）
        }
    }

    fun selectCandidateLine(groupId: String, legIndex: Int, lineId: String) {
        _userSelectedLines.value = _userSelectedLines.value + ("${groupId}_${legIndex}" to lineId)
    }

    fun addCandidateLineToLeg(groupId: String, legIndex: Int, candidateLine: CandidateLine) {
        viewModelScope.launch {
            val group = groupRepository.getGroupById(groupId)
            val leg = group?.legs?.getOrNull(legIndex)
            val alignedLine = if (leg != null) {
                val city = alignment.resolveCandidateCity(candidateLine, settings.selectedCity.value)
                val matched = alignment.findRealtimeLineMatch(
                    city = city,
                    lineName = candidateLine.lineName,
                    fromStopName = leg.fromStopName,
                    toStopName = leg.toStopName,
                    preferredDirection = candidateLine.direction
                )
                matched ?: candidateLine
            } else candidateLine

            groupRepository.addCandidateLineToLeg(groupId, legIndex, alignedLine)
            refreshRealtimeEta(groupRepository.groups.value)
        }
    }

    /**
     * 启动整组全程自动换乘追踪。
     *
     * 启动前：
     * 1. 若候选线路为合成的（lineId 以 line_ 开头 / fromStopOrder>=toStopOrder / fromStopOrder<=0），
     *    先调用 [RouteAlignmentService.findRealtimeLineMatch] 对齐并把结果写回仓库（[IRouteGroupRepository.updateGroup]）；
     *    匹配不到则保持未对齐并通过 [trackingMessage] 提示，不使用假站序启动追踪。
     * 2. 追踪使用用户在卡片上选中的候选线路（[selectedLineKeys]）。
     */
    fun startGroupTracking(group: TransitGroup, startingLegIndex: Int = 0): Job =
        viewModelScope.launch {
            val alignedGroup = ensureGroupAligned(group)

            // 若对齐后仍存在合成/无效站序的要追踪线路，则不启动，给出提示
            val selectedIds = resolveSelectedLineIds(alignedGroup)
            val startLeg = alignedGroup.legs.getOrNull(startingLegIndex.coerceIn(0, alignedGroup.legs.lastIndex))
            val startLine = startLeg?.let { leg ->
                leg.candidateLines.firstOrNull { it.lineId == selectedIds[leg.legIndex] } ?: leg.primaryLine
            }
            if (startLine == null || isSyntheticLine(startLine)) {
                _trackingMessage.value = "未能对齐【${group.name}】的真实线路站序，暂无法开启追踪，请稍后重试或检查候选线路。"
                return@launch
            }

            trackingController.start(
                TrackingTarget.Route(
                    group = alignedGroup,
                    startLeg = startingLegIndex,
                    selectedLineIds = selectedIds
                )
            )
        }

    /** 手动确认“我已上车”，跳过等车阶段 */
    fun confirmBoarded() {
        trackingController.confirmBoarded()
    }

    /** 手动跳到下一程（乘车阶段使用） */
    fun switchToNextLeg() {
        trackingController.switchToNextLeg()
    }

    /** 判断候选线路是否为合成/未对齐（合成 id / 非法站序）。和对齐时的判断不同（多一条 fromStopOrder <= 0），原样保留 */
    private fun isSyntheticLine(line: CandidateLine): Boolean =
        line.lineId.startsWith("line_") || line.fromStopOrder <= 0 || line.fromStopOrder >= line.toStopOrder

    /**
     * 对齐该组内所有合成线路；仅当该组已保存在仓库时才写回（临时追踪的方案只在内存里，不能借此写进收藏）。
     * 若无需对齐则原样返回。
     */
    private suspend fun ensureGroupAligned(group: TransitGroup): TransitGroup {
        val needsAlign = group.legs.any { leg -> leg.candidateLines.any { isSyntheticLine(it) } }
        if (!needsAlign) return group
        val aligned = alignment.alignTransitGroupWithRealtimeSource(group)
        val isSaved = groupRepository.groups.value.any { it.id == group.id }
        if (aligned != group && isSaved) {
            runCatching { groupRepository.updateGroup(aligned) }
        }
        return aligned
    }

    /** 从 selectedLineKeys 解析每程选中的 lineId（legIndex -> lineId） */
    private fun resolveSelectedLineIds(group: TransitGroup): Map<Int, String> {
        val keys = selectedLineKeys.value
        val result = mutableMapOf<Int, String>()
        for (leg in group.legs) {
            val chosen = keys["${group.id}_${leg.legIndex}"]
            if (chosen != null && leg.candidateLines.any { it.lineId == chosen }) {
                result[leg.legIndex] = chosen
            }
        }
        return result
    }

    /**
     * 停止换乘追踪
     */
    fun stopGroupTracking() {
        trackingController.stopTracking()
    }

    // ---- 路线详情页（第 22 步）：改名、改分组、生成返程、删除、临时方案存成收藏 ----

    /** 收藏分组（详情页「改分组」和「收藏」弹层用） */
    val favoriteGroups: StateFlow<List<FavoriteGroup>> = groupRepository.favoriteGroups

    /** 改路线名 */
    fun renameGroup(groupId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(ioDispatcher) {
            val group = groupRepository.getGroupById(groupId) ?: return@launch
            if (group.name == trimmed) return@launch
            groupRepository.updateGroup(group.copy(name = trimmed))
        }
    }

    /** 放到某个收藏分组；null = 不分组 */
    fun moveGroupToFavoriteGroup(groupId: String, favoriteGroupId: String?) {
        viewModelScope.launch(ioDispatcher) {
            val group = groupRepository.getGroupById(groupId) ?: return@launch
            if (group.customGroupId == favoriteGroupId) return@launch
            groupRepository.updateGroup(
                group.copy(
                    customGroupId = favoriteGroupId,
                    category = if (favoriteGroupId == null) GroupCategory.CUSTOM else group.category
                )
            )
        }
    }

    /** 地图临时方案存成收藏（可同时改名字、放到分组） */
    fun saveTemporaryGroup(groupId: String, name: String, favoriteGroupId: String?) {
        viewModelScope.launch(ioDispatcher) {
            groupRepository.saveOrConvertTemporaryGroup(
                groupId = groupId,
                newName = name.trim().ifEmpty { null },
                customGroupId = favoriteGroupId
            )
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch(ioDispatcher) {
            groupRepository.deleteGroup(groupId)
        }
    }

    fun createReverseGroup(group: TransitGroup) {
        viewModelScope.launch(ioDispatcher) {
            val targetGroupId = when (group.customGroupId) {
                "commute" -> "home"
                "home" -> "commute"
                else -> group.customGroupId ?: if (group.category == GroupCategory.COMMUTE) "home" else null
            }
            val reversed = alignment.buildReverseTransitGroup(group, targetGroupId)
            groupRepository.addGroup(reversed)
        }
    }

    /**
     * 刷新路线中所有候选线路在途车辆实时状态并聚合计算最先到站线路。
     * 新一轮刷新会取消上一轮尚未完成的刷新，避免旧结果覆盖新结果。
     */
    fun refreshRealtimeEta(groupList: List<TransitGroup> = groups.value): Job {
        etaRefreshJob?.cancel()
        val job = viewModelScope.launch(ioDispatcher) {
            refreshRealtimeEtaInternal(groupList)
        }
        etaRefreshJob = job
        return job
    }

    private suspend fun refreshRealtimeEtaInternal(groupList: List<TransitGroup>): Unit = coroutineScope {
            val candidateAlignmentMap = mutableMapOf<String, CandidateLine>()
            for (group in groupList) {
                for (leg in group.legs) {
                    for (candidate in leg.candidateLines) {
                        val key = "${group.id}_${leg.legIndex}_${candidate.lineId}"
                        if (candidate.lineId.startsWith("line_") || candidate.fromStopOrder >= candidate.toStopOrder) {
                            val matched = alignment.alignSyntheticCandidateCached(leg, candidate)
                            if (matched != null) {
                                candidateAlignmentMap[key] = matched
                            }
                        }
                    }
                }
            }

            val allCandidates = groupList
                .flatMap { g ->
                    g.legs.flatMap { leg ->
                        leg.candidateLines.map { c ->
                            candidateAlignmentMap["${g.id}_${leg.legIndex}_${c.lineId}"] ?: c
                        }
                    }
                }

            if (allCandidates.isEmpty()) return@coroutineScope

            data class QueryKey(
                val cityId: String,
                val cityName: String,
                val lineId: String,
                val targetOrder: Int,
                val source: DataSourceType,
                val lineName: String,
                val direction: Int
            )

            val uniqueQueries = allCandidates.map { candidate ->
                val targetOrder = if (candidate.fromStopOrder > 0) candidate.fromStopOrder else candidate.toStopOrder
                QueryKey(
                    cityId = candidate.cityId,
                    cityName = candidate.cityName,
                    lineId = candidate.lineId,
                    targetOrder = targetOrder,
                    source = candidate.source,
                    lineName = candidate.lineName,
                    direction = candidate.direction
                )
            }.distinctBy { "${it.lineId}_${it.targetOrder}" }

            val detailResults = uniqueQueries.map { query ->
                async {
                    val city = TransitCity(cityId = query.cityId, cityName = query.cityName)
                    // 只走 realtime 一条路径，失败不再对同一上游重复请求
                    val detail: BusLineDetail? = runCatching {
                        realtime.getLineDetail(
                            city = city,
                            lineId = query.lineId,
                            targetOrder = query.targetOrder,
                            source = query.source,
                            lineNameHint = query.lineName,
                            directionHint = query.direction
                        ).getOrNull()
                    }.getOrNull()
                    "${query.lineId}_${query.targetOrder}" to detail
                }
            }.awaitAll().toMap()

            val updatedCandidateMap = _candidateRealtimeMap.value.toMutableMap()
            val updatedSummaryMap = _legRealtimeSummary.value.toMutableMap()

            for (group in groupList) {
                for (leg in group.legs) {
                    val legCandidates = leg.candidateLines
                    if (legCandidates.isEmpty()) continue

                    val parsedInfos = legCandidates.map { candidate ->
                        val effectiveCandidate = candidateAlignmentMap["${group.id}_${leg.legIndex}_${candidate.lineId}"] ?: candidate
                        val targetOrder = if (effectiveCandidate.fromStopOrder > 0) effectiveCandidate.fromStopOrder else effectiveCandidate.toStopOrder
                        val detail = detailResults["${effectiveCandidate.lineId}_${targetOrder}"]

                        val rideStops = (effectiveCandidate.toStopOrder - effectiveCandidate.fromStopOrder).coerceAtLeast(0)
                        val directionText = detail?.line?.endStop?.takeIf { it.isNotBlank() }?.let { "${it}方向" }
                            ?: effectiveCandidate.endStopName.takeIf { it.isNotBlank() }?.let { "${it}方向" }
                            ?: ""

                        if (detail != null) {
                            val candidateBuses = detail.buses.filter { it.order <= targetOrder }
                            val nearest = candidateBuses.maxByOrNull { it.order.toFloat() + it.segmentProgress }
                            if (nearest != null) {
                                val stopsLeft = nearest.stopsTo(targetOrder).coerceAtLeast(0)
                                // 到站口径统一走 ArrivalRules（ETA 1..45 秒、坐标缺失不信插值），文字走 ArrivalText
                                val arrivalState = ArrivalText.stateForBus(targetOrder, nearest)
                                val isArrived = arrivalState is ArrivalState.Arriving
                                val etaMinutes = ArrivalText.minutesOf(nearest.etaSeconds) ?: (stopsLeft * 2).coerceAtLeast(1)
                                val etaText = ArrivalText.format(arrivalState).short
                                val shortChipEta = if (isArrived) "已到站" else "${stopsLeft}站"

                                CandidateLineRealtimeInfo(
                                    lineId = candidate.lineId,
                                    lineName = candidate.lineName,
                                    etaText = etaText,
                                    shortChipEta = shortChipEta,
                                    stopsAway = stopsLeft,
                                    estimatedMinutes = etaMinutes,
                                    directionText = directionText,
                                    vehiclePlate = nearest.friendlyPlate,
                                    rideStopsCount = rideStops,
                                    isFastest = false
                                )
                            } else {
                                // 没车时按线路的运营状态写，别一律写成「等待发车」：
                                // 末班之后掌上公交和车来了都应显示「末班已过」。
                                val serviceText = when (detail.operatingState) {
                                    LineOperatingState.ENDED -> "末班已过"
                                    LineOperatingState.WAITING -> "等待发车"
                                    LineOperatingState.SUSPENDED -> "暂停运营"
                                    LineOperatingState.NO_REALTIME -> "未开通实时"
                                    LineOperatingState.RUNNING ->
                                        detail.statusDescription.ifBlank { "暂无在途车辆" }
                                }
                                val chipText = when (detail.operatingState) {
                                    LineOperatingState.ENDED -> "末班已过"
                                    LineOperatingState.WAITING -> "等待发车"
                                    LineOperatingState.SUSPENDED -> "暂停运营"
                                    LineOperatingState.NO_REALTIME -> "未开通"
                                    LineOperatingState.RUNNING -> "等待发车"
                                }
                                CandidateLineRealtimeInfo(
                                    lineId = candidate.lineId,
                                    lineName = candidate.lineName,
                                    etaText = serviceText,
                                    shortChipEta = chipText,
                                    stopsAway = Int.MAX_VALUE,
                                    estimatedMinutes = Int.MAX_VALUE,
                                    directionText = directionText,
                                    vehiclePlate = "",
                                    rideStopsCount = rideStops,
                                    isFastest = false
                                )
                            }
                        } else {
                            CandidateLineRealtimeInfo(
                                lineId = candidate.lineId,
                                lineName = candidate.lineName,
                                etaText = "等待调度发车",
                                shortChipEta = "等待发车",
                                stopsAway = Int.MAX_VALUE,
                                estimatedMinutes = Int.MAX_VALUE,
                                directionText = directionText,
                                vehiclePlate = "",
                                rideStopsCount = rideStops,
                                isFastest = false
                            )
                        }
                    }

                    val minStops = parsedInfos
                        .filter { it.stopsAway < Int.MAX_VALUE }
                        .minOfOrNull { it.stopsAway }

                    val fastestLineId = if (minStops != null) {
                        parsedInfos.find { it.stopsAway == minStops }?.lineId
                    } else null

                    parsedInfos.forEachIndexed { idx, info ->
                        val candidate = legCandidates[idx]
                        val effective = candidateAlignmentMap["${group.id}_${leg.legIndex}_${candidate.lineId}"] ?: candidate
                        val isFastest = (info.lineId == fastestLineId)
                        val finalInfo = info.copy(isFastest = isFastest)

                        val candidateLegKey = "${group.id}_${leg.legIndex}_${candidate.lineId}"
                        val candidateLineKey = "${candidate.lineId}_${candidate.toStopOrder}"
                        val sameLine = candidate.lineId == effective.lineId
                        val sameOrder = candidate.toStopOrder == effective.toStopOrder

                        // 始终保留原始 key，UI 侧会按原始候选项查询
                        updatedCandidateMap[candidateLegKey] = finalInfo
                        updatedCandidateMap[candidateLineKey] = finalInfo
                        updatedSummaryMap[candidateLineKey] = finalInfo.etaText

                        // 对齐后的 key 仅在真正不同时才额外写入，避免同 key 重复赋值
                        if (!sameLine) {
                            updatedCandidateMap["${group.id}_${leg.legIndex}_${effective.lineId}"] = finalInfo
                        }
                        if (!sameLine || !sameOrder) {
                            updatedCandidateMap["${effective.lineId}_${effective.toStopOrder}"] = finalInfo
                            updatedSummaryMap["${effective.lineId}_${effective.toStopOrder}"] = finalInfo.etaText
                        }
                    }
                }
            }

            _candidateRealtimeMap.value = updatedCandidateMap
            _legRealtimeSummary.value = updatedSummaryMap
        }
}
