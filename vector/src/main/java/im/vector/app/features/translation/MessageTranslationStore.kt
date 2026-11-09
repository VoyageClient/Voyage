/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import androidx.core.text.HtmlCompat
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

    /** The latest edit a translation was made from; a null revision is the unedited original. */
    data class Revision(val editId: String?, val editTs: Long)

    private class Entry(val translation: Translation, val source: Revision?)

    private val translations = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    // Local echo id -> synced event id, for own messages translated before their send completed.
    private val remoteIds = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates: SharedFlow<String> = _updates

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    /** Unchecked against edits; only for callers that already went through [get] with the event. */
    fun get(eventId: String): Translation? {
        val key = resolve(eventId)
        val entry = translations[key] ?: return null
        // A language switch makes old translations stale — they targeted the previous app language.
        if (entry.translation.targetLanguage != TranslationLanguages.appLanguage()) {
            translations.remove(key, entry)
            return null
        }
        return entry.translation
    }

    /** Also drops the translation once the message has been edited since it was translated. */
    fun get(event: TimelineEvent): Translation? {
        val entry = translations[event.eventId] ?: adoptLocalEcho(event) ?: return null
        if (isEditedSince(entry.source, sourceOf(event))) {
            translations.remove(event.eventId, entry)
            return null
        }
        return get(event.eventId)
    }

    fun isTranslated(event: TimelineEvent): Boolean = get(event) != null

    fun isTranslating(eventId: String): Boolean =
            eventId in inFlight || remoteIds.any { (localEchoId, remoteId) -> remoteId == eventId && localEchoId in inFlight }

    fun untranslate(eventId: String) {
        val key = resolve(eventId)
        if (translations.remove(key) != null) _updates.tryEmit(key)
    }

    private fun resolve(eventId: String): String = remoteIds[eventId] ?: eventId

    // The synced copy of an own message carries its local echo id as the transaction id.
    private fun adoptLocalEcho(event: TimelineEvent): Entry? {
        val localEchoId = event.root.unsignedData?.transactionId?.takeIf { it.isNotEmpty() && it != event.eventId } ?: return null
        if (localEchoId !in inFlight && !translations.containsKey(localEchoId)) return null
        remoteIds[localEchoId] = event.eventId
        val entry = translations.remove(localEchoId) ?: return null
        return translations.putIfAbsent(event.eventId, entry) ?: entry
    }

    /**
     * Translates [text] (the message's plain body, reply fallback already stripped) for [eventId].
     * When [formattedBody] is given, it is translated instead — markup, mention pills and line
     * structure survive — and the plain text is derived from the result.
     */
    fun translate(eventId: String, source: Revision?, text: String, formattedBody: String? = null) {
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
                        val key = resolve(eventId)
                        translations[key] = Entry(translation, source)
                        _updates.tryEmit(key)
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
        // Keyed on the edit rather than the body: the same event loaded elsewhere (a jump-to context,
        // the actions sheet's fresh copy) can have its edit still undecrypted or its aggregation
        // missing, and must not count as edited.
        fun sourceOf(event: TimelineEvent): Revision? {
            val edit = event.annotations?.editSummary?.latestEdit ?: return null
            return Revision(edit.eventId, edit.originServerTs ?: event.annotations?.editSummary?.lastEditTs ?: 0L)
        }

        // Only a newer edit than the translated one invalidates it; a view that knows of no edit, or of
        // an older one, is just behind.
        private fun isEditedSince(translated: Revision?, current: Revision?): Boolean {
            if (current == null || current.editId == translated?.editId) return false
            return current.editTs > (translated?.editTs ?: Long.MIN_VALUE)
        }
    }
}
