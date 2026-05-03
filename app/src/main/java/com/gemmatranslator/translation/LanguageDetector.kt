package com.gemmatranslator.translation

/**
 * Two-tier language detector.
 *
 * Tier 1 — heuristic script detection (μs, no model call):
 *   Identifies languages whose writing system is unique enough that a
 *   Unicode script scan is conclusive: CJK, Japanese kana, Korean Hangul,
 *   Arabic, Hebrew, Devanagari, Thai, Greek, Cyrillic.
 *   If at least [SCRIPT_CONFIDENCE_THRESHOLD] of sampled codepoints belong
 *   to a single script family the result is returned immediately.
 *
 * Tier 2 — Gemma model call (ms, via [TranslationEngine]):
 *   For ambiguous Latin-script languages (e.g. en vs fr vs es vs de) or
 *   mixed-script text the model is consulted. The engine invokes
 *   [PromptTemplates.languageDetection] with a tight token budget.
 *
 * Threading: all public functions are pure / stateless. The [ModelDelegate]
 * lambda provided to [LanguageDetector] is the only mutable dependency and
 * is expected to be thread-safe on the caller side.
 */
class LanguageDetector(
    /** Invoked only when heuristics are insufficient. Receives the raw text,
     *  returns an ISO-639-1 code string (possibly with surrounding whitespace). */
    private val modelDelegate: suspend (text: String) -> String
) {

    companion object {
        /** Fraction of sampled codepoints that must match a script to be conclusive. */
        private const val SCRIPT_CONFIDENCE_THRESHOLD = 0.60f

        /** How many codepoints to sample for script analysis (cap for long strings). */
        private const val SAMPLE_CODEPOINTS = 300

        /** Fallback when detection is inconclusive. */
        const val UNKNOWN_LANG = "unknown"

        // Unicode block ranges for fast script classification
        private val CJK_RANGES = listOf(
            0x4E00..0x9FFF,   // CJK Unified Ideographs
            0x3400..0x4DBF,   // CJK Extension A
            0x20000..0x2A6DF, // CJK Extension B
            0xF900..0xFAFF,   // CJK Compatibility Ideographs
        )
        private val HIRAGANA_RANGE    = 0x3041..0x309F
        private val KATAKANA_RANGE    = 0x30A0..0x30FF
        private val HANGUL_RANGE      = 0xAC00..0xD7AF
        private val ARABIC_RANGE      = 0x0600..0x06FF
        private val HEBREW_RANGE      = 0x0590..0x05FF
        private val DEVANAGARI_RANGE  = 0x0900..0x097F
        private val THAI_RANGE        = 0x0E00..0x0E7F
        private val GREEK_RANGE       = 0x0370..0x03FF
        private val CYRILLIC_RANGE    = 0x0400..0x04FF
        private val GEORGIAN_RANGE    = 0x10A0..0x10FF
        private val ARMENIAN_RANGE    = 0x0530..0x058F
        private val ETHIOPIC_RANGE    = 0x1200..0x137F
        private val MYANMAR_RANGE     = 0x1000..0x109F
        private val KHMER_RANGE       = 0x1780..0x17FF
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Detect language synchronously using heuristics only.
     * Returns null when the script is ambiguous (Latin-family) or the sample
     * is too short — in that case callers should use [detectLanguage].
     */
    fun detectLanguageHeuristic(text: String): String? {
        if (text.isBlank()) return null
        val sample = text.codePoints()
            .limit(SAMPLE_CODEPOINTS.toLong())
            .toArray()
            .filter { it > 0x007F } // skip ASCII — not conclusive on its own
        if (sample.size < 4) return null  // not enough non-ASCII data

        val counts = mutableMapOf<String, Int>()
        for (cp in sample) {
            val script = classifyCodepoint(cp) ?: continue
            counts[script] = (counts[script] ?: 0) + 1
        }
        if (counts.isEmpty()) return null

        val topEntry = counts.maxByOrNull { it.value } ?: return null
        val confidence = topEntry.value.toFloat() / sample.size
        if (confidence < SCRIPT_CONFIDENCE_THRESHOLD) return null

        return scriptToLanguageCode(topEntry.key)
    }

    /**
     * Full detection pipeline. Returns an ISO-639-1 language code.
     * Falls through to [modelDelegate] when heuristics are inconclusive.
     */
    suspend fun detectLanguage(text: String): String {
        if (text.isBlank()) return UNKNOWN_LANG

        // Fast path
        detectLanguageHeuristic(text)?.let { return it }

        // Slow path — model call
        return try {
            val raw = modelDelegate(text)
            parseModelOutput(raw)
        } catch (e: Exception) {
            UNKNOWN_LANG
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Maps a Unicode codepoint to a script identifier string, or null for
     *  Latin/Common/punctuation which we cannot resolve without a model. */
    private fun classifyCodepoint(cp: Int): String? = when {
        cp in HIRAGANA_RANGE || cp in KATAKANA_RANGE -> "hiragana_katakana"
        CJK_RANGES.any { cp in it }                  -> "cjk"
        cp in HANGUL_RANGE                            -> "hangul"
        cp in ARABIC_RANGE                            -> "arabic"
        cp in HEBREW_RANGE                            -> "hebrew"
        cp in DEVANAGARI_RANGE                        -> "devanagari"
        cp in THAI_RANGE                              -> "thai"
        cp in GREEK_RANGE                             -> "greek"
        cp in CYRILLIC_RANGE                          -> "cyrillic"
        cp in GEORGIAN_RANGE                          -> "georgian"
        cp in ARMENIAN_RANGE                          -> "armenian"
        cp in ETHIOPIC_RANGE                          -> "ethiopic"
        cp in MYANMAR_RANGE                           -> "myanmar"
        cp in KHMER_RANGE                             -> "khmer"
        else                                          -> null
    }

    /**
     * Maps script identifier to ISO-639-1 language code.
     * Japanese uses both Hiragana/Katakana *and* CJK, so Hiragana/Katakana
     * presence takes priority (most Japanese text mixes them with kanji).
     * Pure CJK without kana is treated as Chinese (zh).
     */
    private fun scriptToLanguageCode(script: String): String = when (script) {
        "hiragana_katakana" -> "ja"
        "cjk"               -> "zh"  // could be zh/ja/ko — ja caught above
        "hangul"            -> "ko"
        "arabic"            -> "ar"
        "hebrew"            -> "he"
        "devanagari"        -> "hi"
        "thai"              -> "th"
        "greek"             -> "el"
        "cyrillic"          -> "ru"  // most common; uk/bg etc need model
        "georgian"          -> "ka"
        "armenian"          -> "hy"
        "ethiopic"          -> "am"
        "myanmar"           -> "my"
        "khmer"             -> "km"
        else                -> UNKNOWN_LANG
    }

    /**
     * Strips noise from the model's language-code response.
     * The model is prompted to return just the code but may include
     * surrounding whitespace, punctuation, or a brief explanation.
     */
    private fun parseModelOutput(raw: String): String {
        // Take the first token that looks like a 2-3 letter language code
        val cleaned = raw.trim().lowercase()
        val tokenRegex = Regex("[a-z]{2,3}")
        return tokenRegex.find(cleaned)?.value ?: UNKNOWN_LANG
    }
}
