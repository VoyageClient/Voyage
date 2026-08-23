/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import java.util.Locale

data class TranslationLanguage(val id: String, val name: String)

/** Google's language set is the superset every engine is mapped from. */
object TranslationLanguages {

    const val AUTO = "auto"

    /** Sentinel for "the app's interface language", resolved at call time. */
    const val APP = "app"

    val all: List<TranslationLanguage> = listOf(
            "af" to "Afrikaans", "am" to "Amharic", "ar" to "Arabic", "az" to "Azerbaijani", "be" to "Belarusian",
            "bg" to "Bulgarian", "bn" to "Bengali", "bs" to "Bosnian", "ca" to "Catalan", "ceb" to "Cebuano",
            "co" to "Corsican", "cs" to "Czech", "cy" to "Welsh", "da" to "Danish", "de" to "German", "el" to "Greek",
            "en" to "English", "eo" to "Esperanto", "es" to "Spanish", "et" to "Estonian", "eu" to "Basque",
            "fa" to "Persian", "fi" to "Finnish", "fr" to "French", "fy" to "Frisian", "ga" to "Irish",
            "gd" to "Scots Gaelic", "gl" to "Galician", "gu" to "Gujarati", "ha" to "Hausa", "haw" to "Hawaiian",
            "hi" to "Hindi", "hmn" to "Hmong", "hr" to "Croatian", "ht" to "Haitian Creole", "hu" to "Hungarian",
            "hy" to "Armenian", "id" to "Indonesian", "ig" to "Igbo", "is" to "Icelandic", "it" to "Italian",
            "iw" to "Hebrew", "ja" to "Japanese", "jw" to "Javanese", "ka" to "Georgian", "kk" to "Kazakh",
            "km" to "Khmer", "kn" to "Kannada", "ko" to "Korean", "ku" to "Kurdish", "ky" to "Kyrgyz", "la" to "Latin",
            "lb" to "Luxembourgish", "lo" to "Lao", "lt" to "Lithuanian", "lv" to "Latvian", "mg" to "Malagasy",
            "mi" to "Maori", "mk" to "Macedonian", "ml" to "Malayalam", "mn" to "Mongolian", "mr" to "Marathi",
            "ms" to "Malay", "mt" to "Maltese", "my" to "Burmese", "ne" to "Nepali", "nl" to "Dutch",
            "no" to "Norwegian", "ny" to "Chichewa", "or" to "Odia", "pa" to "Punjabi", "pl" to "Polish",
            "ps" to "Pashto", "pt" to "Portuguese", "ro" to "Romanian", "ru" to "Russian", "rw" to "Kinyarwanda",
            "sd" to "Sindhi", "si" to "Sinhala", "sk" to "Slovak", "sl" to "Slovenian", "sm" to "Samoan",
            "sn" to "Shona", "so" to "Somali", "sq" to "Albanian", "sr" to "Serbian", "st" to "Sesotho",
            "su" to "Sundanese", "sv" to "Swedish", "sw" to "Swahili", "ta" to "Tamil", "te" to "Telugu",
            "tg" to "Tajik", "th" to "Thai", "tk" to "Turkmen", "tl" to "Filipino", "tr" to "Turkish", "tt" to "Tatar",
            "ug" to "Uyghur", "uk" to "Ukrainian", "ur" to "Urdu", "uz" to "Uzbek", "vi" to "Vietnamese",
            "xh" to "Xhosa", "yi" to "Yiddish", "yo" to "Yoruba", "zh-CN" to "Chinese (Simplified)",
            "zh-TW" to "Chinese (Traditional)", "zu" to "Zulu",
    ).map { TranslationLanguage(it.first, it.second) }

    private val byId = all.associateBy { it.id }

    // Parenthetical qualifiers ("Chinese (Simplified)") are dropped for display.
    fun nameOf(id: String?): String? = id?.let { byId[normalize(it)]?.name?.substringBefore(" (") }

    fun isKnown(id: String): Boolean = normalize(id) in byId

    /** Maps engine-reported / user-typed codes (he, zh-Hans, PT-BR, …) onto the Google ids above. */
    fun normalize(code: String): String {
        val lower = code.trim().lowercase(Locale.ROOT)
        return when (lower) {
            "he" -> "iw"
            "jp" -> "ja"
            "jv" -> "jw"
            "fil" -> "tl"
            "zh", "zh-hans", "zh-cn" -> "zh-CN"
            "zh-hant", "zh-tw", "zh-hk" -> "zh-TW"
            "nb", "nn" -> "no"
            else -> {
                if (lower in byId) return lower
                val base = lower.substringBefore('-')
                if (base in byId) base else lower
            }
        }
    }

    /** The app interface language as a translation target, e.g. "en" or "zh-CN". */
    fun appLanguage(): String {
        // VectorConfiguration keeps Locale.getDefault() in sync with the chosen app language.
        val locale = Locale.getDefault()
        val tag = if (locale.country.isNullOrEmpty()) locale.language else "${locale.language}-${locale.country}"
        val normalized = normalize(tag)
        return if (normalized in byId) normalized else "en"
    }

    fun resolve(id: String): String = if (id == APP) appLanguage() else normalize(id)

    private val SEND_PREFIX = Regex("""^\$([A-Za-z]{2,3}(?:-[A-Za-z]{2,4})?)\s+\S""")

    /** "$fr hello" typed straight into the composer -> target language + remaining text, else null. */
    fun sendPrefix(text: CharSequence): Pair<String, CharSequence>? {
        val match = SEND_PREFIX.find(text) ?: return null
        val language = normalize(match.groupValues[1])
        if (!isKnown(language)) return null
        return language to text.subSequence(match.groupValues[1].length + 1, text.length).trimStart()
    }
}
