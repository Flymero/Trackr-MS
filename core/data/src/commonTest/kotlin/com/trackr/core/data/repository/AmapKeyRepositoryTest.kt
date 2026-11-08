package com.trackr.core.data.repository

import com.trackr.core.data.local.AmapKeyStore
import com.trackr.core.data.local.SerialWriter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 高德 Key 两槽的规则（计划 §2.5、Q18、D2）：各槽「用户填的 > 构建时的」，互不回落；
 * `hasMapKey` = 有一个；`canShowMap` = 有 Android Key。计划里叫 SettingsRepositoryTest 的那一条
 * （原 `FavoritesGroupViewModelTest.customAmapApiKey_doesNotFallBackToLegacyWebSlot`）在这里。
 */
class AmapKeyRepositoryTest {

    private class MemoryStore(var android: String? = null, var web: String? = null, var ios: String? = null) : AmapKeyStore {
        override suspend fun loadIosKey() = ios
        override suspend fun saveIosKey(key: String?) { ios = key }
        override suspend fun loadAndroidKey(): String? = android
        override suspend fun loadWebKey(): String? = web
        override suspend fun saveAndroidKey(key: String?) { android = key }
        override suspend fun saveWebKey(key: String?) { web = key }
    }

    private suspend fun repo(
        store: MemoryStore,
        defaults: AmapKeyDefaults = AmapKeyDefaults.NONE,
        scope: kotlinx.coroutines.CoroutineScope
    ) = DefaultAmapKeyRepository.create(store, SerialWriter(scope), defaults)

    @Test
    fun noKeyAtAll_hidesRouteTabAndMap() = runTest {
        val r = repo(MemoryStore(), scope = this)
        assertFalse(r.hasMapKey.value)
        assertFalse(r.canShowMap.value)
        assertEquals("", r.effectiveAndroidKey.value)
        assertEquals("", r.effectiveWebKey.value)
    }

    @Test
    fun onlyWebKey_showsRouteTabWithoutMap() = runTest {
        val r = repo(MemoryStore(web = "web_1"), scope = this)
        assertTrue(r.hasMapKey.value)
        assertFalse(r.canShowMap.value)
        assertEquals("web_1", r.effectiveWebKey.value)
    }

    @Test
    fun onlyAndroidKey_showsRouteTabAndMap() = runTest {
        val r = repo(MemoryStore(android = "and_1"), scope = this)
        assertTrue(r.hasMapKey.value)
        assertTrue(r.canShowMap.value)
        assertEquals("", r.effectiveWebKey.value)
    }

    @Test
    fun customAmapApiKey_doesNotFallBackToLegacyWebSlot() = runTest {
        // 旧版本单输入框的值留在 Web 槽：不当作 Android Key 喂给 SDK，也不因此画地图
        val r = repo(MemoryStore(web = "legacy_single_key"), scope = this)
        assertNull(r.androidKey.value)
        assertEquals("", r.effectiveAndroidKey.value)
        assertEquals("legacy_single_key", r.webKey.value)
        assertFalse(r.canShowMap.value)
    }

    @Test
    fun androidSlotEmpty_doesNotReadWebSlot_andWebSlotDoesNotReadAndroidSlot() = runTest {
        val r = repo(MemoryStore(), scope = this)
        r.setWebKey("web_only")
        assertEquals("", r.effectiveAndroidKey.value)
        r.setWebKey(null)
        r.setAndroidKey("and_only")
        assertEquals("", r.effectiveWebKey.value)
        assertEquals("and_only", r.effectiveAndroidKey.value)
    }

    @Test
    fun userKeyBeatsBuildKey_perSlot() = runTest {
        val defaults = AmapKeyDefaults(androidKey = "build_and", webKey = "build_web")
        val r = repo(MemoryStore(android = "user_and"), defaults, this)
        assertEquals("user_and", r.effectiveAndroidKey.value)
        assertEquals("build_web", r.effectiveWebKey.value)
        assertTrue(r.canShowMap.value)

        r.setAndroidKey("  ")
        assertEquals("build_and", r.effectiveAndroidKey.value)
    }

    @Test
    fun buildKeysAlone_countAsConfigured() = runTest {
        val r = repo(MemoryStore(), AmapKeyDefaults(webKey = " build_web "), this)
        assertTrue(r.hasMapKey.value)
        assertFalse(r.canShowMap.value)
        assertEquals("build_web", r.effectiveWebKey.value)
    }

    @Test
    fun settersUpdateStatusAtOnce_andPersistTrimmedValuesPerSlot() = runTest {
        val store = MemoryStore()
        val r = repo(store, scope = this)

        r.setWebKey("  web_2  ")
        assertTrue(r.hasMapKey.value)
        assertFalse(r.canShowMap.value)

        r.setAndroidKey("and_2")
        assertTrue(r.canShowMap.value)

        r.setAndroidKey("")
        r.setWebKey(null)
        assertFalse(r.hasMapKey.value)
        assertFalse(r.canShowMap.value)

        r.setWebKey(" web_3 ")
        r.flush()
        assertNull(store.android)
        assertEquals("web_3", store.web)
    }

    @Test
    fun platformWithoutAndroidMapSdk_ignoresAndroidSlot() = runTest {
        // iOS（第 38 步之前）：只看 Web Key，永远不画地图
        val noSdk = AmapKeyDefaults(androidKey = "build_and", hasAndroidMapSdk = false)
        val r = repo(MemoryStore(android = "user_and"), noSdk, this)
        assertFalse(r.hasMapKey.value)
        assertFalse(r.canShowMap.value)

        r.setWebKey("web")
        assertTrue(r.hasMapKey.value)
        assertFalse(r.canShowMap.value)
    }

    @Test
    fun rules_effectiveTrimsAndFallsBackOnlyToSameSlotBuildKey() {
        assertEquals("a", AmapKeyRules.effective(" a ", "b"))
        assertEquals("b", AmapKeyRules.effective(null, " b "))
        assertEquals("b", AmapKeyRules.effective("   ", "b"))
        assertEquals("", AmapKeyRules.effective(null, ""))
    }
    @Test fun iosSlotIsIndependentAndSurvivesReopen() = runTest {
        val store = MemoryStore(android = "android", web = "web")
        val defaults = AmapKeyDefaults(hasAndroidMapSdk = false, hasIosMapSdk = true, iosKey = "built_ios")
        val r = repo(store, defaults, this)
        assertEquals("built_ios", r.effectiveIosKey.value)
        assertEquals("", r.effectiveAndroidKey.value)
        r.setIosKey(" user_ios ")
        r.flush()
        assertEquals("android", store.android)
        assertEquals("web", store.web)
        assertEquals("user_ios", repo(store, defaults, this).effectiveIosKey.value)
        r.setIosKey(null)
        assertEquals("built_ios", r.effectiveIosKey.value)
        assertEquals("web", r.effectiveWebKey.value)
        assertTrue(r.canShowMap.value)
    }

    @Test fun iosSlotNeverEnablesAndroidMapAndSimulatorDoesNotPretendToHaveSdk() {
        val android = AmapKeyRules.status(null, null, AmapKeyDefaults(), "ios")
        assertFalse(android.hasMapKey)
        assertFalse(android.canShowMap)
        val simulator = AmapKeyRules.status("android", "web", AmapKeyDefaults(hasAndroidMapSdk = false), "ios")
        assertTrue(simulator.hasMapKey)
        assertFalse(simulator.canShowMap)
    }

}
