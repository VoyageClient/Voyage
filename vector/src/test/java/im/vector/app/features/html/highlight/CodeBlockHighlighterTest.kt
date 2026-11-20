/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html.highlight

import im.vector.app.features.html.BodySegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CodeBlockHighlighterTest {

    private val highlighter = CodeBlockHighlighter(RuntimeEnvironment.getApplication())

    @Test
    fun `blocks of any size are highlighted`() {
        val diff = "-old line\n+new line\n".repeat(5_000)

        val request = highlighter.requestFor(BodySegment.Code(diff, languageClasses = listOf("diff")))

        assertEquals(CodeBlockHighlighter.Request(diff, listOf("diff")), request)
    }

    @Test
    fun `blocks naming no language stay plain`() {
        assertNull(highlighter.requestFor(BodySegment.Code("-old line\n+new line\n")))
    }
}
