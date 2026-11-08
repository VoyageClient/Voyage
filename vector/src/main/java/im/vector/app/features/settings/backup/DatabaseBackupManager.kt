/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.dispatchers.CoroutineDispatchers
import im.vector.app.core.extensions.useCompat
import im.vector.lib.core.utils.timer.Clock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.data.sessionId
import org.matrix.android.sdk.api.database.DatabaseFileLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class InvalidBackupException(cause: Throwable? = null) : IOException("Not a database backup", cause)

class BackupTooLargeException : IOException("Backup too large for a zip on this Android version")

data class StagedBackup(val createdAt: Long, val userIds: List<String>)

/**
 * Full database backups: every signed-in account's databases (logins and encryption keys included), the search
 * index, preserved redactions and the app's settings files, as a zip that can be passphrase-encrypted.
 */
class DatabaseBackupManager @Inject constructor(
        @ApplicationContext private val context: Context,
        private val authenticationService: AuthenticationService,
        private val coroutineDispatchers: CoroutineDispatchers,
        private val clock: Clock,
) {

    private val dataDir = File(context.applicationInfo.dataDir)

    private class Item(val path: String, val file: File, val isDatabase: Boolean) {
        val size: Long get() = if (isDatabase) file.length() + wal.length() else file.length()
        val wal: File get() = File(file.path + "-wal")
    }

    suspend fun export(open: () -> OutputStream, passphrase: String?, onProgress: (done: Long, total: Long) -> Unit) = withContext(coroutineDispatchers.io) {
        val accounts = authenticationService.getAllSessionParams().map { BackupAccount(it.userId, it.credentials.sessionId()) }
        val items = collectItems(accounts)
        val total = items.sumOf { it.size }
        // Android's zip writer only learned Zip64 at API 24.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N && total >= ZIP32_LIMIT) throw BackupTooLargeException()

        val output = open()
        val sink = if (passphrase.isNullOrEmpty()) output else BackupEncryption.encrypt(output, passphrase)
        ZipOutputStream(BufferedOutputStream(sink, BUFFER_SIZE)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest(accounts).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            var done = 0L
            val job = coroutineContext
            val buffer = ByteArray(BUFFER_SIZE)
            fun copy(path: String, file: File) {
                if (!file.exists()) return
                zip.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
                file.inputStream().use { input ->
                    while (true) {
                        job.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
                zip.closeEntry()
            }
            items.forEach { item ->
                if (item.isDatabase) {
                    DatabaseFileLock.withWritesBlocked(item.file) {
                        copy(item.path, item.file)
                        if (item.wal.length() > 0) copy(item.path + "-wal", item.wal)
                    }
                } else {
                    copy(item.path, item.file)
                }
            }
        }
    }

    private fun collectItems(accounts: List<BackupAccount>): List<Item> {
        val items = mutableListOf<Item>()
        fun addTree(path: String, file: File) {
            when {
                file.isDirectory -> file.listFiles().orEmpty().sortedBy { it.name }.forEach { addTree("$path/${it.name}", it) }
                file.isFile -> items += Item(path, file, isDatabase = false)
            }
        }

        AUTH_AND_GLOBAL_DATABASES.forEach { name ->
            items += Item("databases/$name", context.getDatabasePath(name), isDatabase = true)
        }
        accounts.map { it.sessionId }.distinct().forEach { sessionId ->
            val sessionDir = File(context.filesDir, sessionId)
            // The sync cache before the encryption store, so a restore re-syncs over anything the keys already saw.
            SESSION_DATABASES.forEach { name ->
                items += Item("files/$sessionId/$name", File(sessionDir, name), isDatabase = true)
            }
            sessionDir.listFiles().orEmpty()
                    .filter { file -> SESSION_DATABASES.none { file.name.startsWith(it) } }
                    .sortedBy { it.name }
                    .forEach { addTree("files/$sessionId/${it.name}", it) }
        }
        context.filesDir.listFiles().orEmpty()
                .filter { it.name in FILES_TO_BACK_UP || it.name.startsWith("image_pack_cache_") }
                .sortedBy { it.name }
                .forEach { addTree("files/${it.name}", it) }

        val defaultPreferences = "${context.packageName}_preferences.xml"
        File(dataDir, "shared_prefs").listFiles().orEmpty()
                .filter { it.name.endsWith(".xml") && it.name !in PendingDatabaseRestore.DEVICE_PREFERENCE_FILES }
                .sortedBy { it.name }
                .forEach {
                    val path = if (it.name == defaultPreferences) PendingDatabaseRestore.DEFAULT_PREFERENCES_PLACEHOLDER else "shared_prefs/${it.name}"
                    items += Item(path, it, isDatabase = false)
                }
        return items.filter { it.file.exists() }
    }

    private fun manifest(accounts: List<BackupAccount>): String = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("created_at", clock.epochMillis())
            .put("accounts", JSONArray().apply {
                accounts.forEach { put(JSONObject().put("user_id", it.userId).put("session_id", it.sessionId)) }
            })
            .toString(2)

    suspend fun isEncrypted(uri: Uri): Boolean = withContext(coroutineDispatchers.io) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        stream.use { input ->
            val head = ByteArray(BackupEncryption.headerPeekSize)
            var read = 0
            while (read < head.size) {
                val count = input.read(head, read, head.size - read)
                if (count < 0) break
                read += count
            }
            BackupEncryption.hasEncryptedHeader(head.copyOf(read))
        }
    }

    /**
     * Unpacks and fully verifies the backup into the staging folder. Nothing in use is touched until [commitStaged].
     * @throws WrongPassphraseException, [InvalidBackupException] or [CorruptedBackupException].
     */
    suspend fun stage(uri: Uri, passphrase: String?, onProgress: (done: Long, total: Long) -> Unit): StagedBackup = withContext(coroutineDispatchers.io) {
        val staging = PendingDatabaseRestore.stagingDir(dataDir)
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingRoot = staging.canonicalPath + File.separator
        val total = sizeOf(uri)
        try {
            val raw = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
            CountingInputStream(BufferedInputStream(raw, BUFFER_SIZE)) { onProgress(it, total) }.use { counted ->
                val plain = if (passphrase.isNullOrEmpty()) counted else BackupEncryption.decrypt(counted, passphrase)
                val zip = ZipInputStream(plain)
                val first = zip.nextEntry
                if (first?.name != MANIFEST) throw InvalidBackupException()
                val manifest = parseManifest(zip.readBytes().toString(Charsets.UTF_8))

                val job = coroutineContext
                while (true) {
                    job.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val target = File(staging, entry.name)
                    // Zip-slip: never write outside the staging folder.
                    if (!ALLOWED_ROOTS.any { entry.name.startsWith(it) } || !target.canonicalPath.startsWith(stagingRoot)) {
                        throw InvalidBackupException()
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it, BUFFER_SIZE) }
                }
                // ZipInputStream stops at the central directory; the MAC is only checked at the very end.
                val buffer = ByteArray(BUFFER_SIZE)
                while (plain.read(buffer) >= 0) job.ensureActive()
                manifest
            }
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    fun commitStaged() = PendingDatabaseRestore.markReady(dataDir)

    fun discardStaged() {
        PendingDatabaseRestore.stagingDir(dataDir).deleteRecursively()
    }

    private fun parseManifest(text: String): StagedBackup = try {
        val json = JSONObject(text)
        if (json.optString("format") != FORMAT || json.optInt("version") !in 1..VERSION) throw InvalidBackupException()
        val accounts = json.optJSONArray("accounts") ?: JSONArray()
        StagedBackup(
                createdAt = json.optLong("created_at"),
                userIds = (0 until accounts.length()).map { accounts.getJSONObject(it).getString("user_id") },
        )
    } catch (e: JSONException) {
        throw InvalidBackupException(e)
    }

    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.useCompat { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull() ?: 0L

    private class CountingInputStream(input: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) onCount(++count) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
            if (it > 0) {
                count += it
                onCount(count)
            }
        }
    }

    companion object {
        private const val FORMAT = "im.voyage.backup"
        private const val VERSION = 1
        private const val MANIFEST = "manifest.json"
        private const val BUFFER_SIZE = 64 * 1024
        private const val ZIP32_LIMIT = 4_000_000_000L
        private val AUTH_AND_GLOBAL_DATABASES = listOf("matrix-sdk-auth.db", "matrix-sdk-global.db")
        private val SESSION_DATABASES = listOf(
                "session_store.db", "matrix-sdk-identity.db", "redacted_content.db", "event_index.db", "crypto_store.db",
        )
        private val FILES_TO_BACK_UP = setOf("preserved_media", "account_info", "bulk_queue", "datastore", "custom_emoji.ttf", "profile_colors.z")
        private val ALLOWED_ROOTS = listOf("databases/", "files/", "shared_prefs/")
    }
}
