/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.debug.DebugLog
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import timber.log.Timber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogHealthTest {
    private val context = RuntimeEnvironment.getApplication()
    private val directory = File(context.cacheDir, "logs")

    @Before
    fun setUp() {
        LogHealth.awaitIdle()
        directory.deleteRecursively()
        LogHealth.reset()
        DebugLog.enabled = true
        Timber.uprootAll()
    }

    @After
    fun tearDown() {
        DebugLog.enabled = false
        Timber.uprootAll()
        LogHealth.awaitIdle()
        directory.deleteRecursively()
    }

    @Test
    fun `a full writer queue records dropped lines outside the logger queue`() {
        val logger = VectorFileLogger(context)
        logger.open()
        val tags = DebugTagFileLogger(context)
        Timber.plant(logger, tags)
        val writer = logger.writer
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            writer.executeControl {
                entered.countDown()
                runCatching { release.await(5, TimeUnit.SECONDS) }
            }
            tags.writer.executeControl {
                entered.countDown()
                runCatching { release.await(5, TimeUnit.SECONDS) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            repeat(20_000) { DebugLog.i { "TESTDBG synthetic log line" } }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            println("Diagnostic file loggers: 20000 lines with blocked storage took ${elapsed}ms")
            assertTrue("Producer blocked for ${elapsed}ms", elapsed < 2000)
            assertTrue(LogHealth.awaitIdle())
            assertTrue(File(directory, "log-health.txt").readText().contains("reason=queue-drop count=1 queueDepth=1024"))
        } finally {
            release.countDown()
            logger.close()
            tags.close()
            assertTrue(writer.awaitIdle())
            assertTrue(tags.writer.awaitIdle())
        }
    }

    @Test
    fun `unwritable diagnostic storage reports failure without recursive Timber logging`() {
        directory.parentFile!!.mkdirs()
        directory.writeText("synthetic obstruction")
        ShadowLog.clear()
        val logger = DebugTagFileLogger(context)
        Timber.plant(logger)
        Timber.i("TESTDBG synthetic event")
        assertTrue(logger.writer.awaitIdle())
        assertTrue(LogHealth.awaitIdle())
        logger.close()
        assertTrue(logger.writer.awaitIdle())
        assertTrue(ShadowLog.getLogs().any { it.tag == "LogHealth" && "healthWriteFailed=" in it.msg })
    }
}
