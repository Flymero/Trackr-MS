package com.trackr.core.navigation

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackrTransitionsTest {
    @Test
    fun realEntryContentKeysRecognizeTabSwitchesInBothDirections() {
        val settings = NavEntry<NavKey>(ScreenKey.More) {}.contentKey
        val lines = NavEntry<NavKey>(ScreenKey.Lines) {}.contentKey
        assertTrue(TrackrTransitions.isTabRootSwitch(settings, lines))
        assertTrue(TrackrTransitions.isTabRootSwitch(lines, settings))
        assertFalse(TrackrTransitions.isTabRootSwitch(lines, lines))
        val detail = NavEntry<NavKey>(ScreenKey.LineDetail("ms", "ms:1_1_0")) {}.contentKey
        assertFalse(TrackrTransitions.isTabRootSwitch(lines, detail))
        assertFalse(TrackrTransitions.isTabRootSwitch(detail, lines))
    }
}
