package com.trackr.core.data.repository

import com.trackr.core.data.local.SerialWriter
import com.trackr.core.data.local.TransitLocalSnapshot
import com.trackr.core.data.local.TransitLocalStore
import com.trackr.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Persistent settings for non-Android hosts. Initialize off the UI thread before creating ViewModels. */
class DefaultSettingsRepository private constructor(
    private val store: TransitLocalStore,
    private val writer: SerialWriter,
    private val initial: TransitLocalSnapshot
) : SettingsRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _selectedCity = MutableStateFlow(loadInitialCity())
    override val selectedCity: StateFlow<TransitCity> = _selectedCity.asStateFlow()

    private val _hasSavedCity = MutableStateFlow(initial.selectedCityJson != null)
    override val hasSavedCity: StateFlow<Boolean> = _hasSavedCity.asStateFlow()

    private val _engineMode = MutableStateFlow(loadInitialEngineMode())
    override val engineMode: StateFlow<EngineMode> = _engineMode.asStateFlow()


    private val _preferredTransitCodeProvider = MutableStateFlow(TransitCodeProvider.fromId(initial.transitCodeProvider ?: TransitCodeProvider.ASK_ALWAYS.id))
    override val preferredTransitCodeProvider: StateFlow<TransitCodeProvider> = _preferredTransitCodeProvider.asStateFlow()

    private val _showTransitCodeButton = MutableStateFlow(initial.showTransitCodeButton ?: true)
    override val showTransitCodeButton: StateFlow<Boolean> = _showTransitCodeButton.asStateFlow()

    private val _autoStopAfterBoarding = MutableStateFlow(initial.autoStopAfterBoarding ?: true)
    override val autoStopAfterBoarding: StateFlow<Boolean> = _autoStopAfterBoarding.asStateFlow()

    private val _nearbyStopsCount = MutableStateFlow((initial.nearbyStopsCount ?: 4).coerceIn(1, 16))
    override val nearbyStopsCount: StateFlow<Int> = _nearbyStopsCount.asStateFlow()

    private val _isLiveNotificationEnabled = MutableStateFlow(initial.liveNotificationEnabled ?: true)
    override val isLiveNotificationEnabled: StateFlow<Boolean> = _isLiveNotificationEnabled.asStateFlow()

    private val _showOnLockScreen = MutableStateFlow(initial.showOnLockScreen ?: true)
    override val showOnLockScreen: StateFlow<Boolean> = _showOnLockScreen.asStateFlow()

    private val _alertThresholdStops = MutableStateFlow(
        (initial.alertThresholdStops ?: AlertThreshold.DEFAULT_STOPS).coerceIn(AlertThreshold.MIN_STOPS, AlertThreshold.MAX_STOPS)
    )
    override val alertThresholdStops: StateFlow<Int> = _alertThresholdStops.asStateFlow()

    private val _uiMode = MutableStateFlow(UiMode.entries.firstOrNull { it.name == initial.uiMode } ?: UiMode.Standard)
    override val uiMode: StateFlow<UiMode> = _uiMode.asStateFlow()

    private val _railMode = MutableStateFlow(RailMode.entries.firstOrNull { it.name == initial.railMode } ?: RailMode.Horizontal)
    override val railMode: StateFlow<RailMode> = _railMode.asStateFlow()

    private val _themeColors = MutableStateFlow(
        initial.themeColorsJson?.let { runCatching { json.decodeFromString<ThemeColors>(it).normalized() }.getOrNull() } ?: ThemeColors()
    )
    override val themeColors: StateFlow<ThemeColors> = _themeColors.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.entries.firstOrNull { it.name == initial.themeMode } ?: ThemeMode.System)
    override val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _fontScalePromptDismissed = MutableStateFlow(initial.fontScalePromptDismissed ?: false)
    override val fontScalePromptDismissed: StateFlow<Boolean> = _fontScalePromptDismissed.asStateFlow()

    private fun loadInitialCity(): TransitCity {
        val cityJson = initial.selectedCityJson
        if (!cityJson.isNullOrBlank()) {
            try {
                val city = json.decodeFromString<TransitCity>(cityJson)
                val prov = if (city.province.isNotBlank()) city.province else ProvinceCityCatalog.inferProvince(city.cityName)
                return city.copy(province = prov)
            } catch (_: Exception) {}
        }
        return TransitCity(cityId = "034", cityName = "上海", pinyin = "ShangHai", supportSubway = true, isHot = true, province = "直辖市")
    }

    override fun setNearbyStopsCount(count: Int) {
        val clamped = count.coerceIn(1, 16)
        _nearbyStopsCount.value = clamped
        writer.enqueue { store.saveNearbyStopsCount(clamped) }
    }

    override fun setLiveNotificationEnabled(enabled: Boolean) {
        _isLiveNotificationEnabled.value = enabled
        writer.enqueue { store.saveLiveNotificationEnabled(enabled) }
    }

    override fun setShowOnLockScreen(enabled: Boolean) {
        _showOnLockScreen.value = enabled
        writer.enqueue { store.saveShowOnLockScreen(enabled) }
    }

    override fun setShowTransitCodeButton(enabled: Boolean) {
        _showTransitCodeButton.value = enabled
        writer.enqueue { store.saveShowTransitCodeButton(enabled) }
    }

    override fun setAutoStopAfterBoarding(enabled: Boolean) {
        _autoStopAfterBoarding.value = enabled
        writer.enqueue { store.saveAutoStopAfterBoarding(enabled) }
    }

    override fun setAlertThresholdStops(stops: Int) {
        val clamped = stops.coerceIn(AlertThreshold.MIN_STOPS, AlertThreshold.MAX_STOPS)
        _alertThresholdStops.value = clamped
        writer.enqueue { store.saveAlertThresholdStops(clamped) }
    }

    override fun setUiMode(mode: UiMode) {
        _uiMode.value = mode
        writer.enqueue { store.saveUiMode(mode.name) }
    }

    override fun setRailMode(mode: RailMode) {
        _railMode.value = mode
        writer.enqueue { store.saveRailMode(mode.name) }
    }

    override fun setThemeColors(colors: ThemeColors) {
        val normalized = colors.normalized()
        _themeColors.value = normalized
        writer.enqueue { store.saveThemeColors(json.encodeToString(normalized)) }
    }

    override fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        writer.enqueue { store.saveThemeMode(mode.name) }
    }

    override fun dismissFontScalePrompt() {
        if (_fontScalePromptDismissed.value) return
        _fontScalePromptDismissed.value = true
        writer.enqueue { store.saveFontScalePromptDismissed(true) }
    }

    private fun loadInitialEngineMode(): EngineMode {
        val modeStr = initial.engineMode ?: EngineMode.CHELAILE.name
        return runCatching { EngineMode.valueOf(modeStr) }.getOrDefault(EngineMode.CHELAILE)
    }

    override fun setEngineMode(mode: EngineMode) {
        _engineMode.value = mode
        writer.enqueue { store.saveEngineMode(mode.name) }
    }

    override suspend fun selectCity(city: TransitCity) {
        val effectiveCity = if (city.province.isBlank()) {
            city.copy(province = ProvinceCityCatalog.inferProvince(city.cityName))
        } else {
            city
        }
        _selectedCity.value = effectiveCity
        _hasSavedCity.value = true
        val str = json.encodeToString(effectiveCity)
        writer.enqueue { store.saveSelectedCityJson(str) }.join()
    }

    override suspend fun setPreferredTransitCodeProvider(provider: TransitCodeProvider) {
        _preferredTransitCodeProvider.value = provider
        writer.enqueue { store.saveTransitCodeProvider(provider.id) }.join()
    }


    suspend fun flushWrites() = writer.flush()

    companion object {
        suspend fun create(store: TransitLocalStore, writer: SerialWriter) =
            DefaultSettingsRepository(store, writer, store.load())
    }
}
