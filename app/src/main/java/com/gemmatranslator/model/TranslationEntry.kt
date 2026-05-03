package com.gemmatranslator.model

import java.time.Instant

/**
 * An immutable record of a single completed translation, used to build the session history.
 *
 * @param id             Stable unique identifier for Compose [key()] calls.
 * @param originalText   The verbatim text that was recognized by speech-to-text.
 * @param translatedText The output text produced by the TranslationEngine.
 * @param sourceLang     The language detected (or inferred) for [originalText].
 * @param targetLang     The language into which [originalText] was translated.
 * @param timestamp      UTC instant at which the translation was completed.
 */
data class TranslationEntry(
    val id: Long,
    val originalText: String,
    val translatedText: String,
    val sourceLang: Language,
    val targetLang: Language,
    val timestamp: Instant = Instant.now(),
) {
    /** One-liner suitable for accessibility content descriptions and logging. */
    val summary: String
        get() = "[${sourceLang.displayName} → ${targetLang.displayName}] $originalText → $translatedText"
}
