package com.trackr.core.network.remote.meishan.push

/**
 * TCP 套接字抽象：commonMain 没有跨平台 socket API，androidMain 用 java.net.Socket，
 * iOS 阶段接 Network.framework。一次连接一个实例，由 [MeishanPushClient] 驱动。
 */
interface MeishanPushSocket {
    /** 阻塞建连；失败抛 IOException */
    suspend fun connect(host: String, port: Int, timeoutMs: Long)

    /**
     * 阻塞读一帧文本（返回内容不含 `$_$` 分隔符）。
     * 连接断开 / 读超时返回 null；实现需保证挂起可被取消。
     */
    suspend fun read(): String?

    /** 阻塞写一帧文本（调用方已拼好 `$_$` 结尾） */
    suspend fun write(text: String)

    /** 读超时（毫秒）；0 = 无限阻塞。握手期短超时、在线期 0 */
    fun setReadTimeoutMs(ms: Long)

    fun close()
}

/** 平台套接字工厂（androidMain：java.net.Socket） */
internal expect fun platformPushSocket(): MeishanPushSocket
