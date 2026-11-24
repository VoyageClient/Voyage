/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import android.os.SystemClock
import androidx.core.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.profile.IdentityAliases
import org.matrix.android.sdk.api.util.MatrixJsonParser
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimelineArchiveStore @Inject constructor(@ApplicationContext private val context: Context, private val importer: TimelineArchiveImporter) {
    data class Entry(val id: String, val name: String, val events: Int, val importedAt: Long, val avatar: String? = null, val color: Int? = null)
    private val adapter = MatrixJsonParser.getMoshi().adapter(TimelineExportSnapshot::class.java)
    private fun root(owner: String) = File(context.filesDir, "timeline_archives/${IdentityAliases.digest(owner)}").also { it.mkdirs() }
    fun directory(owner: String, id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(root(owner), id)
    }
    fun list(owner: String): List<Entry> = root(owner).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith("pending-") }.mapNotNull { dir ->
        runCatching {
            val index = File(dir, "index.json")
            val cached = if (index.isFile) runCatching cachedIndex@{
                val value = AtomicFile(index).openRead().bufferedReader().use {
                    MatrixJsonParser.getMoshi().adapter(Any::class.java).fromJson(it.readText()) as Map<*, *>
                }
                if (value["version"] != 2.0) return@cachedIndex null
                Entry(dir.name, value["name"] as String, (value["events"] as Number).toInt(), dir.lastModified(), value["avatar"] as? String, (value["color"] as? Number)?.toInt())
            }.onFailure { Timber.w(it, "ExportTrace archive index invalid; reading snapshot") }.getOrNull() else null
            cached ?: read(owner, dir.name).let { snapshot ->
                writeIndex(dir, snapshot)
                Entry(dir.name, snapshot.roomName, snapshot.events.size, dir.lastModified(), snapshot.roomPerson?.avatar, snapshot.roomPerson?.color)
            }
        }.onFailure { Timber.w(it, "ExportTrace archive index read failed") }.getOrNull()
    }.sortedByDescending { it.importedAt }

    // Search pages, long-presses and profiles each read the archive they belong to; one parse serves them all.
    private var cached: Pair<List<Any>, TimelineExportSnapshot>? = null

    fun read(owner: String, id: String): TimelineExportSnapshot {
        val file = File(directory(owner, id), "snapshot.json")
        val key = listOf(owner, id, file.lastModified(), file.length())
        synchronized(this) { cached?.takeIf { it.first == key }?.let { return it.second } }
        val snapshot = AtomicFile(file).openRead().bufferedReader().use { adapter.fromJson(it.readText()) } ?: error("Archive metadata is missing")
        synchronized(this) { cached = key to snapshot }
        return snapshot
    }

    fun delete(owner: String, id: String) {
        synchronized(this) { if (cached?.first?.take(2) == listOf(owner, id)) cached = null }
        check(directory(owner, id).deleteRecursively())
    }

    private fun writeIndex(directory: File, snapshot: TimelineExportSnapshot) {
        val index = AtomicFile(File(directory, "index.json"))
        val stream = index.startWrite()
        try {
            val value = mapOf("version" to 2, "name" to snapshot.roomName, "events" to snapshot.events.size,
                    "avatar" to snapshot.roomPerson?.avatar, "color" to snapshot.roomPerson?.color)
            stream.write(MatrixJsonParser.getMoshi().adapter(Any::class.java).toJson(value).toByteArray(Charsets.UTF_8))
            index.finishWrite(stream)
        } catch (failure: Throwable) { index.failWrite(stream); throw failure }
    }

    suspend fun importArchive(owner: String, input: InputStream, name: String, progress: (Int) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val pending = File(root(owner), "pending-$id").also { check(it.mkdirs()) }
        val source = File(pending, "source").also { it.mkdirs() }
        val started = SystemClock.elapsedRealtime()
        try {
            input.buffered().use { stream ->
                stream.mark(4)
                val magic = ByteArray(4); val count = stream.read(magic); stream.reset()
                if (count >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte()) {
                    ZipInputStream(stream).use { zip ->
                        var total = 0L
                        var entries = 0
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = zip.nextEntry ?: break
                            require(++entries <= 20_000) { context.getString(CommonStrings.archive_error_file_limit) }
                            require(!entry.name.contains('\\') && !entry.name.startsWith('/') && !entry.name.contains('\u0000')) { context.getString(CommonStrings.archive_error_path) }
                            val file = File(source, entry.name).canonicalFile
                            require(file.path.startsWith(source.canonicalPath + File.separator)) { context.getString(CommonStrings.archive_error_path) }
                            if (entry.isDirectory) { check(file.mkdirs() || file.isDirectory); continue }
                            require(!file.exists()) { context.getString(CommonStrings.archive_error_duplicate_files) }
                            file.parentFile?.mkdirs()
                            file.outputStream().use { output ->
                                var size = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = zip.read(buffer); if (n < 0) break
                                    total += n; size += n
                                    require(total <= 2L * 1024 * 1024 * 1024 && size <= 1024L * 1024 * 1024) { context.getString(CommonStrings.archive_error_size_limit) }
                                    output.write(buffer, 0, n)
                                }
                            }
                            if (entries % 32 == 0) { Timber.d("ExportTrace archive extracted files=%d bytes=%d", entries, total); progress(5) }
                        }
                        Timber.i("ExportTrace archive extracted files=%d bytes=%d", entries, total)
                    }
                } else {
                    stream.mark(512)
                    val prefix = ByteArray(512); val prefixSize = stream.read(prefix); stream.reset()
                    val html = prefixSize > 0 && String(prefix, 0, prefixSize, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("<")
                    val extension = if (html) "html" else name.substringAfterLast('.', "json").lowercase().takeIf { it in setOf("html", "htm", "json") } ?: "json"
                    File(source, "import.$extension").outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        val isJson = extension == "json"
                        val limit = if (isJson) 32L * 1024 * 1024 else 2L * 1024 * 1024 * 1024
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val countRead = stream.read(buffer)
                            if (countRead < 0) break
                            total += countRead
                            require(total <= limit) {
                                context.getString(if (isJson) CommonStrings.archive_error_metadata_size else CommonStrings.archive_error_size_limit)
                            }
                            output.write(buffer, 0, countRead)
                        }
                    }
                }
            }
            progress(10)
            val snapshot = importer.parse(source, owner) { progress(10 + it * 85 / 100) }
            val assets = File(source, "assets")
            val retained = snapshot.assets.map { File(source, it.path).canonicalPath }.toSet()
            if (assets.exists()) assets.walkTopDown().filter { it.isFile && it.canonicalPath !in retained }.forEach { check(it.delete()) }
            if (assets.exists()) check(assets.renameTo(File(pending, "assets")))
            check(source.deleteRecursively())
            val atomic = AtomicFile(File(pending, "snapshot.json"))
            val stream = atomic.startWrite()
            try { stream.write(adapter.toJson(snapshot).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
            writeIndex(pending, snapshot)
            check(pending.renameTo(directory(owner, id)))
            Timber.i("ExportTrace archive imported events=%d assets=%d elapsedMs=%d", snapshot.events.size, snapshot.assets.size, SystemClock.elapsedRealtime() - started)
            progress(100)
            id
        } catch (failure: Throwable) {
            pending.deleteRecursively()
            Timber.e(failure, "ExportTrace archive import failed elapsedMs=%d", SystemClock.elapsedRealtime() - started)
            throw failure
        }
    }
}
