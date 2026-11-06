package com.trackr.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import com.trackr.core.model.TrackingSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource

/**
 * 追踪会话存成一个 JSON 数组（文件 `tracking_session.json`，可同时追多项）。每次变化整份覆盖写，从不查询（§2.4）。
 * 空文件 = 没有会话；解析失败抛 [CorruptionException]，由 DataStore 的损坏处理换成空列表。
 * `TrackingRepository`（第 12 步）经 `DataStoreTrackingSessionStore` 读写。
 *
 * 第 36 步之前文件里是**单个对象**（只能追一项）。读的时候两种都认：以 `[` 开头是数组，否则按旧版单对象解析、
 * 包成一项，下次写盘自动变成数组。
 */
object TrackingSessionSerializer : OkioSerializer<List<TrackingSession>> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(TrackingSession.serializer())

    override val defaultValue: List<TrackingSession> = emptyList()

    override suspend fun readFrom(source: BufferedSource): List<TrackingSession> {
        val text = source.readUtf8().trim()
        if (text.isEmpty()) return emptyList()
        return try {
            if (text.startsWith("[")) {
                json.decodeFromString(listSerializer, text)
            } else {
                // 旧版单会话文件
                listOf(json.decodeFromString(TrackingSession.serializer(), text))
            }
        } catch (e: SerializationException) {
            throw CorruptionException("tracking_session.json 解析失败", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("tracking_session.json 解析失败", e)
        }
    }

    override suspend fun writeTo(t: List<TrackingSession>, sink: BufferedSink) {
        if (t.isNotEmpty()) {
            sink.writeUtf8(json.encodeToString(listSerializer, t))
        }
    }
}
