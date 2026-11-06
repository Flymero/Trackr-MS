package com.trackr.core.datastore

import androidx.datastore.preferences.core.edit
import com.trackr.core.model.BoardChoice
import com.trackr.core.model.BoardOption
import com.trackr.core.model.CandidateLine
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.WatchLine
import com.trackr.core.model.WatchedLine
import com.trackr.core.model.TrackingLegParams
import com.trackr.core.model.TrackingSession
import com.trackr.core.model.TrackingSessionState
import com.trackr.core.model.TrackingTarget
import com.trackr.core.model.TransitGroup
import com.trackr.core.model.TransitLeg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 三个 DataStore 文件：读写、重开读回、文件损坏不崩 */
class TrackrDataStoresTest {

    private lateinit var dir: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("trackr-datastore").toFile()
    }

    @After
    fun tearDown() {
        runBlocking { scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() } }
        dir.deleteRecursively()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private suspend fun closeAll() {
        scopes.forEach { it.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }
        scopes.clear()
    }

    @Test
    fun settings_writeThenReopen_readsBack() = runBlocking {
        val path = File(dir, TrackrDataStoreFiles.SETTINGS).absolutePath
        val first = createPreferencesDataStore(path, newScope())
        first.edit {
            it[SettingsKeys.ENGINE_MODE] = "MYGOLBS"
            it[SettingsKeys.NEARBY_STOPS_COUNT] = 6
            it[SettingsKeys.LEGACY_CORRUPT_KEYS] = setOf("trackr_prefs/favorites_json")
        }
        closeAll()

        val reopened = createPreferencesDataStore(path, newScope()).data.first()
        assertEquals("MYGOLBS", reopened[SettingsKeys.ENGINE_MODE])
        assertEquals(6, reopened[SettingsKeys.NEARBY_STOPS_COUNT])
        assertEquals(setOf("trackr_prefs/favorites_json"), reopened[SettingsKeys.LEGACY_CORRUPT_KEYS])
    }

    @Test
    fun settings_corruptFile_isReplacedWithEmpty() = runBlocking {
        val file = File(dir, TrackrDataStoreFiles.SETTINGS)
        file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x55))

        val prefs = createPreferencesDataStore(file.absolutePath, newScope()).data.first()
        assertTrue(prefs.asMap().isEmpty())
    }

    @Test
    fun amapKeys_areSeparateFileFromSettings() = runBlocking {
        val settings = createPreferencesDataStore(File(dir, TrackrDataStoreFiles.SETTINGS).absolutePath, newScope())
        val keys = createPreferencesDataStore(File(dir, TrackrDataStoreFiles.AMAP_KEYS).absolutePath, newScope())
        keys.edit { it[AmapKeyKeys.ANDROID] = "android-key" }

        assertNull(settings.data.first()[AmapKeyKeys.ANDROID])
        assertEquals("android-key", keys.data.first()[AmapKeyKeys.ANDROID])
        assertTrue(File(dir, TrackrDataStoreFiles.AMAP_KEYS).exists())
    }

    @Test
    fun trackingSession_roundTripAndClear() = runBlocking {
        val path = File(dir, TrackrDataStoreFiles.TRACKING_SESSION).absolutePath
        val store = createTrackingSessionDataStore(path, newScope())
        assertTrue(store.data.first().isEmpty())

        val group = TransitGroup(
            id = "g1",
            name = "上班",
            legs = listOf(
                TransitLeg(
                    legIndex = 0,
                    fromStopName = "A",
                    toStopName = "B",
                    candidateLines = listOf(CandidateLine(cityId = "c", lineId = "L1", lineName = "59", fromStopOrder = 3, toStopOrder = 7))
                )
            ),
            createdAt = 1L,
            updatedAt = 1L
        )
        val session = TrackingSession(
            sessionId = "s1",
            generation = 3,
            target = TrackingTarget.Route(group, startLeg = 0, selectedLineIds = mapOf(0 to "L1")),
            leg = TrackingLegParams(cityId = "c", lineId = "L1", lineName = "59", targetOrder = 7),
            state = TrackingSessionState(isActive = true, lineId = "L1", lineName = "59", targetOrder = 7, updatedAtMillis = 5L)
        )
        store.updateData { listOf(session) }
        closeAll()

        val reopened = createTrackingSessionDataStore(path, newScope())
        assertEquals(listOf(session), reopened.data.first())
        reopened.updateData { emptyList() }
        assertTrue(reopened.data.first().isEmpty())
        assertEquals(0L, File(path).length())
    }

    @Test
    fun trackingSession_step12File_withoutWatchFields_readsBackWithDefaults() = runBlocking {
        // 第 12 步写的会话没有 watchedLines / boardChoice：读回来给默认值，照常接着追
        val file = File(dir, TrackrDataStoreFiles.TRACKING_SESSION)
        file.writeText(
            """{"sessionId":"s1","generation":2,""" +
                """"target":{"type":"single","cityId":"c","lineId":"L1","lineName":"59","targetOrder":7},""" +
                """"leg":{"cityId":"c","lineId":"L1","lineName":"59","targetOrder":7},""" +
                """"state":{"isActive":true,"lineId":"L1","lineName":"59","targetOrder":7,"legPhase":"SINGLE","updatedAtMillis":5},""" +
                """"interrupted":false}"""
        )

        // 旧版文件里是单个对象：包成一项读出来
        val session = createTrackingSessionDataStore(file.absolutePath, newScope()).data.first().single()
        assertEquals("s1", session.sessionId)
        assertTrue(session.target is TrackingTarget.SingleLine)
        assertTrue(session.watchedLines.isEmpty())
        assertNull(session.boardChoice)
        assertNull(session.watchTarget)
    }

    @Test
    fun trackingSession_watchTogether_roundTrip() = runBlocking {
        val path = File(dir, TrackrDataStoreFiles.TRACKING_SESSION).absolutePath
        val store = createTrackingSessionDataStore(path, newScope())
        val lines = listOf(
            WatchLine(lineId = "L1", lineName = "325", stopOrder = 5),
            WatchLine(lineId = "L2", lineName = "M194", stopOrder = 9, source = DataSourceType.MYGOLBS)
        )
        val session = TrackingSession(
            sessionId = "w1",
            generation = 4,
            target = TrackingTarget.WatchTogether(cityId = "sz", stopId = "st1", stopName = "科技园站", lines = lines, stopLat = 22.5),
            leg = TrackingLegParams(cityId = "sz", lineId = "L1", lineName = "325", targetOrder = 5),
            state = TrackingSessionState(isActive = true, lineId = "L1", lineName = "325", targetOrder = 5, updatedAtMillis = 5L),
            watchedLines = lines.map { WatchedLine(line = it, busId = "b-${it.lineId}", stopsRemaining = 2, hasData = true) },
            boardChoice = BoardChoice(listOf(BoardOption(lineId = "L1", lineName = "325", busId = "b-L1", order = 5)), sinceMillis = 9L)
        )
        store.updateData { listOf(session) }
        closeAll()

        assertEquals(listOf(session), createTrackingSessionDataStore(path, newScope()).data.first())
    }

    @Test
    fun trackingSession_corruptFile_readsAsNoSession() = runBlocking {
        val file = File(dir, TrackrDataStoreFiles.TRACKING_SESSION)
        file.writeText("{\"isActive\":tru")

        assertTrue(createTrackingSessionDataStore(file.absolutePath, newScope()).data.first().isEmpty())
    }
}
