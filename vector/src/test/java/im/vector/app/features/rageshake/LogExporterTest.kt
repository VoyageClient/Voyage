/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.content.Context
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.resources.BuildMeta
import im.vector.lib.core.utils.timer.Clock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldStartWith
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class LogExporterTest {

    @get:Rule val temp = TemporaryFolder()

    private val buildMeta = BuildMeta(
            isDebug = false,
            applicationId = "org.example.app",
            applicationName = "Example App",
            lowPrivacyLoggingEnabled = false,
            versionName = "1.2.3",
            gitRevision = "abc1234",
            gitRevisionDate = "2026-01-01",
            gitBranchName = "develop",
            flavorDescription = "",
            flavorShortDescription = "",
    )

    private fun exporter(cacheDir: File) = LogExporter(
            context = mockk<Context> { every { this@mockk.cacheDir } returns cacheDir },
            buildMeta = buildMeta,
            dispatchers = CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined),
            clock = mockk<Clock> { every { epochMillis() } returns 1_700_000_000_000 },
            notificationUtils = mockk(),
    )

    @Test
    fun `the zip holds every log file and the crash report, but not the lock file`() {
        val cache = temp.newFolder("cache")
        File(cache, "logs").mkdirs()
        File(cache, "logs/logs.0.txt").writeText("I/ first")
        File(cache, "logs/logs.1.txt").writeText("I/ second")
        File(cache, "logs/trustdbg.txt").writeText("TRUSTDBG line")
        File(cache, "logs/logs.0.txt.lck").writeText("")
        File(cache, "crash.log").writeText("boom")

        val bytes = ByteArrayOutputStream().also { exporter(cache).writeZip(it) }.toByteArray()

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.reader().readText() }
        }
        entries.keys.toList() shouldBeEqualTo listOf("info.txt", "logs.0.txt", "logs.1.txt", "trustdbg.txt", "crash.log")
        entries["logs.1.txt"] shouldBeEqualTo "I/ second"
        entries["info.txt"]!! shouldStartWith "Example App 1.2.3 (release)"
    }

    @Test
    fun `the file name has no characters that FAT storage rejects`() {
        exporter(temp.newFolder("cache")).fileName().matches(Regex("example-app-logs-[0-9_-]+\\.zip")) shouldBeEqualTo true
    }
}
