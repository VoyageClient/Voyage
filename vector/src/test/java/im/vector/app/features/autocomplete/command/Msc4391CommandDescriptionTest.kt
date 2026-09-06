/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.command

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event

class Msc4391CommandDescriptionTest {

    @Test
    fun parsesCommandDescription() {
        val command = Event(
                eventId = "\$event",
                senderId = "@bot:example.org",
                content = mapOf(
                        "command" to "help",
                        "aliases" to listOf("h"),
                        "parameters" to listOf(
                                mapOf("key" to "topic"),
                                mapOf("key" to "verbose", "optional" to true),
                        ),
                        "description" to mapOf(
                                "m.text" to listOf(mapOf("body" to "Show help", "mimetype" to "text/plain")),
                        ),
                ),
        ).toMsc4391AutocompleteCommand()

        command?.command shouldBeEqualTo "/help"
        command?.aliases shouldBeEqualTo listOf("/h")
        command?.parameters shouldBeEqualTo "<topic> [verbose]"
        command?.description shouldBeEqualTo "Show help"
    }

    @Test
    fun sendsStructuredCommandToRegisteringAccount() {
        val event = Event(
                senderId = "@bot:example.org",
                content = mapOf(
                        "command" to "echo",
                        "parameters" to listOf(mapOf("key" to "text", "schema" to mapOf("schema_type" to "primitive", "type" to "string"))),
                ),
        )

        event.msc4391CommandContent("/echo", "/echo hello") shouldBeEqualTo mapOf(
                "org.matrix.msc4391.command" to mapOf("command" to "echo", "arguments" to mapOf("text" to "hello")),
                "m.mentions" to mapOf("user_ids" to listOf("@bot:example.org"), "room" to false),
        )
    }
}
