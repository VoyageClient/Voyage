/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Base64OutputStream
import android.webkit.MimeTypeMap
import kotlinx.coroutines.ensureActive
import org.matrix.android.sdk.api.util.MatrixJsonParser
import timber.log.Timber
import java.io.File
import java.io.FilterOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ExportBlocks {
    data class Block(val events: List<ExportEvent>, val groupId: String? = null)

    fun build(events: List<ExportEvent>, timeZoneId: String = TimeZone.getDefault().id): List<Block> {
        val result = mutableListOf<Block>()
        var run = mutableListOf<ExportEvent>()
        fun flush() {
            if (run.isNotEmpty()) result.add(Block(run.toList(), if (run.size > 1 && run.first().group != null) run.first().id else null))
            run = mutableListOf()
        }
        events.forEach { event ->
            val previous = run.lastOrNull()
            if (previous != null && (event.group != previous.group || day(event.timestamp, timeZoneId) != day(previous.timestamp, timeZoneId))) flush()
            run.add(event)
        }
        flush()
        return result
    }

    fun day(timestamp: Long, zone: String = TimeZone.getDefault().id): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(timestamp))
}

class TimelineExportHtml {
    private val json = MatrixJsonParser.getMoshi().adapter(Any::class.java).indent("  ")

    fun write(snapshot: TimelineExportSnapshot, directory: File) {
        File(directory, "export.json").writeText(json.toJson(org.matrix.android.sdk.api.session.room.model.message.coerceGalleryJsonNumbers(mapOf(
                "room_name" to snapshot.roomName, "room_creator" to snapshot.creator,
                "topic" to snapshot.topic, "export_date" to ExportBlocks.day(snapshot.exportedAt, snapshot.options.timeZoneId),
                "exported_by" to snapshot.exportedBy, "messages" to snapshot.events.map { it.raw },
        ))))
        val supplemental = mapOf(
                "emoji_images" to snapshot.emojiImages,
                "version" to 2, "snapshot" to json.fromJson(MatrixJsonParser.getMoshi().adapter(TimelineExportSnapshot::class.java).toJson(snapshot.forArchive())), "exported_at" to snapshot.exportedAt,
                "events" to snapshot.events.map { event -> mapOf(
                        "event_id" to event.id, "reactions" to event.reactions.map { reaction -> mapOf("key" to reaction.key, "count" to reaction.count, "images" to reaction.images,
                                "users" to reaction.people.map { mapOf("id" to it.id, "name" to it.name) }) },
                        "receipts" to event.receipts.map { mapOf("user_id" to it.person.id, "name" to it.person.name, "timestamp" to it.timestamp) },
                        "history" to event.history.map { it.raw }, "history_incomplete" to event.historyIncomplete,
                        "thread_count" to event.threadCount, "reply_missing" to event.replyMissing,
                ) },
        )
        File(directory, "voyage.json").writeText(json.toJson(org.matrix.android.sdk.api.session.room.model.message.coerceGalleryJsonNumbers(supplemental)))
    }

    companion object {
        internal const val snapshotTag = "<script id=\"voyage-archive\" type=\"application/vnd.voyage.archive+json\">"
        internal const val embeddedAssetsTag = "<script id=\"voyage-assets\" type=\"application/vnd.voyage.assets+json\">"
        internal const val embeddedImagesTag = "<style id=\"voyage-images\">"

        /** A JSON asset entry whose data is the Nth url() of the image stylesheet rather than a data URI of its own. */
        internal const val STYLESHEET_REFERENCE = "css:"
        private val STYLESHEET_URL = Regex("url\\(\"([^\"]+)\"\\)")

        /**
         * Files the image stylesheet references are inlined there, so images and fonts show in viewers that run no
         * JavaScript, such as Android's HTML Viewer; the rest are only reachable through the script's asset map.
         */
        suspend fun singleFile(directory: File, destination: File, entries: List<String>, assets: List<ExportAsset>, progress: (Int, Int) -> Unit = { _, _ -> }) {
            val types = assets.associate { it.path to it.mime }
            val names = entries.filter { it != "messages.html" }.distinct()
            val included = names.toSet()
            var written = 0
            destination.outputStream().buffered().use { output ->
                fun writeText(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
                suspend fun writeDataUri(path: String) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val file = File(directory, path)
                    require(file.isFile)
                    val mime = types[path]?.takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) }
                            ?: when (file.extension) { "ttf" -> "font/ttf"; "txt" -> "text/plain"; else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) }
                            ?: BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeFile(file.path, this) }.outMimeType
                            ?: "application/octet-stream"
                    writeText("\"data:$mime;base64,")
                    val unclosed = object : FilterOutputStream(output) {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                        override fun close() = flush()
                    }
                    Base64OutputStream(unclosed, Base64.NO_WRAP).use { base64 -> file.inputStream().use { it.copyTo(base64) } }
                    output.write('"'.code)
                    progress(++written, names.size)
                }
                val html = File(directory, "messages.html").readText().replace("font-src 'self'", "font-src 'self' data:").replace("media-src 'self'", "media-src 'self' data:")
                val body = html.substringBeforeLast("</body>")
                val inlined = linkedMapOf<String, Int>()
                val styleStart = body.indexOf(embeddedImagesTag).takeIf { it >= 0 }?.let { it + embeddedImagesTag.length }
                val styleEnd = styleStart?.let { body.indexOf("</style>", it) }?.takeIf { it >= 0 }
                if (styleStart == null || styleEnd == null) writeText(body) else {
                    writeText(body.substring(0, styleStart))
                    var copied: Int = styleStart
                    STYLESHEET_URL.findAll(body.substring(styleStart, styleEnd)).forEach { match ->
                        val path = match.groupValues[1]
                        if (path !in included || path in inlined) return@forEach
                        writeText(body.substring(copied, styleStart + match.range.first) + "url(")
                        writeDataUri(path)
                        writeText(")")
                        inlined[path] = inlined.size
                        copied = styleStart + match.range.last + 1
                    }
                    writeText(body.substring(copied))
                }
                writeText("\n$embeddedAssetsTag\n{")
                val json = MatrixJsonParser.getMoshi().adapter(String::class.java)
                names.forEachIndexed { index, path ->
                    writeText((if (index > 0) "," else "") + json.toJson(path) + ":")
                    inlined[path]?.let { writeText(json.toJson(STYLESHEET_REFERENCE + it)) } ?: writeDataUri(path)
                }
                writeText("}\n</script>\n<script>$EMBEDDED_SCRIPT</script></body></html>")
                Timber.i("ExportTrace single HTML complete assets=%d inlined=%d", names.size, inlined.size)
            }
        }

        private const val EMBEDDED_SCRIPT = """
            (function(){var assets=JSON.parse(document.getElementById('voyage-assets').textContent),images=document.getElementById('voyage-images'),inline=[];if(images)images.textContent.replace(/url\("(data:[^"]+)"\)/g,function(all,url){inline.push(url);return all;});for(var key in assets){var ref=/^css:(\d+)$/.exec(assets[key]);if(ref)assets[key]=inline[+ref[1]];}window.voyageAssets=assets;function css(value){return value.replace(/url\((['"]?)([^)'"\s]+)\1\)/g,function(all,quote,path){return assets[path]?"url('"+assets[path]+"')":all;});}var styles=document.querySelectorAll('style');for(var i=0;i<styles.length;i++)if(styles[i]!==images)styles[i].textContent=css(styles[i].textContent);var nodes=document.querySelectorAll('[src],[href],[style]');for(var i=0;i<nodes.length;i++){var node=nodes[i];for(var j=0;j<2;j++){var attr=j?'href':'src',path=node.getAttribute(attr);if(assets[path])node.setAttribute(attr,assets[path]);}var value=node.getAttribute('style');if(value)node.setAttribute('style',css(value));}resizeLayouts();})();
        """

        fun archiveEntries(snapshot: TimelineExportSnapshot, rendered: List<String>): List<String> = buildList {
            if (snapshot.options.format == "JSON") addAll(listOf("export.json", "voyage.json")) else addAll(rendered)
            addAll(snapshot.assets.map { it.path })
        }.distinct()

        fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
        fun color(value: String, fallback: String): String = value.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) } ?: fallback
        fun cssColor(value: Int): String = "#%06x".format(value and 0xffffff)
    }
}
