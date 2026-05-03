package com.gemmatranslator.model

/**
 * Supported languages with their BCP-47 locale codes and human-readable display names.
 *
 * [bcp47] is passed directly to the TTS engine and used as a hint during language detection.
 * [displayName] is shown in UI dropdowns and labels.
 */
enum class Language(val displayName: String, val bcp47: String) {
    ENGLISH("English", "en-US"),
    SPANISH("Spanish", "es-ES"),
    FRENCH("French", "fr-FR"),
    GERMAN("German", "de-DE"),
    CHINESE("Chinese", "zh-CN"),
    JAPANESE("Japanese", "ja-JP"),
    KOREAN("Korean", "ko-KR"),
    ARABIC("Arabic", "ar-SA"),
    HINDI("Hindi", "hi-IN"),
    PORTUGUESE("Portuguese", "pt-BR"),
    RUSSIAN("Russian", "ru-RU"),
    ITALIAN("Italian", "it-IT"),
    TURKISH("Turkish", "tr-TR"),
    VIETNAMESE("Vietnamese", "vi-VN"),
    THAI("Thai", "th-TH");

    override fun toString(): String = displayName

    companion object {
        /** Returns the Language whose [bcp47] starts with the given language subtag (case-insensitive).
         *  e.g. "en" matches ENGLISH, "zh" matches CHINESE. Returns null if no match. */
        fun fromBcp47(tag: String): Language? {
            val prefix = tag.substringBefore('-').lowercase()
            return entries.firstOrNull { it.bcp47.substringBefore('-').lowercase() == prefix }
        }

        val default: Language get() = ENGLISH
    }
}
