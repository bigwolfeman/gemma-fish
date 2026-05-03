package com.gemmatranslator.model

/**
 * Complete snapshot of the UI state consumed by Compose screens.
 * All fields are immutable; the ViewModel emits a new instance on every change.
 *
 * Compose diffing is O(changed fields) because [copy] only allocates what changed.
 */
data class TranslatorUiState(

    // ── Mode & language configuration ──────────────────────────────────────

    val mode: TranslationMode = TranslationMode.EARBUD,

    /** Language assigned to the left channel / Person A. */
    val leftLanguage: Language = Language.ENGLISH,

    /** Language assigned to the right channel / Person B. */
    val rightLanguage: Language = Language.SPANISH,

    // ── Listening / pipeline ───────────────────────────────────────────────

    /** True while the AudioPipeline is actively capturing and processing audio. */
    val isListening: Boolean = false,

    // ── Model loading ──────────────────────────────────────────────────────

    /** Null means loading hasn't started or has completed successfully. */
    val modelLoadingProgress: Float? = null,   // 0f–1f while loading, null otherwise

    /** True once [TranslationEngine.isReady] returns true. */
    val isModelReady: Boolean = false,

    /** Non-null when an unrecoverable error has occurred (model load, pipeline crash, …). */
    val errorMessage: String? = null,

    // ── Live transcription / translation ──────────────────────────────────

    /**
     * The most recently recognized source text, shown in real time before translation
     * completes. Cleared to null once the translation entry is committed.
     */
    val pendingRecognizedText: String? = null,

    /**
     * The most recently produced translation result, displayed as a transient overlay
     * before it scrolls into [translationHistory].
     */
    val latestTranslation: TranslationEntry? = null,

    // ── Translation history ────────────────────────────────────────────────

    /**
     * Rolling log of the last [MAX_HISTORY_SIZE] completed translations,
     * ordered newest-first (index 0 = most recent).
     */
    val translationHistory: List<TranslationEntry> = emptyList(),

) {

    // ── Derived / convenience properties used directly by Compose ──────────

    /** Label for the left panel header, e.g. "Left · English" or "Person A · English". */
    val leftLabel: String
        get() = when (mode) {
            TranslationMode.EARBUD   -> "Left · ${leftLanguage.displayName}"
            TranslationMode.SPEAKER  -> "Person A · ${leftLanguage.displayName}"
        }

    /** Label for the right panel header. */
    val rightLabel: String
        get() = when (mode) {
            TranslationMode.EARBUD   -> "Right · ${rightLanguage.displayName}"
            TranslationMode.SPEAKER  -> "Person B · ${rightLanguage.displayName}"
        }

    /** Human-readable description of the active mode shown in the mode toggle button. */
    val modeLabel: String get() = mode.displayName

    /** True while the model is in the process of loading (progress is non-null). */
    val isModelLoading: Boolean get() = modelLoadingProgress != null

    /** True when the UI should be interactive (model ready and no fatal error). */
    val isReady: Boolean get() = isModelReady && errorMessage == null

    companion object {
        const val MAX_HISTORY_SIZE = 20
    }
}
