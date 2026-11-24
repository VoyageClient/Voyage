/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import androidx.core.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import org.matrix.android.sdk.api.session.profile.IdentityAliases
import org.matrix.android.sdk.api.util.MatrixJsonParser
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimelineExportStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val root get() = File(context.filesDir, "bulk_queue/exports").also { it.mkdirs() }
    private val draftAdapter = MatrixJsonParser.getMoshi().adapter(TimelineExportDraft::class.java)
    private val snapshotAdapter = MatrixJsonParser.getMoshi().adapter(TimelineExportSnapshot::class.java)
    private val optionsAdapter = MatrixJsonParser.getMoshi().adapter(TimelineExportOptions::class.java)

    fun draftId(owner: String, roomId: String, threadId: String?): String = IdentityAliases.digest("$owner\n$roomId\n${threadId.orEmpty()}")
    fun directory(owner: String, id: String, create: Boolean = true): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(root, IdentityAliases.digest(owner) + "/" + id).also { if (create) it.mkdirs() }
    }

    @Synchronized fun read(owner: String, roomId: String, threadId: String?): TimelineExportDraft? =
            readFile(File(directory(owner, draftId(owner, roomId, threadId), create = false), "draft.json")) { draftAdapter.fromJson(it) }

    @Synchronized fun save(draft: TimelineExportDraft) {
        val file = File(directory(draft.owner, draft.id), "draft.json")
        val current = readFile(file) { draftAdapter.fromJson(it) }
        if (current != null && (current.updatedAt > draft.updatedAt || current == draft)) return
        writeFile(file, draftAdapter.toJson(draft))
    }

    @Synchronized fun discard(owner: String, id: String) { directory(owner, id, create = false).deleteRecursively() }

    fun stageBytes(snapshot: TimelineExportSnapshot, source: File): Long =
            snapshot.assets.map { it.path }.distinct().sumOf { path -> File(source, path).takeIf { it.isFile }?.length() ?: 0L } +
                    snapshotAdapter.toJson(snapshot).toByteArray(Charsets.UTF_8).size

    fun stage(snapshot: TimelineExportSnapshot, source: File, id: String = UUID.randomUUID().toString()): String {
        val destination = directory(snapshot.owner, id)
        val names = snapshot.assets.map { it.path }
        names.distinct().forEach { name ->
            require(name.startsWith("assets/") && !name.contains(".."))
            val file = File(source, name)
            if (file.isFile) file.copyTo(File(destination, name).also { it.parentFile?.mkdirs() }, overwrite = true)
        }
        Timber.d("ExportTrace stage events=%d assets=%d", snapshot.events.size, snapshot.assets.size)
        writeFile(File(destination, "snapshot.json"), snapshotAdapter.toJson(snapshot))
        return id
    }

    fun snapshot(owner: String, id: String): TimelineExportSnapshot =
            readFile(File(directory(owner, id), "snapshot.json")) { snapshotAdapter.fromJson(it) } ?: error("Missing export snapshot")

    fun savePreset(owner: String, name: String, options: TimelineExportOptions) {
        val prefs = context.getSharedPreferences("voyage_export_presets_" + IdentityAliases.digest(owner), Context.MODE_PRIVATE)
        prefs.edit().putString(name, optionsAdapter.toJson(options)).apply()
    }

    fun presets(owner: String): Map<String, TimelineExportOptions> =
            context.getSharedPreferences("voyage_export_presets_" + IdentityAliases.digest(owner), Context.MODE_PRIVATE).all
                    .mapNotNull { (name, value) -> runCatching { optionsAdapter.fromJson(value as String)?.let { name to it } }.getOrNull() }.toMap()

    fun deletePreset(owner: String, name: String) {
        context.getSharedPreferences("voyage_export_presets_" + IdentityAliases.digest(owner), Context.MODE_PRIVATE).edit().remove(name).apply()
    }

    private fun <T> readFile(file: File, read: (String) -> T?): T? = runCatching {
        AtomicFile(file).openRead().bufferedReader().use { read(it.readText()) }
    }.getOrNull()

    private fun writeFile(file: File, value: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
