/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.filter.Filter
import org.matrix.android.sdk.internal.session.filter.ProfileFieldsFilter

/**
 * MSC4429 signals a cleared profile field with a literal JSON null, so the response has to survive
 * nulls sitting inside a map — the shape most JSON mappers reject.
 */
class SyncProfileUpdatesTest {

    private val adapter = MoshiProvider.providesMoshi().adapter(SyncResponse::class.java)

    private fun parse(json: String) = adapter.fromJson(json.trimIndent())!!

    @Test
    fun `parses profile updates including a cleared field`() {
        val response = parse(
                """
                {
                  "next_batch": "s1",
                  "users": {
                    "@alice:example.org": {
                      "profile_updates": {
                        "m.status": { "text": "swimming", "emoji": "🏊" },
                        "m.tz": null
                      }
                    }
                  }
                }
                """
        )

        val alice = response.profileUpdates?.get("@alice:example.org")?.profileUpdates!!
        alice["m.status"] shouldBeEqualTo mapOf("text" to "swimming", "emoji" to "🏊")
        alice.containsKey("m.tz") shouldBe true
        alice["m.tz"].shouldBeNull()
    }

    @Test
    fun `a null profile_updates means stop tracking the user`() {
        val response = parse(
                """
                {
                  "next_batch": "s1",
                  "users": { "@bob:example.org": { "profile_updates": null } }
                }
                """
        )

        response.profileUpdates?.containsKey("@bob:example.org") shouldBe true
        response.profileUpdates?.get("@bob:example.org")?.profileUpdates.shouldBeNull()
    }

    @Test
    fun `a profile field may be any json value`() {
        // m.tz is a bare string while m.status is an object, so the value type cannot be narrowed.
        val response = parse(
                """
                {
                  "next_batch": "s1",
                  "users": { "@alice:example.org": { "profile_updates": { "m.tz": "Europe/London" } } }
                }
                """
        )

        response.profileUpdates?.get("@alice:example.org")?.profileUpdates?.get("m.tz") shouldBeEqualTo "Europe/London"
    }

    @Test
    fun `the stable users field wins over the unstable one`() {
        val response = parse(
                """
                {
                  "next_batch": "s1",
                  "org.matrix.msc4429.users": { "@unstable:example.org": { "profile_updates": {} } },
                  "users": { "@stable:example.org": { "profile_updates": {} } }
                }
                """
        )

        response.profileUpdates?.keys shouldBeEqualTo setOf("@stable:example.org")
    }

    @Test
    fun `the unstable users field is read when the stable one is absent`() {
        val response = parse(
                """
                {
                  "next_batch": "s1",
                  "org.matrix.msc4429.users": { "@unstable:example.org": { "profile_updates": {} } }
                }
                """
        )

        response.profileUpdates?.keys shouldBeEqualTo setOf("@unstable:example.org")
    }

    @Test
    fun `a sync response without profile updates has none`() {
        parse("""{ "next_batch": "s1" }""").profileUpdates.shouldBeNull()
    }

    @Test
    fun `the filter serializes profile fields under the requested prefix`() {
        val filterAdapter = MoshiProvider.providesMoshi().adapter(Filter::class.java)
        val ids = listOf("m.status", "m.tz")

        filterAdapter.toJson(Filter(profileFields = ProfileFieldsFilter(ids))) shouldBeEqualTo
                """{"profile_fields":{"ids":["m.status","m.tz"]}}"""
        filterAdapter.toJson(Filter(unstableProfileFields = ProfileFieldsFilter(ids))) shouldBeEqualTo
                """{"org.matrix.msc4429.profile_fields":{"ids":["m.status","m.tz"]}}"""
    }
}
