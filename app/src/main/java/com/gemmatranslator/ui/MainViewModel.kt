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
        engine.resetConversation()
    }

    /**
     * Sets the language for the right channel / Person B.
     * Propagates to the pipeline immediately; does not stop listening.
     */
    fun setRightLanguage(language: Language) {
        _uiState.update { it.copy(rightLanguage = language) }
        syncPipelineLanguages()
        engine.resetConversation()
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
            engine.translate(text, sourceLang.displayName, targetLang.displayName)
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
        val code = candidateLang.bcp47.substringBefore('-')

        // Unique script detection — conclusive if any characters match
        scriptRange(code)?.let { range ->
            return lower.any { it.code in range }
        }

        // Latin-script languages — use word markers + diacritics
        val (markers, diacritics) = latinMarkers(code) ?: return false
        val hasSpecialChars = diacritics.isNotEmpty() && lower.any { it in diacritics }
        val matchCount = words.count { it in markers }
        return hasSpecialChars || matchCount >= 2 || (matchCount >= 1 && words.size <= 4)
    }

    private fun scriptRange(code: String): IntRange? = when (code) {
        "ja" -> null // Japanese uses mixed scripts, handled specially
        "zh" -> 0x4E00..0x9FFF
        "ko" -> 0xAC00..0xD7AF
        "ar", "fa", "ur", "ps" -> 0x0600..0x06FF
        "he" -> 0x0590..0x05FF
        "hi", "mr", "ne" -> 0x0900..0x097F
        "bn" -> 0x0980..0x09FF
        "gu" -> 0x0A80..0x0AFF
        "pa" -> 0x0A00..0x0A7F
        "ta" -> 0x0B80..0x0BFF
        "te" -> 0x0C00..0x0C7F
        "kn" -> 0x0C80..0x0CFF
        "ml" -> 0x0D00..0x0D7F
        "si" -> 0x0D80..0x0DFF
        "th" -> 0x0E00..0x0E7F
        "lo" -> 0x0E80..0x0EFF
        "my" -> 0x1000..0x109F
        "km" -> 0x1780..0x17FF
        "ka" -> 0x10A0..0x10FF
        "hy" -> 0x0530..0x058F
        "am" -> 0x1200..0x137F
        "ru", "uk", "bg", "mk", "sr", "mn", "kk", "uz" -> 0x0400..0x04FF
        "el" -> 0x0370..0x03FF
        else -> null
    }

    private fun latinMarkers(code: String): Pair<Set<String>, String>? = when (code) {
        "ja" -> setOf("の", "は", "が", "を", "に", "で", "と", "も", "から", "まで", "です", "ます") to ""
        "es" -> setOf("el", "la", "los", "las", "de", "en", "que", "es", "un", "una",
            "por", "con", "para", "como", "pero", "muy", "está", "son",
            "hola", "sí", "yo", "tú", "bien", "bueno", "también") to "áéíóúñ¿¡"
        "fr" -> setOf("le", "la", "les", "de", "des", "du", "un", "une", "est",
            "et", "en", "que", "qui", "dans", "pour", "avec", "pas",
            "je", "il", "elle", "nous", "vous", "oui", "non", "merci") to "àâçéèêëïîôùûüÿœæ"
        "de" -> setOf("der", "die", "das", "ein", "eine", "ist", "und", "ich",
            "nicht", "mit", "auf", "für", "von", "aber", "oder", "wenn",
            "ja", "nein", "danke", "bitte", "haben", "sein") to "äöüß"
        "pt" -> setOf("o", "os", "as", "de", "do", "da", "em", "no", "na",
            "um", "uma", "que", "não", "com", "para", "por", "mais",
            "sim", "está", "são", "você", "eu", "obrigado") to "àáâãçéêíóôõú"
        "it" -> setOf("il", "lo", "la", "le", "gli", "un", "una", "di", "del",
            "che", "è", "non", "per", "con", "sono", "come", "anche",
            "io", "tu", "questo", "bene", "sì", "grazie") to "àèéìòù"
        "tr" -> setOf("bir", "ve", "bu", "için", "ile", "ben", "sen",
            "var", "yok", "çok", "iyi", "evet", "hayır", "nasıl") to "çğıöşü"
        "vi" -> setOf("là", "của", "và", "có", "được", "không", "này",
            "cho", "với", "từ", "như", "đã", "sẽ", "tôi") to "ăâđêôơư"
        "nl" -> setOf("de", "het", "een", "van", "en", "in", "is", "dat",
            "niet", "op", "zij", "hij", "wij", "maar", "ook", "met") to "ëïé"
        "pl" -> setOf("nie", "jest", "na", "się", "to", "że", "jak",
            "tak", "ale", "już", "tylko", "czy", "bardzo", "dobrze") to "ąćęłńóśźż"
        "cs" -> setOf("je", "na", "se", "že", "to", "jak", "ale",
            "tak", "jsem", "není", "jsou", "také", "velmi", "dobře") to "áčďéěíňóřšťúůýž"
        "ro" -> setOf("este", "sunt", "nu", "și", "dar", "sau", "care",
            "mai", "foarte", "bine", "da", "cum", "unde", "când") to "ăâîșț"
        "hu" -> setOf("egy", "nem", "van", "hogy", "az", "meg", "már",
            "igen", "nagyon", "köszönöm", "jó", "csak", "mint") to "áéíóöőúüű"
        "sv" -> setOf("och", "det", "att", "en", "ett", "är", "inte",
            "som", "för", "med", "har", "den", "jag", "kan", "från") to "åäö"
        "da" -> setOf("og", "det", "er", "en", "at", "ikke", "med",
            "har", "kan", "jeg", "som", "den", "fra", "skal") to "æøå"
        "no" -> setOf("og", "det", "er", "en", "at", "ikke", "med",
            "har", "kan", "jeg", "som", "den", "fra", "skal") to "æøå"
        "fi" -> setOf("on", "ei", "ja", "se", "että", "hän", "oli",
            "mutta", "niin", "kuin", "myös", "vain", "hyvin", "kiitos") to "äö"
        "id", "ms" -> setOf("dan", "yang", "di", "ini", "itu", "dengan", "untuk",
            "dari", "pada", "adalah", "tidak", "ada", "akan", "saya", "kami") to ""
        "sw" -> setOf("na", "ya", "ni", "kwa", "wa", "katika", "hii",
            "sana", "ndiyo", "hapana", "asante", "habari", "jambo", "karibu") to ""
        "ca" -> setOf("el", "la", "els", "les", "de", "amb", "que", "és",
            "no", "sí", "molt", "bé", "gràcies", "hola") to "àèéíïòóúüç"
        "hr" -> setOf("je", "na", "se", "da", "ali", "su", "ako",
            "samo", "vrlo", "dobro", "hvala", "molim") to "čćđšž"
        "sk" -> setOf("je", "na", "sa", "že", "to", "ale", "tak",
            "som", "nie", "áno", "veľmi", "dobre", "ďakujem") to "áäčďéíľĺňóôŕšťúýž"
        "sl" -> setOf("je", "na", "se", "da", "ali", "so", "če",
            "samo", "zelo", "dobro", "hvala", "prosim") to "čšž"
        "et" -> setOf("on", "ei", "ja", "see", "kui", "aga", "ka",
            "väga", "hästi", "jah", "tänan", "palun") to "äöüõšž"
        "lv" -> setOf("ir", "un", "ka", "ar", "no", "bet", "vai",
            "ļoti", "labi", "jā", "nē", "paldies") to "āčēģīķļņšūž"
        "lt" -> setOf("ir", "yra", "ne", "kad", "su", "bet", "ar",
            "labai", "gerai", "taip", "ačiū", "prašau") to "ąčęėįšųūž"
        "is" -> setOf("og", "er", "að", "ekki", "með", "sem", "en",
            "já", "nei", "mjög", "vel", "takk") to "áðéíóúýþæö"
        "az" -> setOf("və", "bir", "bu", "ilə", "üçün", "deyil",
            "bəli", "xeyr", "çox", "yaxşı") to "çəğıöşü"
        "fil" -> setOf("ang", "ng", "sa", "na", "at", "ay", "mga",
            "hindi", "oo", "salamat", "maganda", "kumusta") to ""
        "ha" -> setOf("da", "na", "shi", "ne", "ba", "mai", "amma",
            "sosai", "nagode", "sannu", "ina") to "ɓɗƙ"
        "ig" -> setOf("na", "bụ", "nke", "ya", "ma", "ọ", "dị",
            "ọfụma", "daalu", "ndewo", "ee", "mba") to "ịọụ"
        "yo" -> setOf("ni", "ti", "si", "àti", "kò", "lè", "ṣe",
            "dáadáa", "ẹ", "bẹ́ẹ̀ni", "rárá") to "ẹọṣ"
        "xh" -> setOf("na", "le", "xa", "nge", "ngo", "hayi", "ewe",
            "kakhulu", "enkosi", "molo", "yintoni") to ""
        "zu" -> setOf("na", "le", "uma", "nge", "ngo", "cha", "yebo",
            "kakhulu", "ngiyabonga", "sawubona", "yini") to ""
        "so" -> setOf("waa", "iyo", "ka", "oo", "ah", "maya", "haa",
            "aad", "mahadsanid", "nabad", "maxaa") to ""
        "cy" -> setOf("yn", "mae", "ac", "ar", "am", "nad", "oes",
            "iawn", "da", "diolch", "bore", "shwmae") to "âêîôûŵŷ"
        else -> null
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
