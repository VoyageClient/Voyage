/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDetailsCreditsTest {
    @Test
    fun `matching artist prefixes are omitted from album credits`() {
        assertEquals("Artist • Album", AudioDetails.credits("Song", "Artist", "Artist - Album"))
        assertEquals("Artist • Album", AudioDetails.credits("Song", "Artist", "artist - Album"))
        assertEquals("Artist • Album", AudioDetails.credits("Song", "Artist", " Artist - Album "))
    }

    @Test
    fun `album names without author separators are preserved`() {
        assertEquals("Artist • Album", AudioDetails.credits("Song", "Artist", "Album"))
        assertEquals("Artist • Self-Titled", AudioDetails.credits("Song", "Artist", "Self-Titled"))
        assertEquals("Artist • Album - Deluxe", AudioDetails.credits("Song", "Artist", "Artist - Album - Deluxe"))
        assertEquals("Performer • Author - Album", AudioDetails.credits("Song", "Performer", "Author - Album"))
        assertEquals("Author - Album", AudioDetails.credits("Song", null, "Author - Album"))
    }

    @Test
    fun `redundant and empty albums are omitted after stripping the prefix`() {
        assertEquals("Artist", AudioDetails.credits("Song", "Artist", "Artist - Song"))
        assertEquals("Artist", AudioDetails.credits("Song", "Artist", "Artist - Artist"))
        assertEquals("Artist", AudioDetails.credits("Song", "Artist", "Artist - "))
        assertEquals("Artist", AudioDetails.credits("Song", "Artist", null))
    }
}
