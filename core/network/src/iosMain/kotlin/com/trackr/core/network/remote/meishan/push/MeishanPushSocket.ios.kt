package com.trackr.core.network.remote.meishan.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

/**
 * iOS TCP 实现：App 壳尚未建立，先保证 KMP 编译通过。
 * connect 即抛错 → 调用方自动走 HTTP 轮询降级链；接 Network.framework 时再补全。
 */
internal actual fun platformPushSocket(): MeishanPushSocket = IosPushSocket()

internal class IosPushSocket : MeishanPushSocket {
    override suspend fun connect(host: String, port: Int, timeoutMs: Long): Unit =
        withContext(Dispatchers.IO) {
            throw IOException("iOS 推送通道未实现，走 HTTP 轮询降级")
        }

    override suspend fun read(): String? = null

    override suspend fun write(text: String) {
        throw IOException("socket 未连接")
    }

    override fun setReadTimeoutMs(ms: Long) = Unit

    override fun close() = Unit
}
