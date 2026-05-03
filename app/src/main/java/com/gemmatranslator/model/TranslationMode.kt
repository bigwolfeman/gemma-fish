package com.gemmatranslator.model

/**
 * Defines the two operational modes of the translator.
 *
 * EARBUD: Each participant wears one earbud. Left earbud carries [leftLanguage],
 *         right earbud carries [rightLanguage]. Both channels play simultaneously
 *         after every translation — each person hears only their language.
 *
 * SPEAKER: Sequential mode for a shared speaker. After speech is detected and
 *          translated, the translation is played aloud for the room to hear.
 *          target1 is the translation of rightLanguage → leftLanguage,
 *          target2 is the translation of leftLanguage → rightLanguage.
 */
enum class TranslationMode(val displayName: String) {
    EARBUD("Earbud Mode"),
    SPEAKER("Speaker Mode");

    override fun toString(): String = displayName
}
