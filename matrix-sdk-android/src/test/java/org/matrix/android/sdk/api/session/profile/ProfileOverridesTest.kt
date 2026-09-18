/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo

class ProfileOverridesTest {

    @Test
    fun `encrypted media is accepted only in encrypted account data`() {
        val content = mapOf(
                USER_ID to mapOf(
                        ProfileKeys.AVATAR_URL to encryptedFile,
                        ProfileKeys.BANNER_URL to encryptedFile,
                        ProfileKeys.STATUS to encryptedFile,
                )
        )

        assertTrue(ProfileOverrides.parse(content, encrypted = true)[USER_ID]?.get(ProfileKeys.AVATAR_URL) is EncryptedFileInfo)
        assertTrue(ProfileOverrides.parse(content, encrypted = true)[USER_ID]?.get(ProfileKeys.BANNER_URL) is EncryptedFileInfo)
        assertFalse(ProfileKeys.STATUS in ProfileOverrides.parse(content, encrypted = true).getValue(USER_ID))
        assertNull(ProfileOverrides.parse(content, encrypted = false)[USER_ID])
    }

    @Test
    fun `known fields with invalid types are ignored`() {
        val parsed = ProfileOverrides.parse(
                mapOf(
                        USER_ID to mapOf(
                                ProfileKeys.DISPLAY_NAME to 4,
                                ProfileKeys.AVATAR_URL to "https://example.org/avatar",
                                ProfileKeys.PRONOUNS to "they/them",
                                ProfileKeys.TIMEZONE to emptyMap<String, Any>(),
                                ProfileKeys.STATUS to "away",
                                ProfileKeys.BIOGRAPHY to listOf("bio"),
                        )
                )
        )

        assertNull(parsed[USER_ID])
    }

    @Test
    fun `invalid user ids are ignored and null overrides are retained`() {
        val parsed = ProfileOverrides.parse(
                mapOf(
                        "alice" to mapOf(ProfileKeys.DISPLAY_NAME to "Alice"),
                        USER_ID to mapOf(ProfileKeys.DISPLAY_NAME to null),
                )
        )

        assertFalse("alice" in parsed)
        assertTrue(ProfileKeys.DISPLAY_NAME in parsed.getValue(USER_ID))
        assertNull(parsed.getValue(USER_ID)[ProfileKeys.DISPLAY_NAME])
    }

    @Test
    fun `media resolver prefers stable override and retains encryption metadata`() {
        ProfileOverrides.claim(SESSION_ID)
        try {
            val parsed = ProfileOverrides.parse(
                    mapOf(USER_ID to mapOf(ProfileKeys.BANNER_URL to encryptedFile)),
                    encrypted = true,
            )
            ProfileOverrides.set(SESSION_ID, parsed)

            val media = ProfileOverrides.mediaOr(
                    USER_ID,
                    listOf(ProfileKeys.BANNER_URL, ProfileKeys.BANNER_URL_UNSTABLE),
                    "mxc://example.org/server",
            )
            assertEquals("mxc://example.org/media", media?.url)
            assertTrue(media?.encryptedFile is EncryptedFileInfo)
        } finally {
            ProfileOverrides.release(SESSION_ID)
        }
    }

    @Test
    fun `an encrypted avatar override is resolvable from its url alone`() {
        withOverrides(mapOf(USER_ID to mapOf(ProfileKeys.AVATAR_URL to encryptedFile))) {
            assertEquals("mxc://example.org/media", ProfileOverrides.avatarUrlFor(USER_ID))
            assertEquals(ProfileOverrides.avatarDecryptionFor(USER_ID), ProfileOverrides.avatarDecryptionForUrl("mxc://example.org/media"))
            assertNull(ProfileOverrides.avatarDecryptionForUrl("mxc://example.org/other"))
            assertNull(ProfileOverrides.avatarDecryptionForUrl(null))
        }
    }

    @Test
    fun `a plain avatar override carries no key`() {
        withOverrides(mapOf(USER_ID to mapOf(ProfileKeys.AVATAR_URL to "mxc://example.org/plain"))) {
            assertNull(ProfileOverrides.avatarDecryptionFor(USER_ID))
            assertNull(ProfileOverrides.avatarDecryptionForUrl("mxc://example.org/plain"))
        }
    }

    @Test
    fun `changes names only the users whose overrides differ`() = runBlocking {
        ProfileOverrides.claim(SESSION_ID)
        try {
            val emitted = mutableListOf<Set<String>>()
            val job = launch(Dispatchers.Unconfined) { ProfileOverrides.changes.toList(emitted) }

            ProfileOverrides.set(SESSION_ID, mapOf(USER_ID to mapOf(ProfileKeys.DISPLAY_NAME to "Alice")))
            ProfileOverrides.set(
                    SESSION_ID,
                    mapOf(
                            USER_ID to mapOf(ProfileKeys.DISPLAY_NAME to "Alice"),
                            OTHER_USER_ID to mapOf(ProfileKeys.DISPLAY_NAME to "Bob"),
                    )
            )

            assertEquals(listOf(setOf(USER_ID), setOf(OTHER_USER_ID)), emitted)
            job.cancel()
        } finally {
            ProfileOverrides.release(SESSION_ID)
        }
    }

    private fun withOverrides(content: Map<String, Any?>, block: () -> Unit) {
        ProfileOverrides.claim(SESSION_ID)
        try {
            ProfileOverrides.set(SESSION_ID, ProfileOverrides.parse(content, encrypted = true))
            block()
        } finally {
            ProfileOverrides.release(SESSION_ID)
        }
    }

    private companion object {
        const val USER_ID = "@alice:example.org"
        const val OTHER_USER_ID = "@bob:example.org"
        const val SESSION_ID = "test-session"

        val encryptedFile = mapOf(
                "url" to "mxc://example.org/media",
                "key" to mapOf(
                        "alg" to "A256CTR",
                        "ext" to true,
                        "key_ops" to listOf("encrypt", "decrypt"),
                        "kty" to "oct",
                        "k" to "key",
                ),
                "iv" to "iv",
                "hashes" to mapOf("sha256" to "hash"),
                "v" to "v2",
        )
    }
}
