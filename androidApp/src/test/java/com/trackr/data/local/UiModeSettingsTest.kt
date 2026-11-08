package com.trackr.data.local

import com.trackr.core.model.SystemThemeSeed
import com.trackr.core.model.ThemeSeed
import com.trackr.core.model.ThemeColorStyle
import com.trackr.core.model.ThemeColorSource
import com.trackr.core.model.ThemeColors
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.trackr.core.data.provider.TransitProviderCoordinator
import com.trackr.core.database.TrackrDatabase
import com.trackr.core.datastore.TrackrDataStoreFiles
import com.trackr.core.model.UiMode
import com.trackr.core.model.ThemeMode
import com.trackr.data.repository.RouteGroupRepository
import com.trackr.data.repository.TransitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 界面模式和「试试长辈版」提示的存取（第 30 步，重构计划 §8.2、§8.7）：
 * 没存过 = 标准版（包括从旧版本升上来、设置里没有这个键的用户）；写入后换新实例读回；新旧存储切换不丢。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UiModeSettingsTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private val opened = mutableListOf<StorageBackend>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        listOf("trackr_prefs", "trackr_route_groups", "trackr_scheduled_reminders", "trackr_amap_keys",
            "trackr_reminder_schedule_state", StoreSwitch.PREFS_NAME).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        File(context.filesDir, TrackrDataStoreFiles.DIRECTORY).deleteRecursively()
        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).delete()
        context.getDatabasePath(TrackrDatabase.FILE_NAME).let { db ->
            if (db.isDirectory) db.deleteRecursively() else context.deleteDatabase(TrackrDatabase.FILE_NAME)
        }
    }

    @After
    fun tearDown() {
        closeAll()
        scope.cancel()
    }

    private fun closeAll() {
        opened.forEach { it.close() }
        opened.clear()
    }

    private fun open(forceLegacy: Boolean = false) = StorageBackend.open(context, scope, forceLegacy).also { opened += it }

    private fun repo(backend: StorageBackend) = TransitRepository(
        context, TransitProviderCoordinator(emptyMap()), backend.transitStore,
        RouteGroupRepository.openFavorites(backend.favoritesStore), scope
    )

    @Test
    fun freshInstall_isStandard_andPromptNotDismissed() {
        val viaNew = repo(open())
        assertEquals(UiMode.Standard, viaNew.uiMode.value)
        assertFalse(viaNew.fontScalePromptDismissed.value)

        val viaLegacy = repo(open(forceLegacy = true))
        assertEquals(UiMode.Standard, viaLegacy.uiMode.value)
        assertFalse(viaLegacy.fontScalePromptDismissed.value)
    }

    @Test
    fun oldVersionSettingsWithoutKey_readStandard() {
        // 旧版本的设置：有别的键，没有 ui_mode / font_scale_prompt_dismissed
        context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE).edit()
            .putString("engine_mode", "MYGOLBS")
            .putInt("nearby_stops_count", 6)
            .putBoolean("is_live_notification_enabled", false)
            .commit()

        val viaNew = repo(open())
        assertEquals(6, viaNew.nearbyStopsCount.value) // 旧设置确实读到了
        assertEquals(UiMode.Standard, viaNew.uiMode.value)
        assertFalse(viaNew.fontScalePromptDismissed.value)

        val viaLegacy = repo(open(forceLegacy = true))
        assertEquals(UiMode.Standard, viaLegacy.uiMode.value)
        assertFalse(viaLegacy.fontScalePromptDismissed.value)
    }

    @Test
    fun written_readBackByNewInstance() = runBlocking {
        val first = repo(open())
        first.setUiMode(UiMode.Elder)
        first.dismissFontScalePrompt()
        first.flushWrites()
        closeAll()

        val reopened = repo(open())
        assertEquals(UiMode.Elder, reopened.uiMode.value)
        assertTrue(reopened.fontScalePromptDismissed.value)

        reopened.setUiMode(UiMode.Standard)
        reopened.flushWrites()
        closeAll()
        assertEquals(UiMode.Standard, repo(open()).uiMode.value)
    }

    @Test
    fun legacyStore_writtenReadBack() = runBlocking {
        val first = repo(open(forceLegacy = true))
        first.setUiMode(UiMode.Elder)
        first.dismissFontScalePrompt()
        first.flushWrites()
        closeAll()

        val reopened = repo(open(forceLegacy = true))
        assertEquals(UiMode.Elder, reopened.uiMode.value)
        assertTrue(reopened.fontScalePromptDismissed.value)
    }

    @Test
    fun unknownValue_readsStandard() {
        context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE).edit()
            .putString("ui_mode", "Huge")
            .commit()
        assertEquals(UiMode.Standard, repo(open(forceLegacy = true)).uiMode.value)
        assertEquals(UiMode.Standard, repo(open()).uiMode.value)
    }

    @Test
    fun switchOldThenNew_bringsBackModeSetInOldStore() = runBlocking {
        repo(open())
        closeAll()

        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("old\n")
        val legacy = open()
        assertFalse(legacy.usesNewStore)
        val transit = repo(legacy)
        transit.setUiMode(UiMode.Elder)
        transit.dismissFontScalePrompt()
        transit.flushWrites()
        closeAll()

        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("new")
        val back = open()
        assertTrue(back.usesNewStore)
        val seen = repo(back)
        assertEquals(UiMode.Elder, seen.uiMode.value)
        assertTrue(seen.fontScalePromptDismissed.value)
    }

    @Test
    fun themeMode_defaultsToSystemInBothStores() {
        assertEquals(ThemeMode.System, repo(open()).themeMode.value)
        assertEquals(ThemeMode.System, repo(open(forceLegacy = true)).themeMode.value)
    }

    @Test
    fun themeMode_allChoicesSurviveReopeningBothStores() = runBlocking {
        for (legacy in listOf(false, true)) {
            for (mode in listOf(ThemeMode.Dark, ThemeMode.Light, ThemeMode.System)) {
                val first = repo(open(forceLegacy = legacy))
                first.setThemeMode(mode)
                assertEquals(mode, first.themeMode.value)
                first.flushWrites()
                closeAll()
                assertEquals(mode, repo(open(forceLegacy = legacy)).themeMode.value)
                closeAll()
            }
        }
    }

    @Test
    fun themeMode_unknownValueFallsBackToSystem() {
        context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", "unknown").commit()
        assertEquals(ThemeMode.System, repo(open(forceLegacy = true)).themeMode.value)
        assertEquals(ThemeMode.System, repo(open()).themeMode.value)
    }

    @Test
    fun themeMode_switchOldThenNewImportsChoiceWithoutDeletingLegacyKey() = runBlocking {
        repo(open())
        closeAll()
        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("old")
        val old = repo(open())
        old.setThemeMode(ThemeMode.Dark)
        old.flushWrites()
        closeAll()

        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("new")
        assertEquals(ThemeMode.Dark, repo(open()).themeMode.value)
        assertEquals("Dark", context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE).getString("theme_mode", null))
    }
    @Test
    fun themeColors_surviveReopeningBothStoresWithAllChoices() = runBlocking {
        for (legacy in listOf(false, true)) {
            for (source in ThemeColorSource.entries) {
                val choice = ThemeColors(
                    source = source,
                    preset = ThemeSeed.Teal,
                    customRgb = 0xCF304A,
                    style = ThemeColorStyle.Fidelity,
                    systemSeed = SystemThemeSeed.Tertiary,
                    followSystem = false,
                )
                val first = repo(open(forceLegacy = legacy))
                first.setThemeColors(choice)
                first.flushWrites()
                closeAll()
                assertEquals(choice, repo(open(forceLegacy = legacy)).themeColors.value)
                closeAll()
            }
        }
    }

    @Test
    fun themeColors_oldToNewImportsChoiceWithoutDeletingLegacyKey() = runBlocking {
        repo(open())
        closeAll()
        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("old")
        val old = repo(open())
        val choice = ThemeColors(style = ThemeColorStyle.Monochrome)
        old.setThemeColors(choice)
        old.flushWrites()
        closeAll()
        val raw = context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE).getString("theme_colors", null)
        File(context.filesDir, StoreSwitch.OVERRIDE_FILE).writeText("new")
        assertEquals(choice, repo(open()).themeColors.value)
        assertEquals(raw, context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE).getString("theme_colors", null))
    }

    @Test
    fun themeColors_missingOrCorruptPreferenceFallsBackSafely() {
        assertEquals(ThemeColors(), repo(open()).themeColors.value)
        context.getSharedPreferences("trackr_prefs", Context.MODE_PRIVATE).edit().putString("theme_colors", "{broken").commit()
        assertEquals(ThemeColors(), repo(open(forceLegacy = true)).themeColors.value)
    }

}
