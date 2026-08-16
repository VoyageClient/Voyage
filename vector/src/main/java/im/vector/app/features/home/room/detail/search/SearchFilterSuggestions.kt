/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.search

import androidx.annotation.DrawableRes
import im.vector.app.R
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.search.SearchFilters
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.toMatrixItem

data class SearchSuggestion(
        val label: String,
        val hint: String?,
        /** The whole search term once this suggestion is applied. */
        val query: String,
        @DrawableRes val icon: Int,
        /** Set for a room member, whose avatar replaces [icon]. */
        val avatar: MatrixItem? = null,
        /** Range of [query] the member's mention pill covers. */
        val pillRange: IntRange? = null,
)

/**
 * Completions for the filter being typed, so the `from:`/`has:`/… syntax is discoverable instead of
 * hidden: an empty term offers every filter key, and a key offers its values (members for the user
 * filters, media kinds for `has:`), narrowed by whatever is typed after the colon.
 */
object SearchFilterSuggestions {

    private const val MAX_MEMBERS = 8

    fun suggestionsFor(term: String, members: List<RoomMemberSummary>): List<SearchSuggestion> {
        val tokenStart = term.indexOfLast { it.isWhitespace() } + 1
        val prefix = term.substring(0, tokenStart)
        val token = term.substring(tokenStart)
        // Inside quotes everything is literal text, so no filter can start there.
        if (prefix.count { it == '"' } % 2 == 1) return emptyList()

        val colon = token.indexOf(':')
        if (colon <= 0) return keySuggestions(prefix, token)

        val key = token.substring(0, colon).lowercase()
        val typed = token.substring(colon + 1).lowercase()
        return when (key) {
            SearchFilters.HAS -> SearchFilters.hasOptions
                    .filter { it.startsWith(typed) }
                    .map { SearchSuggestion(label = "$key:$it", hint = null, query = "$prefix$key:$it ", icon = hasIcon(it)) }
            in SearchFilters.userKeys -> members
                    .filter { it.matches(typed) }
                    .take(MAX_MEMBERS)
                    .map {
                        val pillStart = prefix.length + key.length + 1
                        SearchSuggestion(
                                label = it.displayName ?: it.userId,
                                hint = it.userId,
                                query = "$prefix$key:${it.userId} ",
                                icon = keyIcon(key),
                                avatar = it.toMatrixItem(),
                                pillRange = pillStart until pillStart + it.userId.length,
                        )
                    }
            // A date has nothing to enumerate and an unknown key is just text being typed: in both
            // cases the keys themselves are the useful completion.
            else -> keySuggestions(prefix, token)
        }
    }

    private fun RoomMemberSummary.matches(typed: String) = typed.isEmpty() ||
            userId.contains(typed, ignoreCase = true) ||
            displayName?.contains(typed, ignoreCase = true) == true

    private fun keySuggestions(prefix: String, token: String): List<SearchSuggestion> {
        val typed = token.lowercase()
        return SearchFilters.keys
                .filter { it.startsWith(typed) }
                .map { SearchSuggestion(label = "$it:", hint = keyHint(it), query = "$prefix$it:", icon = keyIcon(it)) }
    }

    private fun keyHint(key: String) = when (key) {
        in SearchFilters.userKeys -> "@user:server"
        SearchFilters.HAS -> SearchFilters.hasOptions.joinToString(", ")
        else -> "YYYY-MM-DD"
    }

    @DrawableRes
    private fun keyIcon(key: String) = when (key) {
        SearchFilters.FROM -> R.drawable.ic_user
        SearchFilters.MENTIONS -> R.drawable.ic_search_filter_mention
        SearchFilters.HAS -> R.drawable.ic_attachment_file
        else -> R.drawable.ic_clock
    }

    @DrawableRes
    private fun hasIcon(value: String) = when (value) {
        "image" -> R.drawable.ic_attachment_gallery
        "video" -> R.drawable.ic_video
        "audio" -> R.drawable.ic_microphone
        "sticker" -> R.drawable.ic_attachment_sticker
        "poll" -> R.drawable.ic_attachment_poll
        SearchFilters.HAS_LINK -> R.drawable.ic_search_filter_link
        else -> R.drawable.ic_attachment_file
    }
}
