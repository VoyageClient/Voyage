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
        logger = DebugTagFileLogger(context)
        Timber.plant(logger)
    }

    @After
    fun tearDown() {
        Timber.uproot(logger)
        logger.close()
        logger.writer.awaitIdle().shouldBeTrue()
    }

    private fun fileFor(name: String): File {
        logger.writer.awaitIdle().shouldBeTrue()
        return File(logsDirectory, name)
    }

    @Test
    fun `each tag is collected into its own file`() {
        Timber.i("PAGDBG fetching page 4")
        Timber.i("MEDIADBG render start event=a")
        Timber.i("CHUNKDBG splitting a range")

        fileFor("pagdbg.txt").readText().contains("fetching page").shouldBeTrue()
        fileFor("mediadbg.txt").readText().contains("render start").shouldBeTrue()
        fileFor("chunkdbg.txt").readText().contains("splitting a range").shouldBeTrue()
        fileFor("pagdbg.txt").readText().contains("MEDIADBG").shouldBeFalse()
    }

    @Test
    fun `a message with no tag is not collected at all`() {
        Timber.i("an ordinary log line")
        Timber.i("not at the start: PAGDBG something")

        logsDirectory.exists().shouldBeFalse()
    }

    @Test
    fun `the tag must be a whole word`() {
        Timber.i("PAGDBGX not really a tag")

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
    fun `reopening appends non ASCII records instead of truncating`() {
        Timber.i("VIEWDBG café first")
        logger.writer.awaitIdle().shouldBeTrue()
        logger.close()
        logger.writer.awaitIdle().shouldBeTrue()
        logger.open()
        Timber.i("VIEWDBG deuxième")
        val written = fileFor("viewdbg.txt").readText()
        written.contains("café first").shouldBeTrue()
        written.contains("deuxième").shouldBeTrue()
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
