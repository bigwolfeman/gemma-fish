package com.gemmatranslator.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SpeechRecognitionMgr"

/**
 * Wraps Android's [SpeechRecognizer] for continuous, offline-capable speech recognition.
 *
 * Key behaviors:
 * - Emits partial + final results via [recognizedTextFlow]
 * - Auto-restarts after Android's ~60s recognition timeout
 * - Strictly main-thread: SpeechRecognizer is not thread-safe
 * - Lightweight allocation path: reuses the recognition [Intent] across restarts
 */
class SpeechRecognitionManager(
    private val context: Context,
    private val locale: Locale = Locale.getDefault(),
) {
    private val resultChannel = Channel<String>(capacity = Channel.BUFFERED)
    val recognizedTextFlow: Flow<String> = resultChannel.receiveAsFlow()

    private val partialChannel = Channel<String>(capacity = Channel.CONFLATED)
    val partialTextFlow: Flow<String> = partialChannel.receiveAsFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val isListening = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)

    // Reused across restarts — avoids allocation on hot path
    private val recognizerIntent: Intent = buildIntent()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Start continuous recognition. Safe to call from any thread. */
    fun start() {
        if (!isListening.compareAndSet(false, true)) return
        isStopped.set(false)
        mainHandler.post { createAndStartRecognizer() }
    }

    /** Stop recognition and release all resources. Safe to call from any thread. */
    fun stop() {
        isStopped.set(true)
        isListening.set(false)
        mainHandler.post { destroyRecognizer() }
    }

    /** Update the locale and restart recognition if already active. */
    fun setLocale(newLocale: Locale) {
        mainHandler.post {
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, newLocale.toLanguageTag())
            if (isListening.get()) restartRecognizer()
        }
    }

    fun release() {
        stop()
        resultChannel.close()
        partialChannel.close()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /** Must be called on main thread. */
    private fun createAndStartRecognizer() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Must run on main thread" }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return
        }

        destroyRecognizer()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(listener)
            sr.startListening(recognizerIntent)
        }
        Log.d(TAG, "Recognizer started for locale=${recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)}")
    }

    /** Must be called on main thread. */
    private fun destroyRecognizer() {
        recognizer?.apply {
            setRecognitionListener(null)
            stopListening()
            cancel()
            destroy()
        }
        recognizer = null
    }

    /** Restart without changing stopped state — used for timeout recovery. */
    private fun restartRecognizer() {
        if (isStopped.get()) return
        Log.d(TAG, "Restarting recognizer")
        createAndStartRecognizer()
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { partialChannel.trySend(it) }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            val text = texts?.firstOrNull()?.takeIf { it.isNotBlank() } ?: run {
                if (!isStopped.get()) mainHandler.postDelayed({ restartRecognizer() }, 100L)
                return
            }
            val confidence = scores?.firstOrNull() ?: 0f
            Log.d(TAG, "Result: \"$text\" confidence=$confidence")
            if (confidence > 0f && confidence < 0.4f) {
                Log.d(TAG, "Dropping low-confidence result")
                if (!isStopped.get()) mainHandler.postDelayed({ restartRecognizer() }, 100L)
                return
            }
            resultChannel.trySend(text)

            // Android fires onResults then stops. Auto-restart for continuous listening.
            if (!isStopped.get()) {
                mainHandler.postDelayed({ restartRecognizer() }, 100L)
            }
        }

        override fun onError(error: Int) {
            val shouldRestart = when (error) {
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> true       // Normal — just restart
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {   // Backoff
                    mainHandler.postDelayed({ restartRecognizer() }, 500L)
                    false
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                SpeechRecognizer.ERROR_SERVER -> {
                    Log.e(TAG, "Non-recoverable recognition error: $error")
                    isListening.set(false)
                    false
                }
                else -> true
            }
            if (shouldRestart && !isStopped.get()) {
                mainHandler.postDelayed({ restartRecognizer() }, 200L)
            }
        }

        // --- Unused but required by interface ---
        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
