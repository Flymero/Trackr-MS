package com.trackr.core.network.remote.meishan.push

import com.trackr.core.network.json.JSONArray
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 眉山官方实时推送（TCP 3213）的一帧车辆数据。
 * [busInfos] 是服务端原始 JSON 数组（字段与 HTTP busList 完全一致），由上层转成 LiveBus。
 */
data class MeishanPushFrame(
    /** 推送匹配键 "l_copy_id_state"（不含 areaId，04 文档 §4） */
    val tag: String,
    /** 服务端推送时间 "yyyy-MM-dd HH:mm:ss"（北京时间），超 300 秒的帧被丢弃 */
    val pushTime: String,
    val busInfos: JSONArray
)

/** 连接状态。[ONLINE] 才有推送；[DISCONNECTED] 表示订阅还在但连不上（调用方应回退 HTTP 轮询） */
enum class MeishanPushState {
    /** 无订阅，未连接 */
    IDLE,

    /** 握手中 / 重连等待 */
    CONNECTING,

    /** 通道已建立、订阅已生效 */
    ONLINE,

    /** 有订阅但连接失败，正在重试 */
    DISCONNECTED
}

/** 帧分隔符与协议常量（04 文档）。套接字抽象见 [MeishanPushSocket] */
object MeishanPushProtocol {
    const val HOST = "busapp.superms.cn"
    const val PORT = 3213
    const val DELIMITER = "\$_\$"
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val HANDSHAKE_TIMEOUT_MS = 5_000L
    const val HEARTBEAT_INTERVAL_MS = 25_000L
    const val MAX_FRAME_BYTES = 1 shl 20

    /** 服务端要求重建通道（msgType=5）→ 重走握手 */
    const val MSG_SERVER_PUSH = 0
    const val MSG_HEARTBEAT = 1
    const val MSG_SUBSCRIBE = 2
    const val MSG_CONNECT = 3
    const val MSG_CHANNEL_ACK = 4
    const val MSG_REBUILD = 5
}

/**
 * 推送客户端对外接口（doc/news 04）：引用计数式订阅、按线路方向的 3 秒级车辆帧。
 * 实现 [MeishanPushClient]。
 */
interface MeishanPushClientApi {
    val state: StateFlow<MeishanPushState>

    /** 解析后的推送帧（已做 pushTime 300 秒时效过滤） */
    val frames: SharedFlow<MeishanPushFrame>

    /** 订阅引用 +1；首条订阅触发建连与握手 */
    suspend fun acquire(tag: String)

    /** 订阅引用 -1；归零后延迟断开 */
    suspend fun release(tag: String)
}
