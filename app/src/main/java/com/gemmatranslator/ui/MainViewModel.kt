package com.gemmatranslator.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemmatranslator.audio.AudioChannel
import com.gemmatranslator.audio.AudioPipeline
import com.gemmatranslator.audio.TranslationResult
import com.gemmatranslator.model.Language
import com.gemmatranslator.model.TranslationEntry
import com.gemmatranslator.model.TranslationMode
import com.gemmatranslator.model.TranslatorUiState
import com.gemmatranslator.translation.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Central ViewModel that wires UI ↔ [AudioPipeline] ↔ [TranslationEngine].
 *
 * Lifecycle contract
 * ------------------
 * • [loadModel] is called once on app start (triggered from the Compose entry point).
 * • The [AudioPipeline] is created lazily on first [toggleListening] call so that
 *   RECORD_AUDIO permission is already granted at that point.
 * • [onCleared] stops the pipeline and releases the engine.
 *
 * Threading
 * ---------
 * [viewModelScope] defaults to [Dispatchers.Main.immediate] — all [_uiState] updates
 * happen there. The pipeline and engine do their heavy work on [Dispatchers.Default]
 * and [Dispatchers.IO] internally; the callback resumes on the pipeline's thread but
 * state mutations are posted back to Main via [MutableStateFlow.update] which is safe
 * to call from any thread.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
    }

    // ── Dependencies ────────────────────────────────────────────────────────

    private val engine: TranslationEngine = TranslationEngine(
        context   = application,
        modelPath = resolveModelPath(application),
    )

    private fun resolveModelPath(app: Application): String {
        val externalFile = File(app.getExternalFilesDir(null), MODEL_FILE_NAME)
        if (externalFile.exists()) return externalFile.absolutePath
        val internalFile = File(app.filesDir, MODEL_FILE_NAME)
        if (internalFile.exists()) return internalFile.absolutePath
        return externalFile.absolutePath
    }

    /**
     * Pipeline is created lazily so RECORD_AUDIO permission is guaranteed granted
     * before we ever construct [SpeechRecognitionManager] inside it.
     */
    private var pipeline: AudioPipeline? = null

    // ── UI State ─────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(TranslatorUiState())
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Monotonically increasing ID generator for [TranslationEntry]. */
    private val entryIdCounter = AtomicLong(0L)

    /** Guard so concurrent [loadModel] calls are no-ops. */
    private var modelLoadJob: Job? = null

    // ── Model loading ─────────────────────────────────────────────────────────

    /**
     * Kicks off [TranslationEngine] loading. Safe to call multiple times — subsequent
     * calls while a load is in progress or after success are silently ignored.
     *
     * Bridges [TranslationEngine.loadingState] into [TranslatorUiState] so the Compose
     * screen never needs to know about [TranslationEngine.LoadingState] directly.
     */
    fun loadModel() {
        if (modelLoadJob?.isActive == true) return
        if (_uiState.value.isModelReady) return

        modelLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(modelLoadingProgress = 0f, errorMessage = null) }

            // Observe engine's own loading state flow and mirror it into uiState.
            // The flow completes (stays at the terminal state) so this is a one-shot
            // subscription that ends naturally.
            engine.loadingState
                .onEach { state ->
                    when (state) {
                        is TranslationEngine.LoadingState.Loading -> {
                            _uiState.update { it.copy(modelLoadingProgress = state.progress) }
                        }
                        is TranslationEngine.LoadingState.Ready -> {
                            _uiState.update {
                                it.copy(modelLoadingProgress = null, isModelReady = true)
                            }
                        }
                        is TranslationEngine.LoadingState.Error -> {
                            _uiState.update {
                                it.copy(
                                    modelLoadingProgress = null,
                                    isModelReady = false,
                                    errorMessage = "Failed to load model: ${state.cause.localizedMessage}",
                                )
                            }
                        }
                        else -> { /* Idle — no-op */ }
                    }
                }
                .launchIn(this)  // child of modelLoadJob, cancelled when this scope dies

            // Trigger the actual load after subscribing to avoid a race
            engine.load { progress ->
                _uiState.update { it.copy(modelLoadingProgress = progress) }
            }
        }
    }

    // ── Mode & language actions ───────────────────────────────────────────────

    /**
     * Toggles between [TranslationMode.EARBUD] and [TranslationMode.SPEAKER].
     * Propagates the change to the pipeline if it is running.
     */
    fun toggleMode() {
        val newMode = when (_uiState.value.mode) {
            TranslationMode.EARBUD  -> TranslationMode.SPEAKER
            TranslationMode.SPEAKER -> TranslationMode.EARBUD
        }
        _uiState.update { it.copy(mode = newMode) }
        pipeline?.setMode(newMode)
    }

    /**
     * Sets the language for the left channel / Person A.
     * Propagates to the pipeline immediately; does not stop listening.
     */
    fun setLeftLanguage(language: Language) {
        _uiState.update { it.copy(leftLanguage = language) }
        syncPipelineLanguages()
    }

    /**
     * Sets the language for the right channel / Person B.
     * Propagates to the pipeline immediately; does not stop listening.
     */
    fun setRightLanguage(language: Language) {
        _uiState.update { it.copy(rightLanguage = language) }
        syncPipelineLanguages()
    }

    // ── Pipeline control ──────────────────────────────────────────────────────

    /**
     * Starts or stops the [AudioPipeline] based on [TranslatorUiState.isListening].
     *
     * Starting requires [TranslatorUiState.isReady] (model loaded, no error).
     * The pipeline is created lazily on first call.
     */
    fun toggleListening() {
        val state = _uiState.value
        if (state.isListening) {
            stopListening()
        } else {
            if (!state.isReady) return
            startListening(state)
        }
    }

    private fun startListening(state: TranslatorUiState) {
        val activePipeline = pipeline ?: AudioPipeline(
            context       = getApplication(),
            translationFn = ::translationCallback,
            onPartialText = { partial ->
                _uiState.update { it.copy(pendingRecognizedText = partial) }
            },
        ).also { pipeline = it }

        activePipeline.setMode(state.mode)
        activePipeline.setLanguages(state.leftLanguage, state.rightLanguage)

        // AudioPipeline.start() is suspend (initialises TTS async) — run in viewModelScope.
        viewModelScope.launch {
            try {
                activePipeline.start()
                _uiState.update { it.copy(isListening = true, errorMessage = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to start audio pipeline: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun stopListening() {
        pipeline?.stop()
        _uiState.update { it.copy(isListening = false, pendingRecognizedText = null) }
    }

    // ── Translation callback (invoked by AudioPipeline on Dispatchers.Default) ─

    /**
     * Receives recognised [text] from the pipeline, detects its language, selects the
     * correct target language based on which channel the speaker is on, translates,
     * commits the history entry, and returns a [TranslationResult] for TTS routing.
     *
     * Routing rules
     * -------------
     * detected == leftLanguage  → translate to rightLanguage → RIGHT channel
     * detected == rightLanguage → translate to leftLanguage  → LEFT channel
     * no match                  → discard (return empty result, no TTS)
     *
     * [MutableStateFlow.update] is thread-safe so state mutations here are fine even
     * though this runs on [Dispatchers.Default].
     */
    private suspend fun translationCallback(text: String): TranslationResult {
        val state = _uiState.value

        _uiState.update { it.copy(pendingRecognizedText = text) }

        // Fast language detection: check if text matches right language using
        // character/word heuristics. Falls back to assuming left language.
        val isRightLang = detectLanguageFast(text, state.rightLanguage)
        val sourceLang = if (isRightLang) state.rightLanguage else state.leftLanguage
        val targetLang = if (isRightLang) state.leftLanguage else state.rightLanguage
        val outputChannel = if (isRightLang) AudioChannel.LEFT else AudioChannel.RIGHT

        // ── Translation ─────────────────────────────────────────────────────
        val translated: String = try {
            engine.translate(text, sourceLang.bcp47, targetLang.bcp47)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    pendingRecognizedText = null,
                    errorMessage          = "Translation error: ${e.localizedMessage}",
                )
            }
            return TranslationResult(
                translatedText           = "",
                detectedSourceLanguage   = sourceLang.bcp47,
                targetChannel            = outputChannel,
            )
        }

        // ── Commit to history ───────────────────────────────────────────────
        val entry = TranslationEntry(
            id             = entryIdCounter.incrementAndGet(),
            originalText   = text,
            translatedText = translated,
            sourceLang     = sourceLang,
            targetLang     = targetLang,
        )

        _uiState.update { current ->
            val updatedHistory = buildList<TranslationEntry> {
                add(entry)
                addAll(current.translationHistory)
                if (size > TranslatorUiState.MAX_HISTORY_SIZE) {
                    subList(TranslatorUiState.MAX_HISTORY_SIZE, size).clear()
                }
            }
            current.copy(
                pendingRecognizedText = null,
                latestTranslation     = entry,
                translationHistory    = updatedHistory,
            )
        }

        return TranslationResult(
            translatedText           = translated,
            detectedSourceLanguage   = sourceLang.bcp47,
            targetChannel            = outputChannel,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun detectLanguageFast(text: String, candidateLang: Language): Boolean {
        val lower = text.lowercase()
        val words = lower.split("\\s+".toRegex())
        return when (candidateLang.bcp47.substringBefore('-')) {
            "es" -> {
                val markers = setOf("el", "la", "los", "las", "de", "en", "que", "es", "un", "una",
                    "por", "con", "para", "como", "pero", "más", "muy", "está", "son", "tiene",
                    "hola", "sí", "no", "yo", "tú", "esto", "eso", "bien", "bueno", "también",
                    "cómo", "dónde", "qué", "cuándo", "hacer", "puede", "todo", "nada")
                val hasAccents = lower.any { it in "áéíóúñ¿¡ü" }
                val matchCount = words.count { it in markers }
                hasAccents || matchCount >= 2 || (matchCount >= 1 && words.size <= 4)
            }
            "fr" -> {
                val markers = setOf("le", "la", "les", "de", "des", "du", "un", "une", "est",
                    "et", "en", "que", "qui", "dans", "pour", "avec", "sur", "pas", "nous",
                    "vous", "je", "il", "elle", "ce", "cette", "sont", "ont", "mais", "ou",
                    "très", "bien", "oui", "non", "merci", "bonjour")
                val hasAccents = lower.any { it in "àâçéèêëïîôùûüÿœæ" }
                val matchCount = words.count { it in markers }
                hasAccents || matchCount >= 2 || (matchCount >= 1 && words.size <= 4)
            }
            "de" -> {
                val markers = setOf("der", "die", "das", "ein", "eine", "ist", "und", "ich",
                    "du", "er", "sie", "wir", "ihr", "nicht", "mit", "auf", "für", "von",
                    "aber", "oder", "wenn", "auch", "nur", "noch", "schon", "sehr", "gut",
                    "ja", "nein", "danke", "bitte", "haben", "sein", "werden")
                val hasChars = lower.any { it in "äöüß" }
                val matchCount = words.count { it in markers }
                hasChars || matchCount >= 2 || (matchCount >= 1 && words.size <= 4)
            }
            "pt" -> {
                val markers = setOf("o", "a", "os", "as", "de", "do", "da", "em", "no", "na",
                    "um", "uma", "que", "não", "com", "para", "por", "mais", "mas", "muito",
                    "bem", "sim", "está", "são", "tem", "você", "eu", "ele", "ela", "isso",
                    "obrigado", "olá", "como", "onde", "quando")
                val hasAccents = lower.any { it in "àáâãçéêíóôõú" }
                val matchCount = words.count { it in markers }
                hasAccents || matchCount >= 2 || (matchCount >= 1 && words.size <= 4)
            }
            "ja" -> lower.any { it.code in 0x3041..0x309F || it.code in 0x30A0..0x30FF || it.code in 0x4E00..0x9FFF }
            "ko" -> lower.any { it.code in 0xAC00..0xD7AF }
            "zh" -> lower.any { it.code in 0x4E00..0x9FFF }
            "ar" -> lower.any { it.code in 0x0600..0x06FF }
            "hi" -> lower.any { it.code in 0x0900..0x097F }
            "ru" -> lower.any { it.code in 0x0400..0x04FF }
            "it" -> {
                val markers = setOf("il", "lo", "la", "le", "gli", "un", "una", "di", "del",
                    "che", "è", "non", "per", "con", "sono", "come", "anche", "più", "ma",
                    "io", "tu", "lui", "lei", "noi", "questo", "quello", "bene", "sì", "grazie")
                val hasAccents = lower.any { it in "àèéìòù" }
                val matchCount = words.count { it in markers }
                hasAccents || matchCount >= 2 || (matchCount >= 1 && words.size <= 4)
            }
            "tr" -> {
                val markers = setOf("bir", "ve", "bu", "için", "ile", "de", "da", "ben",
                    "sen", "ne", "var", "yok", "çok", "iyi", "evet", "hayır", "nasıl")
                val hasChars = lower.any { it in "çğıöşü" }
                val matchCount = words.count { it in markers }
                hasChars || matchCount >= 2
            }
            "vi" -> lower.any { it in "ăâđêôơư" || (it.code in 0x0300..0x036F) }
            "th" -> lower.any { it.code in 0x0E00..0x0E7F }
            else -> false
        }
    }

    private fun emptyResult() = TranslationResult(
        translatedText           = "",
        detectedSourceLanguage   = "",
        targetChannel            = AudioChannel.BOTH,
    )

    /** Pushes the current language pair from [uiState] to the pipeline (no-op if null). */
    private fun syncPipelineLanguages() {
        val state = _uiState.value
        pipeline?.setLanguages(state.leftLanguage, state.rightLanguage)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is cancelled automatically before onCleared, so all coroutines
        // are already dead at this point. Only native resource release needed.
        pipeline?.release()
        pipeline = null
        engine.close()
    }
}
