package com.gemmatranslator.translation

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.lm.LlmInference
import com.google.ai.edge.litert.lm.LlmInferenceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation engine backed by Gemma 4 E2B running via LiteRT-LM.
 *
 * Architecture notes
 * ------------------
 *  • All LiteRT calls are funnelled through [runInference] — the single
 *    private method that holds the [inferenceMutex].  If the API surface
 *    changes (e.g. a new LiteRT-LM release), only that function needs
 *    updating.
 *  • Model loading is async and reports progress through [loadingState].
 *  • Language detection uses a two-tier strategy: fast Unicode heuristics
 *    first, model fallback only when needed.
 *  • Temperature=0 / topK=1 for deterministic, highest-quality output.
 *
 * Lifecycle
 * ---------
 *  Call [load] once (typically from Application.onCreate or a ViewModel).
 *  Observe [loadingState] to gate UI.  Call [close] when done (e.g. in
 *  ViewModel.onCleared).
 *
 * @param context   Application context (used for asset/file resolution).
 * @param modelPath Absolute path to the Gemma E2B INT4 .bin model file.
 *                  Typically stored under getExternalFilesDir() or assets.
 */
class TranslationEngine(
    private val context: Context,
    private val modelPath: String,
) : AutoCloseable {

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    sealed class LoadingState {
        object Idle        : LoadingState()
        data class Loading(val progress: Float) : LoadingState()  // 0..1
        object Ready       : LoadingState()
        data class Error(val cause: Throwable) : LoadingState()
    }

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState

    val isReady: Boolean get() = _loadingState.value is LoadingState.Ready

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "TranslationEngine"

        // Token budgets
        private const val TRANSLATE_MAX_TOKENS  = 512
        private const val DETECT_MAX_TOKENS     = 8   // just a 2-3 char code
        private const val MIN_TRANSLATE_TOKENS  = 64

        // LiteRT-LM model configuration
        private const val TEMPERATURE  = 0.0f  // deterministic
        private const val TOP_K        = 1
    }

    /** Guards all [llmInference] accesses — LiteRT-LM is not thread-safe. */
    private val inferenceMutex = Mutex()

    /** Nullable until [load] succeeds. */
    @Volatile private var llmInference: LlmInference? = null

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Language detector wired to the model for ambiguous (Latin-script) cases.
     */
    val languageDetector = LanguageDetector { text ->
        runInference(
            prompt    = PromptTemplates.languageDetection(text),
            maxTokens = DETECT_MAX_TOKENS,
        )
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Asynchronously loads the Gemma model. Safe to call multiple times;
     * subsequent calls while loading or ready are no-ops.
     *
     * @param onProgress Optional callback fired on the calling thread with
     *                   a [0..1] progress fraction. Primarily useful for
     *                   showing a progress bar since LiteRT model loading
     *                   can take a few seconds on first launch.
     */
    fun load(onProgress: ((Float) -> Unit)? = null) {
        val current = _loadingState.value
        if (current is LoadingState.Loading || current is LoadingState.Ready) return

        engineScope.launch {
            _loadingState.value = LoadingState.Loading(0f)
            try {
                // Phase 1: build options (~0% → 10%)
                onProgress?.invoke(0.05f)
                _loadingState.value = LoadingState.Loading(0.05f)

                val inference = withContext(Dispatchers.IO) {
                    buildLlmInference()
                }

                // Phase 2: model loaded into memory (~100%)
                _loadingState.value = LoadingState.Loading(1f)
                onProgress?.invoke(1f)

                llmInference = inference
                _loadingState.value = LoadingState.Ready
                Log.i(TAG, "Gemma E2B model loaded from $modelPath")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Gemma model", e)
                _loadingState.value = LoadingState.Error(e)
            }
        }
    }

    override fun close() {
        llmInference?.close()
        llmInference = null
        _loadingState.value = LoadingState.Idle
    }

    // -------------------------------------------------------------------------
    // Core translation API
    // -------------------------------------------------------------------------

    /**
     * Translates [text] from [sourceLang] to [targetLang].
     *
     * Suspends on [Dispatchers.Default]; safe to call from any coroutine.
     * Throws [IllegalStateException] if the engine is not ready.
     *
     * @param text       Input text to translate.
     * @param sourceLang ISO-639-1 source language code (e.g. "en").
     * @param targetLang ISO-639-1 target language code (e.g. "fr").
     * @return Translated text, stripped of any model preamble.
     */
    suspend fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
    ): String {
        requireReady()
        if (text.isBlank()) return ""

        val maxTokens = PromptTemplates
            .estimateTranslationTokens(text, TRANSLATE_MAX_TOKENS)
            .coerceAtLeast(MIN_TRANSLATE_TOKENS)

        val prompt = PromptTemplates.translation(text, sourceLang, targetLang)
        val raw    = runInference(prompt, maxTokens)
        return raw.trim()
    }

    /**
     * Translates [text] to [targetLang] without specifying a source language.
     * Slightly faster prompt — use when source language is already known to
     * the model (e.g. auto-detected upstream).
     */
    suspend fun translateToTarget(text: String, targetLang: String): String {
        requireReady()
        if (text.isBlank()) return ""

        val maxTokens = PromptTemplates
            .estimateTranslationTokens(text, TRANSLATE_MAX_TOKENS)
            .coerceAtLeast(MIN_TRANSLATE_TOKENS)

        val prompt = PromptTemplates.translationToTarget(text, targetLang)
        val raw    = runInference(prompt, maxTokens)
        return raw.trim()
    }

    /**
     * Detects the language of [text].
     * Uses [LanguageDetector]: fast Unicode heuristics first, model fallback
     * for Latin-script ambiguity.
     *
     * Returns an ISO-639-1 code or [LanguageDetector.UNKNOWN_LANG].
     */
    suspend fun detectLanguage(text: String): String =
        languageDetector.detectLanguage(text)

    /**
     * Streaming translation — delivers partial results as the model generates.
     * Useful for long texts where first-byte latency matters.
     *
     * @param onPartial Called on each incremental chunk of output text.
     * @param onDone    Called once generation is complete with the full result.
     */
    suspend fun translateStreaming(
        text: String,
        sourceLang: String,
        targetLang: String,
        onPartial: (String) -> Unit,
        onDone: (String) -> Unit,
    ) {
        requireReady()
        if (text.isBlank()) { onDone(""); return }

        val maxTokens = PromptTemplates
            .estimateTranslationTokens(text, TRANSLATE_MAX_TOKENS)
            .coerceAtLeast(MIN_TRANSLATE_TOKENS)

        val prompt = PromptTemplates.translation(text, sourceLang, targetLang)
        runInferenceStreaming(prompt, maxTokens, onPartial, onDone)
    }

    // -------------------------------------------------------------------------
    // LiteRT-LM isolation layer
    // -------------------------------------------------------------------------

    /**
     * THE SINGLE METHOD THAT CALLS LITERT-LM.
     *
     * All inference is routed through here so the API surface is contained.
     * If LiteRT-LM's API changes, update only this function and
     * [buildLlmInference].
     *
     * [inferenceMutex] ensures only one request runs at a time — LiteRT-LM
     * is not thread-safe.
     */
    private suspend fun runInference(prompt: String, maxTokens: Int): String =
        inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                val inference = llmInference
                    ?: throw IllegalStateException("LlmInference is null — model not loaded")

                // LiteRT-LM 0.1.0 synchronous API
                // NOTE: If the API is updated, adapt here only.
                inference.generateResponse(prompt)
            }
        }

    /**
     * Streaming variant routed through the same isolation layer.
     * Uses [suspendCancellableCoroutine] to bridge the callback-based
     * LiteRT API to Kotlin coroutines.
     */
    private suspend fun runInferenceStreaming(
        prompt: String,
        maxTokens: Int,
        onPartial: (String) -> Unit,
        onDone: (String) -> Unit,
    ) = inferenceMutex.withLock {
        suspendCancellableCoroutine { cont ->
            val inference = llmInference
                ?: run {
                    cont.resumeWithException(
                        IllegalStateException("LlmInference is null — model not loaded")
                    )
                    return@suspendCancellableCoroutine
                }

            val fullBuilder = StringBuilder()

            // LiteRT-LM 0.1.0 async API
            // NOTE: If the callback signature changes, adapt here only.
            inference.generateResponseAsync(prompt) { partialResult, done ->
                if (partialResult != null) {
                    fullBuilder.append(partialResult)
                    onPartial(partialResult)
                }
                if (done) {
                    onDone(fullBuilder.toString().trim())
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            cont.invokeOnCancellation {
                // LiteRT-LM 0.1.0 does not expose a cancel API.
                // Log so we can add it when the SDK matures.
                Log.w(TAG, "Streaming cancelled — LiteRT cancel not yet supported")
            }
        }
    }

    /**
     * Constructs [LlmInferenceOptions] and creates the [LlmInference] instance.
     * Must be called from a background thread (IO-bound model file loading).
     *
     * NOTE: If LlmInferenceOptions builder API changes, update here only.
     */
    private fun buildLlmInference(): LlmInference {
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(TRANSLATE_MAX_TOKENS)
            .setTemperature(TEMPERATURE)
            .setTopK(TOP_K)
            .build()

        return LlmInference.createFromOptions(context, options)
    }

    // -------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------

    private fun requireReady() {
        check(_loadingState.value is LoadingState.Ready) {
            "TranslationEngine is not ready (state=${_loadingState.value}). " +
                "Await loadingState == Ready before calling translate()."
        }
    }
}
