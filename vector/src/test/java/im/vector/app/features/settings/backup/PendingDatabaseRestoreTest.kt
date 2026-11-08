/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val PACKAGE = "im.voyage.app"
private const val OLD_SESSION = "0123456789abcdef0123456789abcdef"
private const val NEW_SESSION = "fedcba9876543210fedcba9876543210"

class PendingDatabaseRestoreTest {

    @get:Rule val temp = TemporaryFolder()

    private val dataDir by lazy { temp.root }

    private fun file(path: String, text: String = path) = File(dataDir, path).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun staged(path: String, text: String = "new $path") = file("${PendingDatabaseRestore.STAGING_DIR}/$path", text)

    private fun read(path: String) = File(dataDir, path).takeIf { it.exists() }?.readText()

    private fun givenCurrentData() {
        file("databases/matrix-sdk-auth.db", "old auth")
        file("databases/matrix-sdk-auth.db-wal", "old wal")
        file("databases/push_requests.db", "push history")
        file("no_backup/androidx.work.workdb", "old work")
        file("files/$OLD_SESSION/session_store.db", "old session")
        file("files/preserved_media/a.jpg", "old media")
        file("files/mbgl-offline.db", "map tiles")
        file("shared_prefs/${PACKAGE}_preferences.xml", "old settings")
        file("shared_prefs/WebViewChromiumPrefs.xml", "webview")
        file("cache/thumb.png", "cached")
    }

    private fun givenStagedBackup() {
        staged("databases/matrix-sdk-auth.db")
        staged("files/$NEW_SESSION/session_store.db")
        staged("files/$NEW_SESSION/crypto_store.db")
        staged(PendingDatabaseRestore.DEFAULT_PREFERENCES_PLACEHOLDER, "new settings")
    }

    @Test
    fun `a ready backup replaces the data it carries and leaves the rest`() {
        givenCurrentData()
        givenStagedBackup()
        PendingDatabaseRestore.markReady(dataDir)

        PendingDatabaseRestore.apply(dataDir, PACKAGE).shouldBeTrue()

        read("databases/matrix-sdk-auth.db") shouldBeEqualTo "new databases/matrix-sdk-auth.db"
        read("databases/matrix-sdk-auth.db-wal") shouldBeEqualTo null
        read("files/$NEW_SESSION/crypto_store.db") shouldBeEqualTo "new files/$NEW_SESSION/crypto_store.db"
        File(dataDir, "files/$OLD_SESSION").exists().shouldBeFalse()
        read("files/preserved_media/a.jpg") shouldBeEqualTo null
        read("shared_prefs/${PACKAGE}_preferences.xml") shouldBeEqualTo "new settings"
        read("no_backup/androidx.work.workdb") shouldBeEqualTo null
        read("cache/thumb.png") shouldBeEqualTo null

        read("databases/push_requests.db") shouldBeEqualTo "push history"
        read("files/mbgl-offline.db") shouldBeEqualTo "map tiles"
        read("shared_prefs/WebViewChromiumPrefs.xml") shouldBeEqualTo "webview"
        PendingDatabaseRestore.stagingDir(dataDir).exists().shouldBeFalse()
    }

    @Test
    fun `an interrupted move finishes without deleting what was already moved`() {
        givenCurrentData()
        givenStagedBackup()
        PendingDatabaseRestore.markReady(dataDir)
        PendingDatabaseRestore.apply(dataDir, PACKAGE)

        // As if the process died after the deletion and one move: the marker says "moving" and one file is left.
        file("${PendingDatabaseRestore.STAGING_DIR}/.moving", "")
        staged("files/$NEW_SESSION/event_index.db")

        PendingDatabaseRestore.apply(dataDir, PACKAGE).shouldBeTrue()

        read("files/$NEW_SESSION/event_index.db") shouldBeEqualTo "new files/$NEW_SESSION/event_index.db"
        read("files/$NEW_SESSION/crypto_store.db") shouldBeEqualTo "new files/$NEW_SESSION/crypto_store.db"
        read("databases/matrix-sdk-auth.db") shouldBeEqualTo "new databases/matrix-sdk-auth.db"
    }

    @Test
    fun `an abandoned import is cleaned up without touching anything`() {
        givenCurrentData()
        givenStagedBackup()

        PendingDatabaseRestore.apply(dataDir, PACKAGE).shouldBeFalse()

        read("databases/matrix-sdk-auth.db") shouldBeEqualTo "old auth"
        read("shared_prefs/${PACKAGE}_preferences.xml") shouldBeEqualTo "old settings"
        PendingDatabaseRestore.stagingDir(dataDir).exists().shouldBeFalse()
    }
}
