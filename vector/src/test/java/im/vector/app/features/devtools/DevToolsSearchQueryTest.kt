/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.devtools

import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test

class DevToolsSearchQueryTest {

    private val type = "m.room.member"
    private val stateKey = "@alice:example.org"
    private val content = mapOf(
            "membership" to "join",
            "displayname" to "Alice",
            "info" to mapOf("size" to 42L, "tags" to listOf("admin", "moderator"))
    )

    private fun matches(query: String) = DevToolsSearchQuery.parse(query).matches(type, stateKey, content)

    @Test
    fun `blank query is empty`() {
        DevToolsSearchQuery.parse("   ").isEmpty.shouldBeTrue()
        DevToolsSearchQuery.parse("type:").isEmpty.shouldBeTrue()
    }

    @Test
    fun `bare term matches type, state key or content`() {
        matches("member").shouldBeTrue()
        matches("ALICE:EXAMPLE").shouldBeTrue()
        matches("displayname").shouldBeTrue()
        matches("moderator").shouldBeTrue()
        matches("42").shouldBeTrue()
        matches("bob").shouldBeFalse()
    }

    @Test
    fun `field prefixes restrict the search`() {
        matches("type:m.room.mem").shouldBeTrue()
        matches("type:alice").shouldBeFalse()
        matches("key:alice").shouldBeTrue()
        matches("key:join").shouldBeFalse()
        matches("content:join").shouldBeTrue()
        matches("content:m.room.member").shouldBeFalse()
    }

    @Test
    fun `terms are combined with and`() {
        matches("type:member join").shouldBeTrue()
        matches("type:member leave").shouldBeFalse()
    }

    @Test
    fun `quotes protect spaces`() {
        DevToolsSearchQuery.parse("\"space name\"")
                .matches("m.custom", "name space", null)
                .shouldBeFalse()
        DevToolsSearchQuery.parse("space name")
                .matches("m.custom", "name space", null)
                .shouldBeTrue()
    }

    @Test
    fun `null fields never match`() {
        DevToolsSearchQuery.parse("key:alice").matches(type, null, content).shouldBeFalse()
        DevToolsSearchQuery.parse("content:alice").matches(type, stateKey, null).shouldBeFalse()
    }
}
