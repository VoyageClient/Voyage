/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.search

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary

class SearchFilterSuggestionsTest {

    private fun member(userId: String, displayName: String?) =
            RoomMemberSummary(membership = Membership.JOIN, userId = userId, displayName = displayName)

    private val members = listOf(
            member("@aaron:hs", "Aaron Everett"),
            member("@bea:hs", "Bea"),
            member("@ev:hs", "Ev"),
            member("@evelyn:hs", "Evelyn"),
            member("@zoe:hs", "Zoe Everson"),
    )

    @Test
    fun `a typed name ranks prefix matches above ones that only contain it`() {
        val labels = SearchFilterSuggestions.suggestionsFor("from:ev", members).map { it.label }

        labels shouldBeEqualTo listOf("Ev", "Evelyn", "Aaron Everett", "Zoe Everson")
    }

    @Test
    fun `a typed user id matches the localpart with or without the sigil`() {
        val withSigil = SearchFilterSuggestions.suggestionsFor("from:@ev", members).map { it.label }
        val without = SearchFilterSuggestions.suggestionsFor("from:ev", members).map { it.label }

        withSigil shouldBeEqualTo listOf("Ev", "Evelyn")
        without.take(2) shouldBeEqualTo listOf("Ev", "Evelyn")
    }

    @Test
    fun `an empty filter value offers the members in their given order`() {
        val labels = SearchFilterSuggestions.suggestionsFor("from:", members).map { it.label }

        labels shouldBeEqualTo members.map { it.displayName }
    }

    @Test
    fun `a completed suggestion queries by user id and pills exactly that id`() {
        val suggestion = SearchFilterSuggestions.suggestionsFor("cat from:evel", members).single()

        suggestion.query shouldBeEqualTo "cat from:@evelyn:hs "
        suggestion.query.substring(suggestion.pillRange!!) shouldBeEqualTo "@evelyn:hs"
    }
}
