/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.profile

import org.matrix.android.sdk.api.session.profile.GrammaticalGender
import org.matrix.android.sdk.api.session.profile.Pronoun
import java.util.Locale

object PronounHelper {

    /**
     * Build a full MSC4247 pronoun entry from a summary. For the common English sets we fill every
     * grammatical form + grammatical_gender so any reader (MSC-style or gomuks-style) can use them;
     * for anything else we keep just the summary (and infer the determiner/gender from the subject).
     */
    fun build(summary: String, language: String = "en"): Pronoun {
        return when (summary.substringBefore('/').trim().lowercase()) {
            "she" -> Pronoun(summary, language, "she", "her", "her", "hers", "herself", GrammaticalGender.FEMININE)
            "he" -> Pronoun(summary, language, "he", "him", "his", "his", "himself", GrammaticalGender.MASCULINE)
            "they" -> Pronoun(summary, language, "they", "them", "their", "theirs", "themselves", GrammaticalGender.NEUTER)
            "it" -> Pronoun(summary, language, "it", "it", "its", "its", "itself", GrammaticalGender.INANIMATE)
            else -> Pronoun(summary = summary, language = language)
        }
    }

    /** Possessive determiner ("her"/"his"/"their"/"its"), or null when it can't be determined. */
    fun possessiveDeterminer(pronoun: Pronoun): String? {
        pronoun.possessiveDeterminer?.takeIf { it.isNotBlank() }?.let { return it }
        pronoun.grammaticalGender?.let { genderToDeterminer(it)?.let { d -> return d } }
        val subject = pronoun.subject ?: pronoun.summary.substringBefore('/')
        return when (subject.trim().lowercase()) {
            "she" -> "her"
            "he" -> "his"
            "they" -> "their"
            "it" -> "its"
            else -> null
        }
    }

    private fun genderToDeterminer(grammaticalGender: String): String? = when (grammaticalGender.lowercase()) {
        GrammaticalGender.FEMININE -> "her"
        GrammaticalGender.MASCULINE -> "his"
        GrammaticalGender.INANIMATE -> "its"
        GrammaticalGender.NEUTER, GrammaticalGender.COMMON -> "their"
        else -> null
    }
}

/**
 * Pronoun entries whose language matches the viewer's locale, or the whole (preference-ordered) list
 * when none match — so we honor the required MSC4247 `language` field without ever showing nothing.
 */
fun List<Pronoun>.forViewerLanguage(language: String = Locale.getDefault().language): List<Pronoun> {
    val matches = filter { it.language?.substringBefore('-')?.equals(language, ignoreCase = true) == true }
    return matches.ifEmpty { this }
}
