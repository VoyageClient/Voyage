/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.emoji

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.features.autocomplete.AutocompleteClickListener
import im.vector.app.features.autocomplete.RecyclerViewPresenter
import im.vector.app.features.imagepack.ResolvedImage
import im.vector.app.features.reactions.data.EmojiDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AutocompleteEmojiPresenter @Inject constructor(
        context: Context,
        private val emojiDataSource: EmojiDataSource,
        private val controller: AutocompleteEmojiController
) :
        RecyclerViewPresenter<AutocompleteEmojiData>(context), AutocompleteClickListener<AutocompleteEmojiData> {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queryJob: Job? = null

    // Custom emotes (MSC2545) for the current room, fed by AutoCompleter.
    private var customEmotes: List<ResolvedImage> = emptyList()
    private var lastQuery: CharSequence? = null

    // Invoked when a late emote update produces matches, so AutoCompleter can re-show a dismissed popup.
    var onEmotesArrived: (() -> Unit)? = null

    init {
        controller.listener = this
    }

    fun clear() {
        coroutineScope.coroutineContext.cancelChildren()
        controller.listener = null
        onEmotesArrived = null
    }

    fun updateCustomEmotes(emotes: List<ResolvedImage>) {
        customEmotes = emotes
        // Refresh the currently displayed results so newly loaded packs show up.
        refresh(lastQuery, signalArrival = true)
    }

    override fun instantiateAdapter(): RecyclerView.Adapter<*> {
        return controller.adapter
    }

    override fun onItemClick(t: AutocompleteEmojiData) {
        dispatchClick(t)
    }

    override fun onQuery(query: CharSequence?) {
        lastQuery = query
        refresh(query, signalArrival = false)
    }

    private fun refresh(query: CharSequence?, signalArrival: Boolean) {
        val queryString = query?.toString()
        queryJob?.cancel()
        queryJob = coroutineScope.launch {
            delay(QUERY_DEBOUNCE_MS)
            val emotes = filterEmotes(queryString).map { AutocompleteEmojiData.Emote(it) }
            val emojis = if (queryString.isNullOrBlank()) {
                emojiDataSource.getQuickReactions()
            } else {
                withContext(Dispatchers.Default) {
                    im.vector.app.core.utils.PerfTrace.time("autocomplete.emoji.filter") {
                        emojiDataSource.filterWith(queryString)
                    }
                }
            }.map { AutocompleteEmojiData.Emoji(it) }
            // Custom emotes first (MSC2545 suggestion priority over unicode emojis).
            controller.setData(emotes + emojis)
            if (signalArrival && emotes.isNotEmpty()) {
                onEmotesArrived?.invoke()
            }
        }
    }

    private fun filterEmotes(query: String?): List<ResolvedImage> {
        if (customEmotes.isEmpty()) return emptyList()
        if (query.isNullOrBlank()) return customEmotes
        return customEmotes.filter { it.shortcode.contains(query, ignoreCase = true) }
    }

    companion object {
        private const val QUERY_DEBOUNCE_MS = 80L
    }
}
