package com.gemmatranslator.model

enum class Language(val displayName: String, val bcp47: String) {
    // ── Strong tier (benchmarked) ─────────────────────────────────────────
    ARABIC("Arabic", "ar"),
    BENGALI("Bengali", "bn"),
    CHINESE_SIMPLIFIED("Chinese (Simplified)", "zh-CN"),
    CHINESE_TRADITIONAL("Chinese (Traditional)", "zh-TW"),
    CZECH("Czech", "cs"),
    DANISH("Danish", "da"),
    DUTCH("Dutch", "nl"),
    ENGLISH("English", "en"),
    FINNISH("Finnish", "fi"),
    FRENCH("French", "fr"),
    GERMAN("German", "de"),
    GREEK("Greek", "el"),
    HEBREW("Hebrew", "he"),
    HINDI("Hindi", "hi"),
    HUNGARIAN("Hungarian", "hu"),
    INDONESIAN("Indonesian", "id"),
    ITALIAN("Italian", "it"),
    JAPANESE("Japanese", "ja"),
    KOREAN("Korean", "ko"),
    MALAY("Malay", "ms"),
    NORWEGIAN("Norwegian", "no"),
    PERSIAN("Persian", "fa"),
    POLISH("Polish", "pl"),
    PORTUGUESE("Portuguese", "pt"),
    ROMANIAN("Romanian", "ro"),
    RUSSIAN("Russian", "ru"),
    SPANISH("Spanish", "es"),
    SWAHILI("Swahili", "sw"),
    SWEDISH("Swedish", "sv"),
    THAI("Thai", "th"),
    TURKISH("Turkish", "tr"),
    UKRAINIAN("Ukrainian", "uk"),
    VIETNAMESE("Vietnamese", "vi"),

    // ── Functional tier (pre-trained, less benchmarked) ────────────────────
    AMHARIC("Amharic", "am"),
    ARMENIAN("Armenian", "hy"),
    AZERBAIJANI("Azerbaijani", "az"),
    BULGARIAN("Bulgarian", "bg"),
    BURMESE("Burmese", "my"),
    CATALAN("Catalan", "ca"),
    CROATIAN("Croatian", "hr"),
    ESTONIAN("Estonian", "et"),
    FILIPINO("Filipino", "fil"),
    GEORGIAN("Georgian", "ka"),
    GUJARATI("Gujarati", "gu"),
    HAUSA("Hausa", "ha"),
    ICELANDIC("Icelandic", "is"),
    IGBO("Igbo", "ig"),
    KANNADA("Kannada", "kn"),
    KAZAKH("Kazakh", "kk"),
    KHMER("Khmer", "km"),
    LAO("Lao", "lo"),
    LATVIAN("Latvian", "lv"),
    LITHUANIAN("Lithuanian", "lt"),
    MACEDONIAN("Macedonian", "mk"),
    MALAYALAM("Malayalam", "ml"),
    MARATHI("Marathi", "mr"),
    MONGOLIAN("Mongolian", "mn"),
    NEPALI("Nepali", "ne"),
    PASHTO("Pashto", "ps"),
    PUNJABI("Punjabi", "pa"),
    SERBIAN("Serbian", "sr"),
    SINHALA("Sinhala", "si"),
    SLOVAK("Slovak", "sk"),
    SLOVENIAN("Slovenian", "sl"),
    SOMALI("Somali", "so"),
    TAMIL("Tamil", "ta"),
    TELUGU("Telugu", "te"),
    URDU("Urdu", "ur"),
    UZBEK("Uzbek", "uz"),
    WELSH("Welsh", "cy"),
    XHOSA("Xhosa", "xh"),
    YORUBA("Yoruba", "yo"),
    ZULU("Zulu", "zu");

    override fun toString(): String = displayName

    companion object {
        fun fromBcp47(tag: String): Language? {
            val prefix = tag.substringBefore('-').lowercase()
            return entries.firstOrNull { it.bcp47.substringBefore('-').lowercase() == prefix }
        }

        val default: Language get() = ENGLISH
    }
}
