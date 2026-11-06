package com.trackr.feature.bus.home

import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitCodeProvider

/** 首页发出的事件。导航类由 [BusHomeRoute] 处理，其余交给 [BusHomeViewModel.onEvent] */
sealed interface BusHomeEvent {

    // ---- 导航（Route 处理）----
    data object OpenSearch : BusHomeEvent
    data class OpenLine(val target: LineTarget) : BusHomeEvent
    data class OpenStop(val target: StopTarget) : BusHomeEvent

    /** 点了收藏的路线（路线详情第 22 步做，先去旧收藏页） */
    data class OpenRoute(val routeId: String) : BusHomeEvent

    /** 收藏右上角「编辑」 */
    data object EditFavorites : BusHomeEvent

    /** 顶栏右上角「设置」（只有长辈版首页有：没有底部页签，从这里进「我的」） */
    data object OpenSettings : BusHomeEvent

    // ---- 交给 ViewModel ----
    data object Refresh : BusHomeEvent
    data object RetryLocation : BusHomeEvent
    /**
     * 一张追踪卡上的「结束」：只停这一项。
     * @param watchLineId 一起等里的哪一条线（只去掉这条）；null = 结束整份会话
     */
    data class StopTracking(val sessionId: String, val watchLineId: String? = null) : BusHomeEvent
    data class SelectGroup(val groupId: String?) : BusHomeEvent
    data object TransitCodeClicked : BusHomeEvent
    data class TransitCodeChosen(val provider: TransitCodeProvider, val saveAsDefault: Boolean) : BusHomeEvent
    data object TransitCodeSheetDismissed : BusHomeEvent
    data class MessageShown(val id: Long) : BusHomeEvent

    /** 长辈版卡片上的「读给我听」：[text] 是卡片的朗读整句 */
    data class ReadAloud(val text: String) : BusHomeEvent

    // 城市选择
    data object OpenCityPicker : BusHomeEvent
    data object CloseCityPicker : BusHomeEvent
    data class CitySearchChanged(val query: String) : BusHomeEvent
    data class ProvinceSelected(val province: String) : BusHomeEvent
    data class CitySelected(val city: TransitCity) : BusHomeEvent
    data object RelocateCity : BusHomeEvent
}
