package com.trackr.core.network.remote.meishan.push

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/** Android TCP implementation (java.net.Socket). */
internal actual fun platformPushSocket(): MeishanPushSocket = AndroidPushSocket()

internal class AndroidPushSocket : MeishanPushSocket {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var writer: BufferedWriter? = null

    override suspend fun connect(host: String, port: Int, timeoutMs: Long) {
        val s = Socket()
        cancellableIo(onCancel = { runCatching { s.close() } }) { continuation ->
            try {
                s.tcpNoDelay = true
                s.soTimeout = 0
                s.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                if (!continuation.isActive) {
                    runCatching { s.close() }
                    return@cancellableIo
                }
                socket = s
                input = s.getInputStream()
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            } catch (failure: Throwable) {
                runCatching { s.close() }
                throw failure
            }
        }
    }

    override suspend fun read(): String? {
        val stream = input ?: return null
        return cancellableIo(onCancel = { runCatching { socket?.close() } }) {
            try {
                readFrame(stream)
            } catch (_: IOException) {
                null // connection closed / read timeout
            }
        }
    }

    override suspend fun write(text: String) {
        cancellableIo(onCancel = { runCatching { socket?.close() } }) {
            val w = writer ?: throw IOException("socket not connected")
            w.write(text)
            w.flush()
        }
    }

    override fun setReadTimeoutMs(ms: Long) {
        socket?.soTimeout = ms.toInt()
    }

    override fun close() {
        // Interrupt a blocking read first. Closing the input wrapper first can wait on its lock.
        runCatching { socket?.close() }
        runCatching { input?.close() }
        runCatching { writer?.close() }
        input = null
        writer = null
        socket = null
    }

    private suspend fun <T> cancellableIo(
        onCancel: () -> Unit,
        block: (CancellableContinuation<T>) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { onCancel() }
        Dispatchers.IO.dispatch(continuation.context) {
            try {
                continuation.resumeWith(Result.success(block(continuation)))
            } catch (failure: Throwable) {
                continuation.resumeWith(Result.failure(failure))
            }
        }
    }
}

/**
 * Reads one UTF-8 frame terminated by `$_$`. An EOF before the delimiter discards the
 * incomplete frame, matching DelimiterBasedFrameDecoder rather than emitting a half JSON.
 * The limit is measured in wire bytes, not Kotlin UTF-16 chars.
 */
internal fun readFrame(input: InputStream): String? {
    val delimiter = MeishanPushProtocol.DELIMITER.encodeToByteArray()
    val frame = ByteArrayOutputStream()
    var previous1 = -1
    var previous2 = -1
    while (true) {
        val value = input.read()
        if (value < 0) return null
        frame.write(value)
        val size = frame.size()
        if (previous2 == delimiter[0].toInt() &&
            previous1 == delimiter[1].toInt() &&
            value == delimiter[2].toInt()
        ) {
            val payloadSize = size - delimiter.size
            if (payloadSize > MeishanPushProtocol.MAX_FRAME_BYTES) {
                throw IOException("推送帧超过 ${MeishanPushProtocol.MAX_FRAME_BYTES} 字节上限")
            }
            return frame.toByteArray().copyOf(payloadSize).toString(Charsets.UTF_8)
        }
        if (size > MeishanPushProtocol.MAX_FRAME_BYTES + delimiter.size) {
            throw IOException("推送帧超过 ${MeishanPushProtocol.MAX_FRAME_BYTES} 字节上限")
        }
        previous2 = previous1
        previous1 = value
    }
}
