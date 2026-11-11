package com.trackr.core.navigation

import com.trackr.core.model.DataSourceType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationKeyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testScreenKey_homeSerialization() {
        val key: ScreenKey = ScreenKey.Home
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertEquals(ScreenKey.Home, deserialized)
    }

    @Test
    fun testScreenKey_routePlanSerialization() {
        val key: ScreenKey = ScreenKey.RoutePlan
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertEquals(ScreenKey.RoutePlan, deserialized)
    }

    @Test
    fun testScreenKey_searchSerialization() {
        val key: ScreenKey = ScreenKey.Search
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertEquals(ScreenKey.Search, deserialized)
    }

    @Test
    fun testScreenKey_moreSerialization() {
        val key: ScreenKey = ScreenKey.More
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertEquals(ScreenKey.More, deserialized)
    }

    @Test
    fun testScreenKey_aboutSerialization() {
        val key: ScreenKey = ScreenKey.About
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertEquals(ScreenKey.About, deserialized)
    }

    @Test
    fun testScreenKey_lineDetailSerialization_preservesFields() {
        val key: ScreenKey = ScreenKey.LineDetail(
            cityId = "021",
            lineId = "line_999",
            targetOrder = 8,
            stopId = "stop_123",
            source = DataSourceType.MYGOLBS,
            lineName = "49路"
        )
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertTrue(deserialized is ScreenKey.LineDetail)
        val detail = deserialized as ScreenKey.LineDetail
        assertEquals("021", detail.cityId)
        assertEquals("line_999", detail.lineId)
        assertEquals(8, detail.targetOrder)
        assertEquals("stop_123", detail.stopId)
        assertEquals(DataSourceType.MYGOLBS, detail.source)
        assertEquals("49路", detail.lineName)
    }

    @Test
    fun testScreenKey_stopDetailSerialization_preservesFields() {
        val key: ScreenKey = ScreenKey.StopDetail(
            cityId = "021",
            stopId = "stop_555",
            stopName = "陕西南路",
            physicalStId = "pst_001",
            namesakeStId = "nst_001",
            lat = 31.22,
            lng = 121.45
        )
        val serialized = json.encodeToString(key)
        val deserialized = json.decodeFromString<ScreenKey>(serialized)
        assertTrue(deserialized is ScreenKey.StopDetail)
        val detail = deserialized as ScreenKey.StopDetail
        assertEquals("021", detail.cityId)
        assertEquals("stop_555", detail.stopId)
        assertEquals("陕西南路", detail.stopName)
        assertEquals("pst_001", detail.physicalStId)
        assertEquals("nst_001", detail.namesakeStId)
        assertEquals(31.22, detail.lat, 0.0001)
        assertEquals(121.45, detail.lng, 0.0001)
    }

    @Test
    fun stationFavoriteNavigation_roundTripsSourceAndOriginalCity_oldKeysUseDefaults() {
        val key: ScreenKey = ScreenKey.StopDetail(cityId = "saved-city", stopId = "s1", source = DataSourceType.MYGOLBS,
            cityName = "旧城市", mygolbsCityCode = "old-code")
        assertEquals(key, json.decodeFromString<ScreenKey>(json.encodeToString(key)))
        val old = json.decodeFromString<ScreenKey.StopDetail>("""{"cityId":"034","stopId":"s1"}""")
        assertEquals(null, old.source)
        assertEquals("", old.mygolbsCityCode)
        val line = json.decodeFromString<ScreenKey.LineDetail>("""{"cityId":"034","lineId":"line_001"}""")
        assertEquals("", line.boardStopName)
        assertEquals("", line.alightStopName)
    }

}
