@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.trackr.ui.screens.search

import androidx.compose.runtime.*
import com.trackr.core.domain.platform.SpeechInput
import kotlinx.coroutines.*
import platform.AVFAudio.*
import platform.Foundation.*
import platform.Speech.*
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import kotlin.coroutines.resume

/** The page owns the microphone; disposal, backgrounding and timeout all release it. */
private class IosSpeechInput : SpeechInput {
    private var pending: CompletableDeferred<String?>? = null
    fun cancel() { pending?.complete(null) }

    override suspend fun listen(): String? = withContext(Dispatchers.Main) {
        if (pending != null) return@withContext null
        val completion = CompletableDeferred<String?>()
        pending = completion
        val engine = AVAudioEngine()
        val audio = AVAudioSession.sharedInstance()
        var tapped = false
        var activated = false
        var task: SFSpeechRecognitionTask? = null
        var request: SFSpeechAudioBufferRecognitionRequest? = null
        try {
            val speechAllowed = suspendCancellableCoroutine { continuation ->
                SFSpeechRecognizer.requestAuthorization { status ->
                    if (continuation.isActive) continuation.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
                }
            }
            val microphoneAllowed = if (speechAllowed) suspendCancellableCoroutine { continuation ->
                AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted -> if (continuation.isActive) continuation.resume(granted) }
            } else false
            if (!microphoneAllowed || completion.isCompleted) return@withContext null
            val recognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = "zh-CN")) ?: return@withContext null
            if (!recognizer.available) return@withContext null
            if (!audio.setCategory(AVAudioSessionCategoryRecord, mode = AVAudioSessionModeMeasurement, options = 0u, error = null)) return@withContext null
            if (!audio.setActive(true, error = null)) return@withContext null
            activated = true
            val input = engine.inputNode
            val format = input.outputFormatForBus(0u)
            if (format.sampleRate <= 0.0 || format.channelCount == 0u) return@withContext null
            val recognition = SFSpeechAudioBufferRecognitionRequest().apply { shouldReportPartialResults = true }
            request = recognition
            var latest: String? = null
            task = recognizer.recognitionTaskWithRequest(recognition) { result, error ->
                NSOperationQueue.mainQueue.addOperationWithBlock {
                    if (result != null) latest = result.bestTranscription.formattedString
                    if (result?.final == true || error != null) completion.complete(latest)
                }
            }
            input.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
                if (buffer != null) recognition.appendAudioPCMBuffer(buffer)
            }
            tapped = true
            engine.prepare()
            if (!engine.startAndReturnError(null)) return@withContext null
            (withTimeoutOrNull(12_000) { completion.await() } ?: latest)
                ?.trim()?.trimEnd('。', '，', '.', ',', '？', '?')?.takeIf { it.isNotBlank() }
        } finally {
            engine.stop()
            if (tapped) engine.inputNode.removeTapOnBus(0u)
            request?.endAudio()
            task?.cancel()
            if (activated) audio.setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
            pending = null
        }
    }
}

@Composable
actual fun rememberSpeechInput(): SpeechInput? {
    val input = remember { IosSpeechInput() }
    DisposableEffect(input) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { input.cancel() }
        onDispose { input.cancel(); center.removeObserver(observer) }
    }
    return input
}
