/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.test.fixtures.aBuildMeta
import im.vector.lib.attachmentviewer.AttachmentViewerDebug
import io.mockk.mockk
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainAll
import org.junit.After
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
class AppLoggingTest {

    private val context = RuntimeEnvironment.getApplication()
    private val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val logsDirectory = File(context.cacheDir, "logs")
    private val fileLogger = VectorFileLogger(context)
    private val tagFileLogger = DebugTagFileLogger(context)

    @Before
    fun setUp() {
        defaultPrefs.edit { clear() }
        logsDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        fileLogger.close()
        tagFileLogger.close()
        DebugLog.enabled = false
        AttachmentViewerDebug.logger = null
        drainWriters()
    }

    private fun drainWriters() {
        LogHealth.awaitIdle().shouldBeTrue()
        tagFileLogger.writer.awaitIdle().shouldBeTrue()
        fileLogger.writer.awaitIdle().shouldBeTrue()
    }

    private fun preferences(isDebug: Boolean) = VectorPreferences(
            context = context,
            buildMeta = aBuildMeta().copy(isDebug = isDebug),
            vectorFeatures = mockk(relaxed = true),
            defaultPrefs = defaultPrefs,
    )

    private fun appLogging(isDebug: Boolean) = AppLogging(aBuildMeta().copy(isDebug = isDebug), preferences(isDebug), fileLogger, tagFileLogger)

    @Test
    fun `a debug build defaults logging and developer mode on`() {
        val preferences = preferences(isDebug = true)
        preferences.seedBuildTypeDefaultsIfNeeded()

        preferences.isLoggingEnabled().shouldBeTrue()
        preferences.developerMode().shouldBeTrue()
        defaultPrefs.getBoolean(VectorPreferences.SETTINGS_LOGGING_ENABLED_KEY, false).shouldBeTrue()
    }

    @Test
    fun `a release build defaults logging and developer mode off`() {
        val preferences = preferences(isDebug = false)
        preferences.seedBuildTypeDefaultsIfNeeded()

        preferences.isLoggingEnabled().shouldBeFalse()
        preferences.developerMode().shouldBeFalse()
        defaultPrefs.contains(VectorPreferences.SETTINGS_LOGGING_ENABLED_KEY).shouldBeTrue()
    }

    @Test
    fun `seeding keeps a choice the user already made`() {
        defaultPrefs.edit { putBoolean(VectorPreferences.SETTINGS_LOGGING_ENABLED_KEY, true) }
        val preferences = preferences(isDebug = false)
        preferences.seedBuildTypeDefaultsIfNeeded()

        preferences.isLoggingEnabled().shouldBeTrue()
        preferences.developerMode().shouldBeFalse()
    }

    @Test
    fun `enabling plants the file loggers and the traces`() {
        appLogging(isDebug = false).apply(true)

        Timber.forest().shouldContainAll(listOf(fileLogger, tagFileLogger))
        Timber.treeCount shouldBeEqualTo 2
        DebugLog.enabled.shouldBeTrue()
    }

    @Test
    fun `a debug build also logs to logcat`() {
        appLogging(isDebug = true).apply(true)

        Timber.treeCount shouldBeEqualTo 3
    }

    @Test
    fun `media and receipt diagnostics use the saved DBG logs without release logcat`() {
        appLogging(isDebug = false).apply(true)
        ShadowLog.clear()

        AttachmentViewerDebug.i { "stage=prepared-resume" }
        AttachmentViewerDebug.w { "stage=sample-warning-without-exception" }
        AttachmentViewerDebug.w(IllegalStateException("Sample failure")) { "stage=sample-warning" }
        DebugLog.i { "READDBG stage=marker-request" }
        drainWriters()

        File(logsDirectory, "videodbg.txt").readText().contains("VIDEODBG stage=prepared-resume").shouldBeTrue()
        File(logsDirectory, "videodbg.txt").readText().contains("VIDEODBG stage=sample-warning").shouldBeTrue()
        File(logsDirectory, "videodbg.txt").readText().contains("VIDEODBG stage=sample-warning-without-exception").shouldBeTrue()
        File(logsDirectory, "readdbg.txt").readText().contains("READDBG stage=marker-request").shouldBeTrue()
        ShadowLog.getLogs().none { it.tag == "VIDEODBG" || it.tag == "READDBG" }.shouldBeTrue()
    }

    @Test
    fun `disabled media diagnostics do not evaluate their messages`() {
        val appLogging = appLogging(isDebug = false)
        appLogging.apply(true)
        AttachmentViewerDebug.enabled.shouldBeTrue()
        appLogging.apply(false)
        var evaluations = 0

        AttachmentViewerDebug.i { evaluations++; "stage=disabled" }
        AttachmentViewerDebug.w(IllegalStateException("Sample failure")) { evaluations++; "stage=disabled-warning" }

        AttachmentViewerDebug.enabled.shouldBeFalse()
        evaluations shouldBeEqualTo 0
    }

    @Test
    fun `logcat skips verbose and debug lines of chatty tags but keeps their warnings`() {
        Timber.plant(LogcatTree())
        ShadowLog.clear()

        Timber.tag("CRYPTO/MXOlmDevice").v("## decryptGroupMessage() timeline: abc")
        Timber.tag("CRYPTO/MXOlmDevice").d("## decryptGroupMessage() roomId: !room:example.org")
        Timber.tag("CRYPTO/MXOlmDevice").w("## decryptGroupMessage() failed")
        Timber.tag("TimelineFragment").v("kept")

        ShadowLog.getLogs().map { it.tag to it.msg } shouldBeEqualTo listOf(
                "CRYPTO/MXOlmDevice" to "## decryptGroupMessage() failed",
                "TimelineFragment" to "kept",
        )
    }

    @Test
    fun `logcat filters a chatty class by its derived tag`() {
        Timber.plant(LogcatTree())
        ShadowLog.clear()

        StreamEventsManager().log()

        ShadowLog.getLogs().map { it.tag to it.msg } shouldBeEqualTo listOf("StreamEventsManager" to "warned")
    }

    @Test
    fun `logcat filters a lambda inside a chatty class`() {
        LogcatTree().isLoggable("UpdateTrustTaskBody\$updateRoomShieldInSummaries", Log.VERBOSE).shouldBeFalse()
        LogcatTree().isLoggable("UpdateTrustTaskBodyHelper", Log.VERBOSE).shouldBeTrue()
    }

    @Test
    fun `disabling uproots everything and deletes the saved logs`() {
        val appLogging = appLogging(isDebug = false)
        appLogging.apply(true)
        Timber.i("PAGDBG fetching page 4")
        drainWriters()
        logsDirectory.listFiles().orEmpty().any { it.name == "pagdbg.txt" }.shouldBeTrue()

        appLogging.apply(false)

        Timber.treeCount shouldBeEqualTo 0
        DebugLog.enabled.shouldBeFalse()
        drainWriters()
        logsDirectory.listFiles().orEmpty().toList().shouldBeEmpty()
    }

    @Test
    fun `starting with logging off clears logs left by an earlier run`() {
        logsDirectory.mkdirs()
        File(logsDirectory, "logs.0.txt").writeText("an old session")

        appLogging(isDebug = false).apply(false)

        Timber.treeCount shouldBeEqualTo 0
        drainWriters()
        logsDirectory.listFiles().orEmpty().toList().shouldBeEmpty()
    }

    @Test
    fun `disabling never waits for a blocked category writer and queued lines stay deleted`() {
        val appLogging = appLogging(isDebug = false)
        appLogging.apply(true)
        drainWriters()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        tagFileLogger.writer.executeControl {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        try {
            entered.await(2, TimeUnit.SECONDS).shouldBeTrue()
            Timber.i("TESTDBG obsolete")
            val started = System.nanoTime()
            appLogging.apply(false)
            ((System.nanoTime() - started) / 1_000_000 < 2000).shouldBeTrue()
            DebugLog.enabled.shouldBeFalse()
        } finally {
            release.countDown()
        }
        drainWriters()
        logsDirectory.listFiles().orEmpty().toList().shouldBeEmpty()
    }

    @Test
    fun `a pending disable cannot delete a newly enabled session`() {
        val appLogging = appLogging(isDebug = false)
        appLogging.apply(true)
        drainWriters()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        tagFileLogger.writer.executeControl {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        try {
            entered.await(2, TimeUnit.SECONDS).shouldBeTrue()
            appLogging.apply(false)
            appLogging.apply(true)
            Timber.i("TESTDBG current session")
        } finally {
            release.countDown()
        }
        drainWriters()
        File(logsDirectory, "testdbg.txt").readText().contains("current session").shouldBeTrue()
        File(logsDirectory, "logs.0.txt").readText().contains("current session").shouldBeTrue()
        appLogging.apply(false)
        drainWriters()
        logsDirectory.listFiles().orEmpty().toList().shouldBeEmpty()
    }

    @Test
    fun `reopening the file log continues it instead of rotating`() {
        fileLogger.open()
        fileLogger.close()
        fileLogger.open()

        drainWriters()
        File(logsDirectory, "logs.0.txt").exists().shouldBeTrue()
        File(logsDirectory, "logs.1.txt").exists().shouldBeFalse()
    }

    @Test
    fun `applying the same state twice does not plant twice`() {
        val appLogging = appLogging(isDebug = false)
        appLogging.apply(true)
        appLogging.apply(true)

        Timber.treeCount shouldBeEqualTo 2
    }
}

// Top level, as Timber derives a nested class's tag from its outer one too.
private class StreamEventsManager {
    fun log() {
        Timber.v("## dispatchLiveEventDecrypted")
        Timber.w("warned")
    }
}
