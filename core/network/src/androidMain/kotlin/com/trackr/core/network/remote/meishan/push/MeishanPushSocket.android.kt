package com.trackr.core.network.remote.meishan.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Android TCP 实现（java.net.Socket）。
 * 读帧：逐字符扫描 `$_$` 分隔符（帧约 1.2KB/3 秒，性能无虞）。
 */
internal actual fun platformPushSocket(): MeishanPushSocket = AndroidPushSocket()

internal class AndroidPushSocket : MeishanPushSocket {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override suspend fun connect(host: String, port: Int, timeoutMs: Long) =
        withContext(Dispatchers.IO) {
            val s = Socket()
            s.tcpNoDelay = true
            s.soTimeout = 0
            s.connect(InetSocketAddress(host, port), timeoutMs.toInt())
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), 64 * 1024)
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            Unit
        }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val r = reader ?: return@withContext null
        try {
            readFrame(r)
        } catch (_: IOException) {
            null // 连接断开 / 读超时
        }
    }

    /** 逐字符扫描 `$_$` 分隔符读一帧（帧约 1.2KB/3 秒，性能无虞）；EOF 且无内容返回 null */
    private fun readFrame(r: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = r.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(c.toChar())
            if (sb.length >= MeishanPushProtocol.DELIMITER.length &&
                sb.endsWith(MeishanPushProtocol.DELIMITER)
            ) {
                return sb.substring(0, sb.length - MeishanPushProtocol.DELIMITER.length)
            }
            if (sb.length > MeishanPushProtocol.MAX_FRAME_BYTES) {
                throw IOException("推送帧超过 ${MeishanPushProtocol.MAX_FRAME_BYTES} 字节上限")
            }
        }
    }

    override suspend fun write(text: String): Unit = withContext(Dispatchers.IO) {
        val w = writer ?: throw IOException("socket 未连接")
        w.write(text)
        w.flush()
    }

    override fun setReadTimeoutMs(ms: Long) {
        socket?.soTimeout = ms.toInt()
    }

    override fun close() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }
}
