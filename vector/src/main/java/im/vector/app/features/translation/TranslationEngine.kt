/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

enum class TranslationEngine(
        val id: String,
        val displayName: String,
        val needsKey: Boolean,
        /** Supported language ids (Google ids); null = everything in [TranslationLanguages.all]. */
        val languages: Set<String>?,
) {
    LOCAL("local", "Local", needsKey = false, languages = im.vector.app.features.translation.ondevice.NllbLanguages.supportedGoogleIds),
    GOOGLE("google", "Google", needsKey = false, languages = null),
    MICROSOFT(
            "microsoft", "Microsoft", needsKey = true,
            languages = setOf(
                    "af", "am", "ar", "az", "bg", "bn", "bs", "ca", "cs", "cy", "da", "de", "el", "en", "es", "et", "eu", "fa",
                    "fi", "tl", "fr", "ga", "gl", "gu", "ha", "iw", "hi", "hr", "ht", "hu", "hy", "id", "ig", "is", "it", "ja",
                    "ka", "kk", "km", "kn", "ko", "ku", "ky", "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mr", "ms", "mt", "my",
                    "ne", "nl", "or", "pa", "pl", "ps", "pt", "ro", "ru", "rw", "sd", "si", "sk", "sl", "sm", "sn", "so", "sq",
                    "st", "sv", "sw", "ta", "te", "th", "tk", "tr", "tt", "ug", "uk", "ur", "uz", "vi", "xh", "yo", "zh-CN",
                    "zh-TW", "zu",
            )
    ),
    DEEPL(
            "deepl", "DeepL", needsKey = true,
            languages = setOf(
                    "ar", "bg", "cs", "da", "de", "en", "el", "es", "et", "fi", "fr", "hu", "id", "it", "ja", "ko", "lt", "lv",
                    "nl", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "zh-CN", "zh-TW",
            )
    ),
    DEEPSEEK("deepseek", "DeepSeek", needsKey = true, languages = null),
    OPENAI_COMPATIBLE("oaicompat", "OpenAI-compatible", needsKey = true, languages = null);

    fun supports(languageId: String): Boolean = languages == null || languageId in languages

    /** Translates a Google id into this engine's wire code. */
    fun wireCode(languageId: String): String = when (this) {
        LOCAL, GOOGLE, DEEPSEEK, OPENAI_COMPATIBLE -> languageId
        MICROSOFT -> when (languageId) {
            "zh-CN" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            "iw" -> "he"
            "tl" -> "fil"
            else -> languageId
        }
        DEEPL -> when (languageId) {
            "zh-CN" -> "ZH-HANS"
            "zh-TW" -> "ZH-HANT"
            "no" -> "NB"
            else -> languageId.uppercase()
        }
    }

    companion object {
        const val DEEPSEEK_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
        const val DEEPSEEK_MODEL = "deepseek-chat"
        const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val OPENAI_MODEL = "gpt-4o-mini"

        fun fromId(id: String?): TranslationEngine? = entries.firstOrNull { it.id == id }
    }
}
