package com.gemmatranslator.translation

/**
 * Prompt templates optimized for Gemma 4 E2B translation tasks.
 *
 * Design goals:
 *  - Minimal token count → lower latency on-device
 *  - Unambiguous instruction phrasing to avoid garbled output
 *  - No chain-of-thought scaffolding (wastes tokens for translation)
 *  - Gemma chat template (<start_of_turn> / <end_of_turn>) wrapping
 *    keeps the model in "assistant" mode and suppresses preamble text.
 */
object PromptTemplates {

    // Gemma 4 special tokens
    private const val START_TURN = "<start_of_turn>"
    private const val END_TURN   = "<end_of_turn>"
    private const val USER_TAG   = "user"
    private const val MODEL_TAG  = "model"

    /**
     * System-level context injected once at the start of the model turn.
     * Kept brief — one sentence is enough to bias the model toward translator
     * behaviour without burning tokens on every call.
     */
    const val SYSTEM_PROMPT = "You are a precise translator. Output only the translation, nothing else."

    // -------------------------------------------------------------------------
    // Translation
    // -------------------------------------------------------------------------

    /**
     * Primary translation prompt.
     *
     * The model receives a user turn that names source + target language and
     * the text, then an open model turn so the first generated token is the
     * translation itself (no "Here is the translation:" preamble).
     *
     * [sourceLang] / [targetLang] should be ISO-639-1 codes ("en", "ja", …)
     * or human-readable names for robustness.
     */
    fun translation(text: String, sourceLang: String, targetLang: String): String =
        buildString {
            append(START_TURN).append(USER_TAG).append('\n')
            append(SYSTEM_PROMPT).append('\n')
            // Concise directive — avoids over-specifying which also wastes tokens
            append("Translate from ").append(sourceLang)
            append(" to ").append(targetLang).append(":\n")
            append(text)
            append(END_TURN).append('\n')
            // Open model turn — model will start generating the translation directly
            append(START_TURN).append(MODEL_TAG).append('\n')
        }

    /**
     * Variant used when source language is unknown / already detected externally.
     * Saves a few tokens vs. supplying source lang.
     */
    fun translationToTarget(text: String, targetLang: String): String =
        buildString {
            append(START_TURN).append(USER_TAG).append('\n')
            append(SYSTEM_PROMPT).append('\n')
            append("Translate to ").append(targetLang).append(":\n")
            append(text)
            append(END_TURN).append('\n')
            append(START_TURN).append(MODEL_TAG).append('\n')
        }

    // -------------------------------------------------------------------------
    // Language detection
    // -------------------------------------------------------------------------

    /**
     * Language detection prompt.
     *
     * Instructs Gemma to reply with a single ISO-639-1 code only.
     * Sampling temperature=0 + topK=1 makes the output deterministic.
     * We only need ~4 tokens of output so maxTokens can be set very low for
     * this call (see [TranslationEngine.DETECT_MAX_TOKENS]).
     */
    fun languageDetection(text: String): String =
        buildString {
            append(START_TURN).append(USER_TAG).append('\n')
            // Take at most the first 200 chars — more than enough for detection
            val sample = if (text.length > 200) text.take(200) else text
            append("Identify the language of the following text. ")
            append("Reply with only the ISO 639-1 two-letter language code (e.g. en, fr, ja).\n")
            append(sample)
            append(END_TURN).append('\n')
            append(START_TURN).append(MODEL_TAG).append('\n')
        }

    // -------------------------------------------------------------------------
    // Token budget helpers
    // -------------------------------------------------------------------------

    /**
     * Rough upper-bound on how many output tokens a translation might need.
     * Japanese / Chinese can expand, Latin scripts are roughly 1:1.
     * We cap at [maxTokens] to avoid runaway generation.
     */
    fun estimateTranslationTokens(inputText: String, maxTokens: Int = 512): Int {
        // Heuristic: ~1.5 words per token, translations ~20% longer than source
        val wordCount = inputText.trim().split(Regex("\\s+")).size
        val estimate  = (wordCount * 1.5f * 1.2f * 1.5f).toInt() // tokens × expansion
        return estimate.coerceIn(64, maxTokens)
    }
}
