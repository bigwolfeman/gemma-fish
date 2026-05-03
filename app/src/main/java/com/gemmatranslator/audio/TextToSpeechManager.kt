package com.gemmatranslator.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "TextToSpeechManager"

/**
 * Wraps Android [TextToSpeech] with:
 * - Coroutine-safe speak() that suspends until the utterance is done
 * - Stereo pan support via [AudioRouter.channelToPan]
 * - Configurable speech rate
 * - Clean release lifecycle
 *
 * Utterances are queued — [TextToSpeech.QUEUE_ADD] — so overlapping calls
 * never drop audio. The caller drives concurrency via coroutines; this class
 * has no internal queue beyond the TTS engine's own queue.
 */
class TextToSpeechManager(
    private val context: Context,
    private val audioRouter: AudioRouter,
) {
    private var tts: TextToSpeech? = null
    private val initStatus = AtomicInteger(TextToSpeech.ERROR)

    // Maps utterance IDs to their resume handles
    private val pendingUtterances = ConcurrentHashMap<String, (Boolean) -> Unit>()

    @Volatile private var speechRate: Float = 1.0f

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialize TTS engine. Must be called before [speak].
     * Suspends until the engine is ready or fails.
     */
    suspend fun initialize(): Boolean = suspendCancellableCoroutine { cont ->
        tts = TextToSpeech(context) { status ->
            initStatus.set(status)
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(utteranceListener)
                Log.d(TAG, "TTS initialized successfully")
                cont.resume(true)
            } else {
                Log.e(TAG, "TTS init failed, status=$status")
                cont.resume(false)
            }
        }
        cont.invokeOnCancellation { release() }
    }

    fun release() {
        tts?.apply {
            stop()
            shutdown()
        }
        tts = null
        pendingUtterances.clear()
        Log.d(TAG, "TTS released")
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.1f, 4.0f)
        tts?.setSpeechRate(speechRate)
    }

    /**
     * Set the default TTS locale. Pass [language] as a BCP-47 tag (e.g. "en-US", "es-ES").
     * Returns true if the locale is supported.
     */
    fun setLanguage(language: String): Boolean {
        val locale = Locale.forLanguageTag(language)
        val result = tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        val ok = result != TextToSpeech.LANG_NOT_SUPPORTED &&
                 result != TextToSpeech.LANG_MISSING_DATA
        if (!ok) Log.w(TAG, "Language $language not supported: $result")
        return ok
    }

    // -------------------------------------------------------------------------
    // Speak
    // -------------------------------------------------------------------------

    /**
     * Speak [text] using [language] locale, routing output to [channel].
     * Suspends until the utterance completes (or errors).
     *
     * Thread-safe: can be called concurrently — utterances are queued.
     *
     * @return true if speech completed successfully.
     */
    suspend fun speak(
        text: String,
        language: String,
        channel: AudioChannel,
    ): Boolean {
        val engine = tts ?: run {
            Log.w(TAG, "speak() called before initialize()")
            return false
        }
        if (text.isBlank()) return true

        val resolvedChannel = audioRouter.resolveChannel(channel)
        val pan = audioRouter.channelToPan(resolvedChannel)
        val utteranceId = UUID.randomUUID().toString()

        // Set language for this utterance
        val locale = Locale.forLanguageTag(language)
        engine.setLanguage(locale)
        engine.setSpeechRate(speechRate)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            // Stream to MUSIC so AudioRouter balance changes affect us
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }

        return suspendCancellableCoroutine { cont ->
            pendingUtterances[utteranceId] = { success -> cont.resume(success) }

            val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                pendingUtterances.remove(utteranceId)
                Log.e(TAG, "TTS speak() returned ERROR for utteranceId=$utteranceId")
                cont.resume(false)
            }

            cont.invokeOnCancellation {
                pendingUtterances.remove(utteranceId)
                // Do not stop() — other queued utterances should continue
            }
        }
    }

    /** Stop current and all pending utterances immediately. */
    fun stopAll() {
        tts?.stop()
        pendingUtterances.values.forEach { it(false) }
        pendingUtterances.clear()
    }

    // -------------------------------------------------------------------------
    // Utterance Listener
    // -------------------------------------------------------------------------

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            Log.v(TAG, "Utterance started: $utteranceId")
        }

        override fun onDone(utteranceId: String) {
            pendingUtterances.remove(utteranceId)?.invoke(true)
            Log.v(TAG, "Utterance done: $utteranceId")
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            pendingUtterances.remove(utteranceId)?.invoke(false)
            Log.e(TAG, "Utterance error: $utteranceId")
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            pendingUtterances.remove(utteranceId)?.invoke(false)
            Log.e(TAG, "Utterance error: $utteranceId, code=$errorCode")
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            pendingUtterances.remove(utteranceId)?.invoke(false)
            Log.v(TAG, "Utterance stopped: $utteranceId, interrupted=$interrupted")
        }
    }
}
