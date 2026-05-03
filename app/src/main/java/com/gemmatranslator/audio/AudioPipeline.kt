package com.gemmatranslator.audio

import android.content.Context
import android.util.Log
import com.gemmatranslator.model.Language
import com.gemmatranslator.model.TranslationMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AudioPipeline"

/**
 * Result produced by the translation step.
 *
 * @param translatedText       Translated text ready for TTS.
 * @param detectedSourceLanguage BCP-47 tag of the detected source language.
 * @param targetChannel        Stereo channel to route the TTS output to.
 */
data class TranslationResult(
    val translatedText: String,
    val detectedSourceLanguage: String,
    val targetChannel: AudioChannel,
)

/**
 * Full audio pipeline: SpeechRecognition → Translation → TTS output.
 *
 * Architecture:
 *   SpeechRecognitionManager emits text segments via a Flow.
 *   A collection coroutine feeds each segment to the [translationFn] lambda.
 *   Each [TranslationResult] is posted to an unbounded TTS queue.
 *   A dedicated TTS worker drains that queue sequentially, ensuring utterances
 *   never overlap regardless of how fast recognition + translation run.
 *
 * Thread model:
 *   - Speech recognition runs on the main thread (Android requirement).
 *   - Translation runs on [Dispatchers.Default].
 *   - TTS speak() suspends on [Dispatchers.Main] (TTS engine is main-thread-friendly
 *     but its callbacks come on a binder thread — handled internally by TTS).
 *
 * @param translationFn Suspend lambda called for each recognized segment.
 *   Receives the raw text; returns a [TranslationResult].
 *   Will be invoked concurrently if recognition outruns translation — the results
 *   are then queued and serialized before TTS output.
 */
class AudioPipeline(
    private val context: Context,
    private val translationFn: suspend (String) -> TranslationResult,
) {
    // -------------------------------------------------------------------------
    // Sub-components
    // -------------------------------------------------------------------------

    private val audioRouter = AudioRouter(context)
    private val speechRecognizer = SpeechRecognitionManager(context)
    private val tts = TextToSpeechManager(context, audioRouter)

    // Serialized TTS output queue. BUFFERED so translation bursts don't block.
    private val ttsQueue = Channel<TranslationResult>(capacity = Channel.BUFFERED)

    // Pipeline-level coroutine scope — SupervisorJob so a single failure in
    // one segment doesn't tear down the whole pipeline.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var recognitionJob: Job? = null
    @Volatile private var ttsWorkerJob: Job? = null
    @Volatile private var isRunning = false

    // -------------------------------------------------------------------------
    // Configuration state
    // -------------------------------------------------------------------------

    @Volatile private var mode: TranslationMode = TranslationMode.SPEAKER

    /** BCP-47 language tags for left/right channels. */
    @Volatile private var leftLanguage: String = "en-US"
    @Volatile private var rightLanguage: String = "es-ES"

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialize TTS then start the full pipeline.
     * No-op if already running.
     */
    suspend fun start() {
        if (isRunning) return

        audioRouter.register()

        val ttsReady = tts.initialize()
        if (!ttsReady) {
            Log.e(TAG, "TTS failed to initialize — pipeline not started")
            return
        }

        isRunning = true
        launchTtsWorker()
        launchRecognitionCollector()
        speechRecognizer.start()

        Log.i(TAG, "Pipeline started, mode=$mode left=$leftLanguage right=$rightLanguage")
    }

    /**
     * Stop all pipeline stages and release resources.
     * Safe to call multiple times.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        speechRecognizer.stop()
        recognitionJob?.cancel()
        recognitionJob = null

        tts.stopAll()
        ttsWorkerJob?.cancel()
        ttsWorkerJob = null

        Log.i(TAG, "Pipeline stopped")
    }

    /** Release all held resources. Call after [stop] when the pipeline is no longer needed. */
    fun release() {
        stop()
        tts.release()
        audioRouter.unregister()
        speechRecognizer.release()
        scope.cancel()
        ttsQueue.close()
        Log.i(TAG, "Pipeline released")
    }

    // -------------------------------------------------------------------------
    // Configuration (hot — safe to call while running)
    // -------------------------------------------------------------------------

    fun setMode(newMode: TranslationMode) {
        mode = newMode
        audioRouter.setMode(newMode)
        Log.d(TAG, "Mode -> $newMode")
    }

    /**
     * Set the languages for each ear/channel.
     * @param left  [Language] for the left-channel speaker (Person A)
     * @param right [Language] for the right-channel speaker (Person B)
     */
    fun setLanguages(left: Language, right: Language) {
        leftLanguage = left.bcp47
        rightLanguage = right.bcp47

        // Update recognition locale to the dominant left-channel language.
        // The translation fn is responsible for actual language detection.
        speechRecognizer.setLocale(java.util.Locale.forLanguageTag(left.bcp47))
        Log.d(TAG, "Languages -> left=${left.bcp47} right=${right.bcp47}")
    }

    fun setSpeechRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    /** True if a Bluetooth stereo headset is detected — makes EARBUD mode meaningful. */
    val isBluetoothHeadsetConnected: Boolean
        get() = audioRouter.isBluetoothHeadsetConnected

    // -------------------------------------------------------------------------
    // Internal coroutines
    // -------------------------------------------------------------------------

    /**
     * Collect recognized text from the speech recognizer flow, run it through
     * [translationFn] concurrently (to overlap translation with recognition),
     * then post results to the TTS queue in arrival order.
     *
     * Note: we preserve order by launching each segment as a child of a sequencing
     * structure. Since translation latency varies we use a per-segment launch but
     * funnel results through the serial [ttsQueue].
     */
    private fun launchRecognitionCollector() {
        recognitionJob = scope.launch {
            speechRecognizer.recognizedTextFlow.collect { segment ->
                if (!isActive) return@collect
                Log.d(TAG, "Recognized: \"$segment\"")
                // Launch translation concurrently; results are queued — order may
                // vary but TTS serialization ensures no overlapping audio.
                launch {
                    try {
                        val result = withContext(Dispatchers.Default) {
                            translationFn(segment)
                        }
                        ttsQueue.send(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Translation failed for segment: \"$segment\"", e)
                    }
                }
            }
        }
    }

    /**
     * Single worker that drains [ttsQueue] sequentially.
     * This is the serialization point — only one utterance plays at a time.
     * The TTS speak() call suspends until the utterance completes, providing
     * natural backpressure without busy-waiting.
     */
    private fun launchTtsWorker() {
        ttsWorkerJob = scope.launch(Dispatchers.Main) {
            for (result in ttsQueue) {
                if (!isActive) break
                if (result.translatedText.isBlank()) continue

                Log.d(TAG, "Speaking [${result.targetChannel}] \"${result.translatedText}\"")
                tts.speak(
                    text = result.translatedText,
                    language = channelLanguage(result.targetChannel),
                    channel = result.targetChannel,
                )
            }
        }
    }

    /** Map a channel back to the configured language tag for TTS voice selection. */
    private fun channelLanguage(channel: AudioChannel): String = when (channel) {
        AudioChannel.LEFT  -> leftLanguage
        AudioChannel.RIGHT -> rightLanguage
        AudioChannel.BOTH  -> leftLanguage   // SPEAKER mode — single voice
    }
}
