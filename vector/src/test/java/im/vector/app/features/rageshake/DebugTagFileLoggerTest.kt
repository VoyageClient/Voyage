/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.File

// Robolectric here goes no higher than 34, while the project targets 35.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugTagFileLoggerTest {

    private val context = RuntimeEnvironment.getApplication()
    private val logsDirectory = File(context.cacheDir, "logs")
    private lateinit var logger: DebugTagFileLogger

    @Before
    fun setUp() {
        logsDirectory.deleteRecursively()
        // Planted rather than called directly: the tree's log() is protected, and going through Timber
        // is the path the app actually uses.
        logger = DebugTagFileLogger(context)
        Timber.plant(logger)
    }

    @After
    fun tearDown() {
        Timber.uproot(logger)
    }

    private fun fileFor(name: String) = File(logsDirectory, name)

    @Test
    fun `each tag is collected into its own file`() {
        Timber.i("GAPDBG healing the boundary under chunk 4")
        Timber.i("MEDIADBG render start event=a")
        Timber.i("CHUNKDBG splitting a range")

        fileFor("gapdbg.txt").readText().contains("healing the boundary").shouldBeTrue()
        fileFor("mediadbg.txt").readText().contains("render start").shouldBeTrue()
        fileFor("chunkdbg.txt").readText().contains("splitting a range").shouldBeTrue()
        // A tag's file holds only its own lines.
        fileFor("gapdbg.txt").readText().contains("MEDIADBG").shouldBeFalse()
    }

    @Test
    fun `a message with no tag is not collected at all`() {
        Timber.i("an ordinary log line")
        Timber.i("not at the start: GAPDBG something")

        logsDirectory.exists().shouldBeFalse()
    }

    @Test
    fun `the tag must be a whole word`() {
        Timber.i("GAPDBGX not really a tag")

        logsDirectory.exists().shouldBeFalse()
    }

    @Test
    fun `a throwable is written after the message it belongs to`() {
        Timber.w(IllegalStateException("boom"), "UTDDBG rescan failed")

        val written = fileFor("utddbg.txt").readText()
        written.contains("rescan failed").shouldBeTrue()
        written.contains("IllegalStateException").shouldBeTrue()
        (written.indexOf("rescan failed") < written.indexOf("IllegalStateException")).shouldBeTrue()
    }

    @Test
    fun `appending keeps earlier lines`() {
        Timber.i("VIEWDBG first")
        Timber.i("VIEWDBG second")

        val written = fileFor("viewdbg.txt").readText()
        written.contains("first").shouldBeTrue()
        written.contains("second").shouldBeTrue()
        written.trim().lines().size shouldBeEqualTo 2
    }
}
