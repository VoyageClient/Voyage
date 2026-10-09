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
import im.vector.app.features.imagepack.EmojiShortNames
import im.vector.app.features.imagepack.EmoteShortcodeProcessor
import im.vector.app.features.imagepack.ResolvedImage
import im.vector.app.features.reactions.data.EmojiDataSource
import im.vector.app.features.reactions.data.EmojiItem
import im.vector.app.features.reactions.data.FrecencyDataSource
import im.vector.app.features.reactions.data.RecentEmojiDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import javax.inject.Inject

class AutocompleteEmojiPresenter @Inject constructor(
        context: Context,
        private val emojiDataSource: EmojiDataSource,
        private val frecencyDataSource: FrecencyDataSource,
        private val recentEmojiDataSource: RecentEmojiDataSource,
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

    override fun clearData() {
        controller.setData(emptyList())
    }

    override fun instantiateRecyclerView(): RecyclerView = dividedRecyclerView(MAX_VISIBLE_EMOJIS)

    override fun onViewShown() = slideContentUpOnShow()

    override fun animateViewOut(onEnd: Runnable) = slideContentDownOnHide(onEnd)

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
            val allEmotes = customEmotes
            val emotes = filterEmotes(allEmotes, queryString).map { AutocompleteEmojiData.Emote(it) }
            val data = withContext(Dispatchers.Default) {
                // An emote sharing an emoji's shortcode was disambiguated, so the emoji goes by `name@emoji`.
                val sharedShortcodes = allEmotes.mapNotNullTo(HashSet()) { emote ->
                    EmoteShortcodeProcessor.baseOf(emote.shortcode).takeIf { it != emote.shortcode }
                }
                fun rowOf(item: EmojiItem): AutocompleteEmojiData.Emoji {
                    val shortcode = item.shortcode.let { if (it in sharedShortcodes) it + EmojiShortNames.UNICODE_SUFFIX else it }
                    return AutocompleteEmojiData.Emoji(item, shortcode)
                }
                if (queryString.isNullOrBlank()) {
                    frequentFirst(allEmotes, ::rowOf)
                } else {
                    val emojis = im.vector.app.core.utils.PerfTrace.time("autocomplete.emoji.filter") {
                        emojiDataSource.filterWith(queryString)
                    }.map(::rowOf)
                    val frecency = frecencyDataSource.snapshot()
                    AutocompleteEmojiRanking.sort(emotes + emojis, queryString) { row ->
                        when (row) {
                            is AutocompleteEmojiData.Emoji -> frecency.score(row.emojiItem.emoji)
                            is AutocompleteEmojiData.Emote -> frecency.score(row.image.mxcUrl)
                        }
                    }
                }
            }
            // Keep the current rows on screen so they are what animates away, rather than collapsing first.
            if (data.isEmpty()) requestDismiss() else controller.setData(data)
            if (signalArrival && emotes.isNotEmpty()) {
                onEmotesArrived?.invoke()
            }
        }
    }

    // A bare `:` lists the most frecent emojis and emotes, then the other emotes and the default quick reactions.
    private suspend fun frequentFirst(emotes: List<ResolvedImage>, rowOf: (EmojiItem) -> AutocompleteEmojiData.Emoji): List<AutocompleteEmojiData> {
        val emotesByMxc = emotes.associateBy { it.mxcUrl }
        val frequent = recentEmojiDataSource.frequentlyUsed().mapNotNull { (emojiOrMxc, _) ->
            if (emojiOrMxc.isMxcUrl()) {
                emotesByMxc[emojiOrMxc]?.let { AutocompleteEmojiData.Emote(it) }
            } else {
                emojiDataSource.itemFor(emojiOrMxc)?.let(rowOf)
            }
        }
        val rest = emotes.map { AutocompleteEmojiData.Emote(it) } + emojiDataSource.getQuickReactions().map(rowOf)
        return (frequent + rest).distinctBy { row ->
            when (row) {
                is AutocompleteEmojiData.Emoji -> row.emojiItem.emoji
                is AutocompleteEmojiData.Emote -> row.image.mxcUrl
            }
        }
    }

    private fun filterEmotes(emotes: List<ResolvedImage>, query: String?): List<ResolvedImage> {
        if (query.isNullOrBlank()) return emotes
        return emotes.filter { AutocompleteEmojiRanking.matches(it.shortcode, query) }
    }

    companion object {
        private const val QUERY_DEBOUNCE_MS = 80L
        private const val MAX_VISIBLE_EMOJIS = 3
    }
}
