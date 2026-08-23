/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
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
    data class Translation(val text: String, val sourceLanguage: String?, val targetLanguage: String)

    private val translations = ConcurrentHashMap<String, Translation>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val updates: SharedFlow<String> = _updates

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors

    fun get(eventId: String): Translation? {
        val translation = translations[eventId] ?: return null
        // A language switch makes old translations stale — they targeted the previous app language.
        if (translation.targetLanguage != TranslationLanguages.appLanguage()) {
            translations.remove(eventId)
            return null
        }
        return translation
    }

    fun isTranslated(eventId: String): Boolean = get(eventId) != null

    fun isTranslating(eventId: String): Boolean = eventId in inFlight

    fun untranslate(eventId: String) {
        if (translations.remove(eventId) != null) _updates.tryEmit(eventId)
    }

    /** Translates [text] (the message's plain body, reply fallback already stripped) for [eventId]. */
    fun translate(eventId: String, text: String) {
        if (!inFlight.add(eventId)) return
        scope.launch {
            try {
                val exceptions = TranslationExceptions.forReceived(text)
                if (!exceptions.hasTranslatableText) {
                    _errors.tryEmit(stringProvider.getString(CommonStrings.translation_nothing_to_translate))
                    return@launch
                }
                when (val result = client.translate(exceptions.text, TranslationLanguages.AUTO, TranslationLanguages.APP)) {
                    is TranslationResult.Failure -> _errors.tryEmit(result.message)
                    is TranslationResult.Success -> {
                        translations[eventId] = Translation(exceptions.restore(result.text), result.detectedSource, TranslationLanguages.appLanguage())
                        _updates.tryEmit(eventId)
                    }
                }
            } finally {
                inFlight.remove(eventId)
            }
        }
    }
}
