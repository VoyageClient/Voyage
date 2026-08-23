/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import android.text.Spanned
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import javax.inject.Inject

/**
 * Translates composer text before it is sent (`/translate`, or a room with auto-translate on).
 * Mention pills are swapped for placeholders so they come back as proper mention links.
 */
class OutgoingMessageTranslator @Inject constructor(
        private val client: TranslationClient,
) {
    sealed class Outcome {
        /** [formatted] is non-null only when the text carried mention pills. */
        data class Translated(val text: String, val formatted: String?) : Outcome()
        object Unchanged : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    private class Pill(val id: String, val name: String)

    suspend fun translate(message: CharSequence, targetOverride: String? = null): Outcome {
        val pills = ArrayList<Pill>()
        val withPlaceholders = extractPills(message, pills)
        // Seeding with the placeholders themselves keeps `{{i}}` in the restored text, so pills are
        // expanded last, into plain names or mention links depending on the body being built.
        val exceptions = TranslationExceptions.forSent(withPlaceholders, pills.indices.map { "{{$it}}" })
        if (!exceptions.hasTranslatableText) return Outcome.Unchanged

        val target = targetOverride ?: TranslationLanguages.APP
        val translated = when (val result = client.translate(exceptions.text, TranslationLanguages.AUTO, target)) {
            is TranslationResult.Failure -> return Outcome.Failed(result.message)
            is TranslationResult.Success -> exceptions.restore(result.text)
        }
        val plain = expandPills(translated, pills) { it.name }
        val formatted = if (pills.isNotEmpty()) {
            expandPills(escape(translated), pills) { pill -> "<a href=\"https://matrix.to/#/${pill.id}\">${escape(pill.name)}</a>" }
                    .replace("\n", "<br />")
        } else {
            null
        }
        return Outcome.Translated(plain, formatted)
    }

    private fun extractPills(message: CharSequence, out: MutableList<Pill>): String {
        val spanned = message as? Spanned ?: return message.toString()
        val spans = spanned.getSpans(0, message.length, MatrixItemSpan::class.java).sortedBy { spanned.getSpanStart(it) }
        if (spans.isEmpty()) return message.toString()
        return buildString {
            var index = 0
            spans.forEach { span ->
                val start = spanned.getSpanStart(span)
                val end = spanned.getSpanEnd(span)
                if (start < index) return@forEach
                append(message, index, start)
                append("{{").append(out.size).append("}}")
                out.add(Pill(span.matrixItem.id, message.subSequence(start, end).toString()))
                index = end
            }
            append(message, index, message.length)
        }
    }

    private fun expandPills(text: String, pills: List<Pill>, render: (Pill) -> String): String {
        if (pills.isEmpty()) return text
        return PILL.replace(text) { match -> pills.getOrNull(match.groupValues[1].toInt())?.let(render) ?: match.value }
    }

    private fun escape(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private val PILL = Regex("""[{｛]\s*[{｛]\s*(\d+)\s*[}｝]\s*[}｝]""")
    }
}
