/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.lib.animatedimage.AnimatedFrame
import im.vector.lib.animatedimage.AnimatedWebpEncoder
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

class ExportSizeException(val suggestedHeight: Int) : Exception()

class TimelineExportWorker @Inject constructor(
        @ApplicationContext private val context: Context,
        private val store: TimelineExportStore,
        private val renderer: TimelineExportRenderer,
        private val nativeHtml: NativeTimelineHtmlExporter,
) {
    suspend fun run(session: Session, state: ExportJobState, progress: (Int, Int) -> Unit): List<Uri> = withContext(Dispatchers.Default) {
        val startedAt = SystemClock.elapsedRealtime()
        Timber.d("ExportTrace worker started")
        var reported = -1
        fun report(percent: Int) {
            val next = percent.coerceIn(0, 100)
            if (next <= reported) return
            reported = next
            progress(next, 100)
            if (next % 5 == 0) Timber.d("ExportTrace worker progress percent=%d heapUsed=%d elapsedMs=%d", next,
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), SystemClock.elapsedRealtime() - startedAt)
        }
        report(0)
        val snapshot = store.snapshot(session.myUserId, state.snapshotId)
        require(snapshot.owner == session.myUserId)
        val directory = store.directory(session.myUserId, state.snapshotId)
        val options = snapshot.options
        Timber.i("ExportTrace worker snapshot selected=%d visible=%d format=%s assets=%d", snapshot.selectedCount, snapshot.events.size, options.format, snapshot.assets.size)
        val outputs = mutableListOf<File>()
        report(1)
        if (options.format in setOf("HTML", "JSON")) {
            val rendered = if (options.format == "HTML") nativeHtml.write(context, snapshot, directory) { current, total -> report(1 + current * 79 / total.coerceAtLeast(1)) }
            else { TimelineExportHtml().write(snapshot, directory); emptyList() }
            val single = options.format == "HTML" && options.singleHtml
            val destination = File(directory, state.name + if (single) ".html" else ".zip")
            withContext(Dispatchers.IO) {
                val names = TimelineExportHtml.archiveEntries(snapshot, rendered)
                if (single) TimelineExportHtml.singleFile(directory, destination, names, snapshot.assets) { current, total -> report(80 + current * 10 / total.coerceAtLeast(1)) }
                else ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                    names.distinct().forEachIndexed { index, name ->
                        coroutineContext.ensureActive()
                        report(80 + index * 10 / names.size.coerceAtLeast(1))
                        val file = File(directory, name)
                        if (file.isFile) {
                            zip.putNextEntry(ZipEntry(name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            outputs.add(destination)
        } else {
            val document = renderer.layout(context, snapshot, directory, progress = { current, total -> report(1 + current * 24 / total.coerceAtLeast(1)) })
            try {
                val scale = options.width.toDouble() / document.logicalWidth
                val height = ceil(document.height * scale).toLong()
                if (height > Int.MAX_VALUE || height <= 0) throw ExportSizeException(8000)
                val boundaries = document.boundaries(options.width)
                val parts = ExportSplitPlanner.split(boundaries, height.toInt(), options.maxHeight)
                if (options.format == "WEBP") {
                    val budget = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(64L * 1024 * 1024)
                    val allowedHeight = minOf(16383L, budget / (options.width * 4L)).toInt().coerceAtLeast(1)
                    if (options.width > 16383 || parts.any { it.height > allowedHeight }) throw ExportSizeException(allowedHeight)
                }
                for ((index, part) in parts.withIndex()) {
                    coroutineContext.ensureActive()
                    val suffix = if (parts.size > 1) "_%03d".format(Locale.ROOT, index + 1) else ""
                    val file = File(directory, state.name + suffix + if (options.format == "WEBP") ".webp" else ".png")
                    if (options.format == "WEBP") writeWebp(document, part, options, file) { frame, frames ->
                        report(25 + ((index + frame.toDouble() / frames.coerceAtLeast(1)) * 65 / parts.size).toInt())
                    } else writePng(document, part, options, file) { strip, strips ->
                        report(25 + ((index + strip.toDouble() / strips.coerceAtLeast(1)) * 65 / parts.size).toInt())
                    }
                    outputs.add(file)
                }
            } finally { withContext(NonCancellable + Dispatchers.Main) { document.release() } }
        }
        val uris = mutableListOf<Uri>()
        withContext(Dispatchers.IO) {
            outputs.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                report(90 + index * 9 / outputs.size.coerceAtLeast(1))
                uris.add(publish(file, directory, index))
            }
        }
        report(100)
        Timber.d("ExportTrace worker complete outputs=%d elapsedMs=%d", uris.size, SystemClock.elapsedRealtime() - startedAt)
        uris
    }

    private suspend fun writePng(
            document: TimelineExportRenderer.Document,
            part: ExportSplitPlanner.Part,
            options: TimelineExportOptions,
            file: File,
            progress: (Int, Int) -> Unit,
    ) {
        val stripHeight = minOf(STRIP_HEIGHT, part.height)
        val bitmap = Bitmap.createBitmap(options.width, stripHeight, Bitmap.Config.ARGB_8888)
        val total = (part.height + stripHeight - 1) / stripHeight
        try {
            file.outputStream().buffered().use { output ->
                ExportPngWriter(output, options.width, part.height).use { writer ->
                    var offset = 0
                    while (offset < part.height) {
                        coroutineContext.ensureActive()
                        val count = minOf(stripHeight, part.height - offset)
                        val scale = options.width.toFloat() / document.logicalWidth
                        renderer.prepareAssets(document, top = ((part.top + offset) / scale).toInt(), bottom = ((part.top + offset + count) / scale).toInt() + 1)
                        withContext(Dispatchers.Main) { renderer.draw(document, Canvas(bitmap), part.top + offset, options.width, count) }
                        writer.append(bitmap, count)
                        offset += count
                        progress((offset + stripHeight - 1) / stripHeight, total)
                    }
                }
            }
        } finally { bitmap.recycle() }
    }

    private suspend fun writeWebp(
            document: TimelineExportRenderer.Document,
            part: ExportSplitPlanner.Part,
            options: TimelineExportOptions,
            file: File,
            progress: (Int, Int) -> Unit,
    ) {
        val bitmap = Bitmap.createBitmap(options.width, part.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fps = options.fps.coerceIn(1, 60)
        val frames = options.durationSeconds.coerceIn(1, 60) * fps
        try {
            AnimatedWebpEncoder.StreamWriter(file, options.width, part.height, options.quality).use { writer ->
                for (index in 0 until frames) {
                    coroutineContext.ensureActive()
                    val scale = options.width.toFloat() / document.logicalWidth
                    renderer.prepareAssets(document, index * 1000 / fps, (part.top / scale).toInt(), ((part.top + part.height) / scale).toInt() + 1)
                    // In strips, so a tall frame doesn't hold the main thread for its whole draw.
                    var offset = 0
                    while (offset < part.height) {
                        val count = minOf(STRIP_HEIGHT, part.height - offset)
                        withContext(Dispatchers.Main) {
                            val saved = canvas.save()
                            canvas.clipRect(0, offset, options.width, offset + count)
                            canvas.translate(0f, offset.toFloat())
                            renderer.draw(document, canvas, part.top + offset, options.width, count)
                            canvas.restoreToCount(saved)
                        }
                        offset += count
                    }
                    val delay = (index + 1) * 1000 / fps - index * 1000 / fps
                    check(writer.append(listOf(AnimatedFrame(bitmap, delay))))
                    progress(index + 1, frames)
                }
                check(writer.finish())
            }
        } finally { bitmap.recycle() }
    }

    suspend fun cancel(owner: String, state: ExportJobState) = withContext(Dispatchers.IO) {
        val directory = store.directory(owner, state.snapshotId)
        directory.listFiles().orEmpty().filter { it.name.startsWith("published-") && it.extension == "uri" }.forEach { journal ->
            val value = runCatching { AtomicFile(journal).openRead().bufferedReader().use { it.readText() } }.getOrNull() ?: return@forEach
            if (!value.startsWith("done:")) {
                val uri = Uri.parse(value)
                if (uri.scheme == "content" && Build.VERSION.SDK_INT >= 29) {
                    runCatching { context.contentResolver.delete(uri, MediaStore.MediaColumns.IS_PENDING + " = 1", null) }
                } else uri.path?.let { path ->
                    val file = File(path)
                    File(file.parentFile, ".${file.name}.partial").delete()
                }
            }
        }
        store.discard(owner, state.snapshotId)
    }

    fun outputName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment.orEmpty()
        return runCatching {
            val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return@runCatching ""
            try { if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" } finally { cursor.close() }
        }.onFailure { Timber.w(it, "ExportTrace saved filename unavailable") }.getOrDefault("")
    }

    private fun publish(source: File, directory: File, index: Int): Uri {
        val mime = when (source.extension) { "png" -> "image/png"; "webp" -> "image/webp"; "html" -> "text/html"; else -> "application/zip" }
        val journal = AtomicFile(File(directory, "published-$index.uri"))
        val existing = runCatching { journal.openRead().bufferedReader().use { it.readText() } }.getOrNull()
        if (existing?.startsWith("done:") == true) return Uri.parse(existing.removePrefix("done:"))
        val images = mime.startsWith("image/")
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (images) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = existing?.let(Uri::parse) ?: run {
                var name = source.name
                var suffix = 0
                while (context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                                MediaStore.MediaColumns.DISPLAY_NAME + " = ?", arrayOf(name), null)?.use { it.moveToFirst() } == true) {
                    suffix++
                    name = source.nameWithoutExtension + " ($suffix)." + source.extension
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (images) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                context.contentResolver.insert(collection, values) ?: error(context.getString(CommonStrings.error_saving_media_file))
            }
            saveJournal(journal, uri.toString())
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error(context.getString(CommonStrings.error_saving_media_file))
            output.use { destination -> source.inputStream().use { it.copyTo(destination) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            saveJournal(journal, "done:$uri")
            return uri
        }
        val target = if (existing != null) File(Uri.parse(existing).path!!) else {
            val folder = Environment.getExternalStoragePublicDirectory(if (images) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS)
            check(folder.isDirectory || folder.mkdirs())
            var destination = File(folder, source.name)
            var suffix = 0
            while (destination.exists()) {
                suffix++
                destination = File(folder, source.nameWithoutExtension + " ($suffix)." + source.extension)
            }
            destination
        }
        val uri = Uri.fromFile(target)
        saveJournal(journal, uri.toString())
        val temporary = File(target.parentFile, ".${target.name}.partial")
        source.inputStream().use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output); output.fd.sync() } }
        check(temporary.renameTo(target))
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        saveJournal(journal, "done:$uri")
        return uri
    }

    private fun saveJournal(file: AtomicFile, value: String) {
        val output = file.startWrite()
        try { output.write(value.toByteArray()); file.finishWrite(output) } catch (failure: Throwable) { file.failWrite(output); throw failure }
    }

    private companion object {
        const val STRIP_HEIGHT = 128
    }
}
