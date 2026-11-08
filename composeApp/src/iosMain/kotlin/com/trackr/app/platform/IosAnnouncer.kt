@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.trackr.app.platform

import com.trackr.core.domain.platform.Announcer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFAudio.*

class IosAnnouncer : Announcer {
    private val synthesizer = AVSpeechSynthesizer()
    override suspend fun speak(text: String): Boolean = withContext(Dispatchers.Main) {
        if (text.isBlank()) return@withContext false
        val voice = AVSpeechSynthesisVoice.voiceWithLanguage("zh-CN") ?: return@withContext false
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        synthesizer.speakUtterance(AVSpeechUtterance.speechUtteranceWithString(text).apply {
            this.voice = voice
            rate = AVSpeechUtteranceDefaultSpeechRate
        })
        true
    }
    override fun stop() { synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }
}
