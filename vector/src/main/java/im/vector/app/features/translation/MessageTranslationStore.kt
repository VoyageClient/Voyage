/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import androidx.core.text.HtmlCompat
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory "this message is shown translated" state, keyed by event id, for the long-press
 * Translate / Untranslate toggle. Lives for the process, so leaving and re-entering a room keeps
 * translations. [updates] emits the event id whenever an entry changes so the timeline can rebuild
 * just that item; [errors] carries failure toasts.
 */
@Singleton
class MessageTranslationStore @Inject constructor(
        private val client: TranslationClient,
        private val stringProvider: StringProvider,
) {
    /** [formatted] is the translated formatted body (markup/pills preserved), when the message had one. */
    data class Translation(val text: String, val sourceLanguage: String?, val targetLanguage: String, val formatted: String? = null)

    private class Entry(val translation: Translation, val source: String?)

    private val translations = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates: SharedFlow<String> = _updates

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    /** Unchecked against edits; only for callers that already went through [get] with the event. */
    fun get(eventId: String): Translation? {
        val entry = translations[eventId] ?: return null
        // A language switch makes old translations stale — they targeted the previous app language.
        if (entry.translation.targetLanguage != TranslationLanguages.appLanguage()) {
            translations.remove(eventId, entry)
            return null
        }
        return entry.translation
    }

    /** Also drops the translation once the message has been edited since it was translated. */
    fun get(event: TimelineEvent): Translation? {
        val entry = translations[event.eventId] ?: return null
        if (entry.source != sourceOf(event)) {
            translations.remove(event.eventId, entry)
            return null
        }
        return get(event.eventId)
    }

    fun isTranslated(event: TimelineEvent): Boolean = get(event) != null

    fun isTranslating(eventId: String): Boolean = eventId in inFlight

    fun untranslate(eventId: String) {
        if (translations.remove(eventId) != null) _updates.tryEmit(eventId)
    }

    /**
     * Translates [text] (the message's plain body, reply fallback already stripped) for [eventId].
     * When [formattedBody] is given, it is translated instead — markup, mention pills and line
     * structure survive — and the plain text is derived from the result.
     */
    fun translate(eventId: String, source: String?, text: String, formattedBody: String? = null) {
        if (!inFlight.add(eventId)) return
        scope.launch {
            try {
                // A formatted body whose visible text is all protected markup (e.g. one code block)
                // falls back to translating the plain body.
                val htmlExceptions = formattedBody?.let { TranslationExceptions.forReceivedHtml(it) }?.takeIf { it.hasTranslatableText }
                val exceptions = htmlExceptions ?: TranslationExceptions.forReceived(text)
                if (!exceptions.hasTranslatableText) {
                    _errors.tryEmit(stringProvider.getString(CommonStrings.translation_nothing_to_translate))
                    return@launch
                }
                when (val result = client.translate(exceptions.text, TranslationLanguages.AUTO, TranslationLanguages.APP)) {
                    is TranslationResult.Failure -> _errors.tryEmit(result.message)
                    is TranslationResult.Success -> {
                        val restored = exceptions.restore(result.text)
                        val translation = if (htmlExceptions != null) {
                            Translation(htmlToPlain(restored), result.detectedSource, TranslationLanguages.appLanguage(), formatted = restored)
                        } else {
                            Translation(restored, result.detectedSource, TranslationLanguages.appLanguage())
                        }
                        translations[eventId] = Entry(translation, source)
                        _updates.tryEmit(eventId)
                    }
                }
            } finally {
                inFlight.remove(eventId)
            }
        }
    }

    private fun htmlToPlain(html: String): String =
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()

    companion object {
        /** What a translation was made from; a differing value means the message was edited since. */
        fun sourceOf(event: TimelineEvent): String? = event.getVectorLastMessageContent()?.body
    }
}
