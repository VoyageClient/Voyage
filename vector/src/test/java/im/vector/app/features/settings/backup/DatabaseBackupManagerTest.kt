/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import android.net.Uri
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.test.fakes.FakeClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.auth.data.sessionId
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val USER_ID = "@alice:example.org"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseBackupManagerTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dataDir = File(context.applicationInfo.dataDir)
    private val credentials = Credentials(USER_ID, "token", null, null, "DEVICE")
    private val sessionId = credentials.sessionId()

    private val authenticationService = mockk<AuthenticationService> {
        every { getAllSessionParams() } returns listOf(mockk<SessionParams> {
            every { userId } returns USER_ID
            every { credentials } returns this@DatabaseBackupManagerTest.credentials
        })
    }
    private val manager = DatabaseBackupManager(
            context = context,
            authenticationService = authenticationService,
            coroutineDispatchers = CoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined),
            clock = FakeClock().apply { givenEpoch(1_700_000_000_000) },
    )

    private fun write(file: File, text: String) = file.apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun givenAppData() {
        write(context.getDatabasePath("matrix-sdk-auth.db"), "auth")
        write(File(context.filesDir, "$sessionId/session_store.db"), "session")
        write(File(context.filesDir, "$sessionId/session_store.db-wal"), "session wal")
        write(File(context.filesDir, "$sessionId/session_store.db-shm"), "shm")
        write(File(context.filesDir, "$sessionId/tw/a.json"), "translation")
        write(File(context.filesDir, "preserved_media/a.jpg"), "media")
        write(File(context.filesDir, "mbgl-offline.db"), "tiles")
        write(File(dataDir, "shared_prefs/${context.packageName}_preferences.xml"), "settings")
        write(File(dataDir, "shared_prefs/WebViewChromiumPrefs.xml"), "webview")
    }

    private fun exportTo(passphrase: String?): File {
        val target = File(context.cacheDir, "backup.zip")
        runBlocking { manager.export({ target.outputStream() }, passphrase) { _, _ -> } }
        return target
    }

    private fun staged(path: String) = File(PendingDatabaseRestore.stagingDir(dataDir), path).takeIf { it.exists() }?.readText()

    @Test
    fun `an exported backup stages back with the data it should carry`() = runBlocking<Unit> {
        givenAppData()
        val backup = exportTo(passphrase = null)

        manager.isEncrypted(Uri.fromFile(backup)).shouldBeFalse()
        val result = manager.stage(Uri.fromFile(backup), passphrase = null) { _, _ -> }

        result.userIds shouldBeEqualTo listOf(USER_ID)
        result.createdAt shouldBeEqualTo 1_700_000_000_000
        staged("databases/matrix-sdk-auth.db") shouldBeEqualTo "auth"
        staged("files/$sessionId/session_store.db") shouldBeEqualTo "session"
        staged("files/$sessionId/session_store.db-wal") shouldBeEqualTo "session wal"
        staged("files/$sessionId/session_store.db-shm") shouldBeEqualTo null
        staged("files/$sessionId/tw/a.json") shouldBeEqualTo "translation"
        staged("files/preserved_media/a.jpg") shouldBeEqualTo "media"
        staged("files/mbgl-offline.db") shouldBeEqualTo null
        staged(PendingDatabaseRestore.DEFAULT_PREFERENCES_PLACEHOLDER) shouldBeEqualTo "settings"
        staged("shared_prefs/WebViewChromiumPrefs.xml") shouldBeEqualTo null
    }

    @Test
    fun `an encrypted backup needs its passphrase`() = runBlocking<Unit> {
        givenAppData()
        val backup = exportTo(passphrase = "secret")

        manager.isEncrypted(Uri.fromFile(backup)).shouldBeTrue()
        val wrong = runCatching { manager.stage(Uri.fromFile(backup), "nope") { _, _ -> } }.exceptionOrNull()
        (wrong is WrongPassphraseException).shouldBeTrue()

        manager.stage(Uri.fromFile(backup), "secret") { _, _ -> }
        staged("databases/matrix-sdk-auth.db") shouldBeEqualTo "auth"
    }

    @Test
    fun `an entry escaping the staging folder rejects the whole backup`() = runBlocking<Unit> {
        val backup = File(context.cacheDir, "evil.zip")
        ZipOutputStream(backup.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"format":"im.voyage.backup","version":1,"accounts":[]}""".toByteArray())
            zip.putNextEntry(ZipEntry("files/../../evil.txt"))
            zip.write("evil".toByteArray())
        }

        val failure = runCatching { manager.stage(Uri.fromFile(backup), null) { _, _ -> } }.exceptionOrNull()

        (failure is InvalidBackupException).shouldBeTrue()
        File(dataDir, "evil.txt").exists().shouldBeFalse()
        PendingDatabaseRestore.stagingDir(dataDir).exists().shouldBeFalse()
    }
}
