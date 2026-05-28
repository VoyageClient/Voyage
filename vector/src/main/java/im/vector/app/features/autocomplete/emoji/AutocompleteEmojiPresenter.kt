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
        RecyclerViewPresenter<String>(context), AutocompleteClickListener<String> {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var queryJob: Job? = null

    init {
        controller.listener = this
    }

    fun clear() {
        coroutineScope.coroutineContext.cancelChildren()
        controller.listener = null
    }

    override fun instantiateAdapter(): RecyclerView.Adapter<*> {
        return controller.adapter
    }

    override fun onItemClick(t: String) {
        dispatchClick(t)
    }

    override fun onQuery(query: CharSequence?) {
        // Filtering ~3700 emojis twice (name + keywords) per keystroke on the main thread
        // used to freeze typing — run it off the main thread and debounce briefly to absorb
        // fast typing.
        val queryString = query?.toString()
        queryJob?.cancel()
        queryJob = coroutineScope.launch {
            delay(QUERY_DEBOUNCE_MS)
            val data = if (queryString.isNullOrBlank()) {
                emojiDataSource.getQuickReactions()
            } else {
                withContext(Dispatchers.Default) {
                    emojiDataSource.filterWith(queryString)
                }
            }
            controller.setData(data)
        }
    }

    companion object {
        private const val QUERY_DEBOUNCE_MS = 80L
    }
}
