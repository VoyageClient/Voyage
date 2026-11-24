/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import android.util.Base64
import android.webkit.MimeTypeMap
import com.squareup.moshi.JsonReader
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.source
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.MatrixJsonParser
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class TimelineArchiveImporter @Inject constructor(private val colors: MatrixItemColorProvider, private val notices: NoticeEventFormatter, @ApplicationContext private val context: Context) {
    private val json = MatrixJsonParser.getMoshi().adapter(Any::class.java)
    private val snapshots = MatrixJsonParser.getMoshi().adapter(TimelineExportSnapshot::class.java)

    @Suppress("UNCHECKED_CAST")
    suspend fun parse(directory: File, owner: String, progress: (Int) -> Unit = {}): TimelineExportSnapshot {
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        val htmlFiles = files.filter { it.extension.lowercase() in setOf("html", "htm") }.sortedWith(compareBy({ if (it.name.matches(Regex("messages[0-9]*\\.html", RegexOption.IGNORE_CASE))) 0 else 1 }, { it.relativeTo(directory).invariantSeparatorsPath.count { char -> char == '/' } }, { it.nameWithoutExtension.length }, { it.name }))
        val jsonFile = files.firstOrNull { it.name == "export.json" }
                ?: files.firstOrNull { htmlFiles.isEmpty() && it.extension.equals("json", true) && it.name != "voyage.json" }
        val embedded = htmlFiles.firstNotNullOfOrNull { file -> readVoyageSnapshot(file) }
        val supplementalData = files.firstOrNull { it.name == "voyage.json" }?.let { json.fromJson(readMetadata(it)) as? Map<String, Any?> }
        val frozen = embedded?.second ?: supplementalData?.get("snapshot")?.let { snapshots.fromJson(json.toJson(it)) }
        val embeddedAssets = if (embedded == null) emptyMap() else extractEmbeddedAssets(directory, embedded.first)
        val media = Media(directory, files, (jsonFile ?: htmlFiles.firstOrNull())?.parentFile ?: directory, embeddedAssets)
        val result = if (frozen != null) {
            Timber.i("ExportTrace archive parse Voyage events=%d assets=%d", frozen.events.size, frozen.assets.size)
            restore(frozen, media)
        } else if (jsonFile != null) {
            val data = json.fromJson(readMetadata(jsonFile))
            val map = data as? Map<String, Any?> ?: emptyMap()
            val raw = (map["messages"] ?: map["events"] ?: data) as? List<*> ?: error(context.getString(CommonStrings.archive_error_no_events))
            require(raw.size <= 100_000) { context.getString(CommonStrings.archive_error_event_limit) }
            fromEvents(raw.mapNotNull { it as? Map<String, Any?> }, map, media, progress)
        } else {
            require(htmlFiles.isNotEmpty()) { context.getString(CommonStrings.archive_error_no_format) }
            fromHtml(htmlFiles, media, progress)
        }
        require(result.events.isNotEmpty()) { context.getString(CommonStrings.archive_error_no_events) }
        val enriched = if (frozen == null && supplementalData != null) supplemental(result, supplementalData, media) else result
        progress(100)
        return enriched.copy(owner = owner, draftId = UUID.randomUUID().toString(), selectedCount = enriched.events.size,
                assets = media.assets.values.toList(), options = enriched.options.copy(format = "PNG", layoutWidth = enriched.options.layoutWidth.coerceIn(240, 1600),
                        phoneWidth = enriched.options.phoneWidth.coerceIn(240, 600), fontScale = enriched.options.fontScale.coerceIn(.5f, 2f)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun supplemental(snapshot: TimelineExportSnapshot, data: Map<String, Any?>, media: Media): TimelineExportSnapshot {
        val additions = (data["events"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.associateBy { it["event_id"] as? String }.orEmpty()
        fun person(value: Map<*, *>): ExportPerson {
            val id = (value["id"] ?: value["user_id"]) as? String ?: "@unknown:archive.invalid"
            val name = value["name"] as? String ?: id
            return ExportPerson(id, name, colors.exportNameColor(MatrixItem.UserItem(id, name), false, true, "CURRENT", false))
        }
        return snapshot.copy(emojiImages = (data["emoji_images"] as? Map<String, String>).orEmpty().mapNotNull { (key, path) -> media.path(path)?.let { key to it } }.toMap(), events = snapshot.events.map { event ->
            val extra = additions[event.id] ?: return@map event
            event.copy(reactions = (extra["reactions"] as? List<*>)?.mapNotNull { item ->
                val reaction = item as? Map<*, *> ?: return@mapNotNull null
                val key = reaction["key"] as? String ?: return@mapNotNull null
                ExportReaction(key, (reaction["count"] as? Number)?.toInt() ?: 0,
                        (reaction["users"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map(::person).orEmpty(),
                        (reaction["images"] as? Map<String, String>).orEmpty().mapNotNull { (source, path) -> media.path(path)?.let { source to it } }.toMap())
            } ?: event.reactions,
                    receipts = (extra["receipts"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { ExportReceipt(person(it), (it["timestamp"] as? Number)?.toLong() ?: 0) }.orEmpty(),
                    history = (extra["history"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()?.map { revision ->
                        val content = revision["content"] as? Map<String, Any?> ?: emptyMap()
                        val body = content["m.new_content"] as? Map<String, Any?> ?: content
                        ExportRevision((revision["origin_server_ts"] as? Number)?.toLong() ?: 0, body["body"] as? String ?: "", safeHtml(body["formatted_body"] as? String ?: "", media), revision)
                    } ?: event.history,
                    historyIncomplete = extra["history_incomplete"] == true,
                    threadCount = (extra["thread_count"] as? Number)?.toInt() ?: event.threadCount, replyMissing = extra["reply_missing"] == true || event.replyMissing)
        })
    }

    private fun readMetadata(file: File): String {
        require(file.length() <= MAX_METADATA_BYTES) { context.getString(CommonStrings.archive_error_metadata_size) }
        return file.readText()
    }

    private suspend fun readVoyageSnapshot(file: File): Pair<File, TimelineExportSnapshot>? = file.source().buffer().use { source ->
        if (!source.skipThrough(TimelineExportHtml.snapshotTag)) return@use null
        require(source.request(1)) { context.getString(CommonStrings.archive_error_read) }
        val metadata = Buffer()
        require(source.skipThrough("</script>", metadata)) { context.getString(CommonStrings.archive_error_read) }
        snapshots.fromJson(metadata)?.let { file to it }
    }

    private suspend fun BufferedSource.skipThrough(tag: String, copyTo: Buffer? = null): Boolean {
        val marker = tag.encodeUtf8()
        while (request(marker.size.toLong())) {
            currentCoroutineContext().ensureActive()
            request(8192)
            val position = buffer.indexOf(marker)
            val count = if (position >= 0) position else buffer.size - marker.size + 1
            if (copyTo == null) skip(count) else {
                require(copyTo.size + count <= MAX_METADATA_BYTES) { context.getString(CommonStrings.archive_error_metadata_size) }
                copyTo.write(buffer, count)
            }
            if (position >= 0) {
                skip(marker.size.toLong())
                return true
            }
        }
        return false
    }

    private suspend fun extractEmbeddedAssets(directory: File, html: File): Map<String, File> {
        val assets = linkedMapOf<String, File>()
        var total = 0L
        fun limit() = minOf(1024L * 1024 * 1024, 2L * 1024 * 1024 * 1024 - total)
        val inlined = mutableListOf<File>()
        suspend fun readStylesheet(source: BufferedSource) {
            val data = "\"data:".encodeUtf8()
            while (true) {
                currentCoroutineContext().ensureActive()
                val quote = source.indexOf('"'.code.toByte(), 0, 512)
                if (quote < 0 || "</style>" in source.readUtf8(quote)) return
                if (!source.rangeEquals(0, data)) {
                    source.skip(1)
                    val close = source.indexOf('"'.code.toByte(), 0, 1024)
                    if (close < 0) return
                    source.skip(close + 1)
                    continue
                }
                require(inlined.size < 20_000) { context.getString(CommonStrings.archive_error_file_limit) }
                val target = File(directory, "embedded/inline-${inlined.size}.bin").also { it.parentFile?.mkdirs() }
                target.outputStream().buffered().use { output -> total += decodeEmbeddedAsset(source, output, limit()) }
                source.skip(1)
                inlined.add(target)
            }
        }
        suspend fun readAssets(source: BufferedSource) {
            val reference = ("\"" + TimelineExportHtml.STYLESHEET_REFERENCE).encodeUtf8()
            val reader = JsonReader.of(source)
            reader.beginObject()
            while (reader.hasNext()) {
                currentCoroutineContext().ensureActive()
                require(assets.size < 20_000) { context.getString(CommonStrings.archive_error_file_limit) }
                val name = reader.nextName()
                require(name !in assets) { context.getString(CommonStrings.archive_error_duplicate_files) }
                val extension = name.substringAfterLast('.', "bin").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) } ?: "bin"
                val target = File(directory, "embedded/${org.matrix.android.sdk.api.session.profile.IdentityAliases.digest(name)}.$extension")
                target.parentFile?.mkdirs()
                reader.nextSource().use { value ->
                    if (value.rangeEquals(0, reference)) {
                        val index = value.readUtf8().trim('"').removePrefix(TimelineExportHtml.STYLESHEET_REFERENCE).toIntOrNull()
                        val inline = index?.let(inlined::getOrNull)?.takeIf { it.isFile }
                        requireNotNull(inline) { context.getString(CommonStrings.archive_error_read) }
                        if (!inline.renameTo(target)) inline.copyTo(target, overwrite = true)
                    } else target.outputStream().buffered().use { output -> total += decodeEmbeddedAsset(value, output, limit()) }
                }
                assets[name] = target
            }
            reader.endObject()
        }
        // The stylesheet precedes the asset map, so a current export is read in one pass.
        val found = html.source().buffer().use { source ->
            if (!source.skipThrough(TimelineExportHtml.embeddedImagesTag)) return@use false
            readStylesheet(source)
            if (!source.skipThrough(TimelineExportHtml.embeddedAssetsTag)) return emptyMap()
            readAssets(source)
            true
        }
        if (!found) html.source().buffer().use { source ->
            if (!source.skipThrough(TimelineExportHtml.embeddedAssetsTag)) return emptyMap()
            readAssets(source)
        }
        if (assets.isNotEmpty()) Timber.i("ExportTrace archive embedded assets=%d bytes=%d", assets.size, total)
        return assets
    }

    private suspend fun decodeEmbeddedAsset(value: BufferedSource, output: OutputStream, limit: Long): Long {
        val comma = value.indexOf(','.code.toByte(), 0, 256)
        val header = if (comma >= 0) value.readUtf8(comma) else ""
        require(header.startsWith("\"data:") && header.endsWith(";base64")) { context.getString(CommonStrings.archive_error_read) }
        value.skip(1)
        var written = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val quote = value.indexOf('"'.code.toByte(), 0, 8193)
            val count = if (quote >= 0) quote else 8192L
            require(count % 4 == 0L && value.request(count)) { context.getString(CommonStrings.archive_error_read) }
            val bytes = Base64.decode(value.readByteArray(count), Base64.NO_WRAP)
            written += bytes.size
            require(written <= limit) { context.getString(CommonStrings.archive_error_size_limit) }
            output.write(bytes)
            if (quote >= 0) return written
        }
    }

    private companion object {
        const val MAX_METADATA_BYTES = 32L * 1024 * 1024
        val PARSED_EXTENSIONS = setOf("html", "htm", "json")
    }

    private class Media(val directory: File, files: List<File>, private val relativeRoot: File, private val embedded: Map<String, File> = emptyMap()) {
        // filesDir is reached through a symlink on many devices (/data/user/0), so keys come from canonical paths.
        private val canonicalDirectory = directory.canonicalFile
        private val originals = files.associateBy { it.canonicalFile.relativeTo(canonicalDirectory).invariantSeparatorsPath }
        private val resolved = mutableMapOf<String, String?>()
        val assets = linkedMapOf<String, ExportAsset>()
        fun path(value: String?, base: File? = null): String? {
            if (value.isNullOrBlank()) return null
            if (value in assets) return value
            val decoded = runCatching { URLDecoder.decode(value.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            if (decoded.contains('\\') || decoded.startsWith('/') || decoded.contains(':')) return null
            val name = (embedded[decoded] ?: if (base != null) File(base.parentFile, decoded) else if (decoded in originals) File(directory, decoded) else File(relativeRoot, decoded)).canonicalFile
            if (!name.path.startsWith(canonicalDirectory.path + File.separator)) return null
            val key = name.relativeTo(canonicalDirectory).invariantSeparatorsPath
            return resolved.getOrPut(key) {
                val source = embedded[decoded] ?: originals[key] ?: return@getOrPut null
                val extension = source.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "bin"
                val path = "assets/import-${UUID.randomUUID()}.$extension"
                val target = File(directory, path).also { it.parentFile?.mkdirs() }
                // The extracted tree is deleted after import, so moving avoids holding every file twice; documents may still be parsed.
                if (extension in PARSED_EXTENSIONS || !source.renameTo(target)) source.copyTo(target)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                assets[path] = ExportAsset(path, mime, AnimatedImageFormat.detect(target) != null)
                path
            }
        }
        fun setMime(path: String?, mime: String?) {
            if (path == null || mime.isNullOrBlank()) return
            assets[path]?.let { assets[path] = it.copy(mime = mime) }
        }
        fun forEvent(raw: Map<String, Any?>, content: Map<String, Any?>): String? {
            path(content["url"] as? String)?.let { return it }
            val id = (raw["event_id"] as? String).orEmpty().replace(Regex("[^a-zA-Z0-9]"), "")
            if (id.isEmpty()) return null
            val folder = when (content["msgtype"]) { "m.image" -> "images"; "m.video" -> "videos"; "m.audio" -> "audio"; else -> if (raw["type"] == "m.sticker") "stickers" else "files" }
            val found = originals.keys.firstOrNull { it.substringAfterLast('/').substringBeforeLast('.') == id && it.substringBeforeLast('/').substringAfterLast('/') == folder }
            return path(found)
        }
    }

    private fun safeHtml(source: String, media: Media, base: File? = null): String {
        val doc = Jsoup.parseBodyFragment(source)
        doc.select("mx-reply").remove()
        doc.select("img").forEach { image ->
            val path = media.path(image.attr("src"), base)?.takeIf { media.assets[it]?.mime?.startsWith("image/") == true }
            if (path == null) image.replaceWith(TextNode(image.attr("alt"))) else image.attr("src", path)
        }
        return Jsoup.clean(doc.body().html(), "", ExportPrivacy.safelist(), Document.OutputSettings().prettyPrint(false))
    }

    private fun restore(snapshot: TimelineExportSnapshot, media: Media): TimelineExportSnapshot {
        require(snapshot.events.size <= 100_000) { context.getString(CommonStrings.archive_error_event_limit) }
        var missingAssets = 0
        snapshot.assets.forEach { asset ->
            val path = media.path(asset.path)
            if (path == null) missingAssets++ else media.setMime(path, asset.mime)
        }
        val issues = if (missingAssets == 0) snapshot.issues else snapshot.issues + context.getString(CommonStrings.archive_missing_media)
        if (missingAssets > 0) Timber.w("ExportTrace archive missing declared assets=%d", missingAssets)
        fun person(p: ExportPerson) = p.copy(avatar = media.path(p.avatar))
        fun raw(value: Any?, key: String? = null): Any? = when (value) {
            is Map<*, *> -> value.entries.filter { it.key is String }.associate { entry ->
                val entryKey = entry.key.toString()
                entryKey to if (entryKey == "formatted_body" && entry.value is String) safeHtml(entry.value as String, media) else raw(entry.value, entryKey)
            }
            is List<*> -> value.map { raw(it, key) }
            // Message text that merely starts with "assets/" stays; anything else unresolved is dropped so it can't point outside the archive.
            is String -> if (value.startsWith("assets/")) media.path(value) ?: value.takeIf { key == "body" } else value
            else -> value
        }
        @Suppress("UNCHECKED_CAST")
        return snapshot.copy(issues = issues.distinct(), mentionedPeople = snapshot.mentionedPeople.map(::person), topicFormatted = snapshot.topicFormatted?.let { safeHtml(it, media) }, roomPerson = snapshot.roomPerson?.let(::person), emojiImages = snapshot.emojiImages.mapNotNull { (key, value) -> media.path(value)?.let { key to it } }.toMap(), events = snapshot.events.map { event ->
            event.copy(person = person(event.person), html = safeHtml(event.html, media), raw = raw(event.raw) as Map<String, Any?>,
                    attachment = media.path(event.attachment), audioBackdrop = media.path(event.audioBackdrop), mapImage = media.path(event.mapImage),
                    extraAttachments = event.extraAttachments.map { it.copy(path = media.path(it.path)) },
                    linkPreviews = event.linkPreviews.map { it.copy(image = media.path(it.image)) },
                    noticeSpans = event.noticeSpans.map { it.copy(image = media.path(it.image)) },
                    reactions = event.reactions.map { reaction -> reaction.copy(people = reaction.people.map(::person), images = reaction.images.mapNotNull { (key, value) -> media.path(value)?.let { key to it } }.toMap()) },
                    receipts = event.receipts.map { it.copy(person = person(it.person)) },
                    history = event.history.map { it.copy(html = safeHtml(it.html, media), raw = raw(it.raw) as Map<String, Any?>) })
        })
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun fromEvents(raw: List<Map<String, Any?>>, metadata: Map<String, Any?>, media: Media, progress: (Int) -> Unit): TimelineExportSnapshot {
        fun content(event: Map<String, Any?>) = (event["content"] as? Map<String, Any?>).orEmpty()
        fun relation(event: Map<String, Any?>) = (content(event)["m.relates_to"] as? Map<String, Any?>).orEmpty()
        val profiles = raw.filter { it["type"] == "m.room.member" }.associate { it["state_key"].toString() to content(it) }
        val people = mutableMapOf<String, ExportPerson>()
        fun person(id: String): ExportPerson = people.getOrPut(id) {
            val name = profiles[id]?.get("displayname") as? String ?: id
            ExportPerson(id, name, colors.exportNameColor(MatrixItem.UserItem(id, name), false, true, "CURRENT", showProfileColors = false), media.path(profiles[id]?.get("avatar_url") as? String))
        }
        val edits = raw.filter { relation(it)["rel_type"] == "m.replace" }.groupBy { relation(it)["event_id"] as? String }
        val redacted = raw.filter { it["type"] == "m.room.redaction" }.mapNotNull { (it["redacts"] ?: content(it)["redacts"]) as? String }.toSet()
        fun isRedacted(event: Map<String, Any?>): Boolean =
                event["event_id"] in redacted || (event["unsigned"] as? Map<*, *>)?.get("redacted_because") != null
        val reactions = raw.filter {
            it["type"] == "m.reaction" && !isRedacted(it) && relation(it)["key"] is String
        }.groupBy { relation(it)["event_id"] as? String }
        val ids = raw.mapNotNull { it["event_id"] as? String }.toSet()
        val events = mutableListOf<ExportEvent>()
        for ((index, original) in raw.withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (relation(original)["rel_type"] == "m.replace" || original["type"] in setOf("m.reaction", "m.room.redaction")) continue
            val id = original["event_id"] as? String ?: "\$archive-${index}"
            val deleted = isRedacted(original)
            val revisions = if (deleted) emptyList() else edits[id].orEmpty()
                    .filter { it["sender"] == original["sender"] && !isRedacted(it) }
                    .sortedBy { (it["origin_server_ts"] as? Number)?.toLong() ?: 0 }
            val edited = revisions.lastOrNull()?.let { content(it)["m.new_content"] as? Map<String, Any?> }
            val current = if (deleted) emptyMap() else edited ?: content(original)
            val author = person(original["sender"] as? String ?: "@unknown:archive.invalid")
            val type = original["type"] as? String ?: "m.room.message"
            val root = if (deleted) original - setOf("content", "unsigned", "prev_content") +
                    ("unsigned" to mapOf("redacted_because" to mapOf("type" to "m.room.redaction"))) else original
            val timestamp = (original["origin_server_ts"] as? Number)?.toLong() ?: index.toLong()
            val url = if (deleted) null else media.forEvent(original, current)
            val info = current["info"] as? Map<String, Any?>
            media.setMime(url, info?.get("mimetype") as? String)
            val reply = ((current["m.relates_to"] as? Map<*, *>)?.get("m.in_reply_to") as? Map<*, *>)?.get("event_id") as? String
            val notice = type !in setOf("m.room.message", "m.sticker", "org.matrix.msc3381.poll.start", "m.poll.start")
            val formatted = if (notice) runCatching { notices.format(root.filterValues { it != null }.mapValues { it.value!! }.toModel<Event>()!!, author.name, false, profiles.mapValues { it.value["displayname"] as? String ?: it.key }) }.getOrNull() else null
            val exportedNotice = if (notice) exportNoticeText(formatted ?: type, "Image") { media.path(it) } else null
            val html = safeHtml(current["formatted_body"] as? String ?: "", media)
            val normalized = current.toMutableMap().apply {
                if (url != null) { put("url", url); remove("file") }
                if (html.isNotEmpty()) put("formatted_body", html) else remove("formatted_body")
                (info?.get("thumbnail_url") as? String)?.let { thumbnail -> media.path(thumbnail)?.let { put("info", info + ("thumbnail_url" to it)) } }
            }
            val summaries = reactions[id].orEmpty().groupBy { relation(it)["key"] as? String ?: "" }.map { (key, values) ->
                val senders = values.map { person(it["sender"] as? String ?: "@unknown:archive.invalid") }.distinctBy { it.id }
                ExportReaction(key, senders.size, senders)
            }
            val history = if (revisions.isEmpty()) emptyList() else (listOf(original) + revisions.dropLast(1)).map { revision ->
                val value = (content(revision)["m.new_content"] as? Map<String, Any?>) ?: content(revision)
                ExportRevision((revision["origin_server_ts"] as? Number)?.toLong() ?: timestamp, value["body"] as? String ?: "", safeHtml(value["formatted_body"] as? String ?: "", media), revision)
            }
            events.add(ExportEvent(id, type, timestamp, author, exportedNotice?.first ?: ExportPrivacy.withoutReplyFallback(current["body"] as? String ?: ""), html,
                    root + ("content" to normalized), notice = notice, noticeSpans = exportedNotice?.second.orEmpty(), sentByMe = author.id == metadata["exported_by"],
                    group = type.takeIf { it in setOf("m.room.member", "m.room.server_acl") }, replyTo = reply?.takeIf { it in ids }, replyMissing = reply != null && reply !in ids,
                    attachment = url, attachmentMime = info?.get("mimetype") as? String ?: url?.let { media.assets[it]?.mime },
                    attachmentName = current["filename"] as? String ?: current["body"] as? String, history = history, edited = revisions.isNotEmpty(), reactions = summaries))
            if (index % 32 == 0) { progress(index * 100 / raw.size.coerceAtLeast(1)); kotlinx.coroutines.yield() }
        }
        Timber.i("ExportTrace archive parsed JSON input=%d events=%d edits=%d reactions=%d assets=%d", raw.size, events.size, edits.size, reactions.size, media.assets.size)
        return TimelineExportSnapshot("", "", metadata["room_name"] as? String ?: "Archive", metadata["topic"] as? String ?: "", metadata["room_creator"] as? String ?: "",
                metadata["exported_by"] as? String ?: "", System.currentTimeMillis(), events.size, events.sortedBy { it.timestamp }, emptyList(), TimelineExportOptions())
    }

    private suspend fun fromHtml(files: List<File>, media: Media, progress: (Int) -> Unit): TimelineExportSnapshot {
        val raw = mutableListOf<Map<String, Any?>>()
        val profiles = linkedMapOf<String, Map<String, Any?>>()
        var roomName = "Archive"
        val warnings = mutableListOf<String>()
        for ((fileIndex, file) in files.withIndex()) {
            val doc = Jsoup.parse(readMetadata(file))
            if (raw.isEmpty() && doc.select(".mx_EventTile").isNotEmpty()) roomName = doc.selectFirst(".mx_RoomHeader_name, .mx_RoomHeader h1, title")?.text() ?: roomName
            var previousSender = "@unknown:archive.invalid"
            for (tile in doc.select(".mx_EventTile")) {
                require(raw.size < 100_000) { context.getString(CommonStrings.archive_error_event_limit) }
                val id = tile.attr("data-event-id").ifBlank { tile.parent()?.takeIf { it.hasClass("mx_Export_EventWrapper") }?.id().orEmpty() }.ifBlank { "\$archive-${raw.size}" }
                val senderElement = tile.selectFirst(".mx_DisambiguatedProfile_displayName, .mx_SenderProfile, .mx_EventTile_sender")
                val senderName = senderElement?.text()?.ifBlank { null }
                val avatar = tile.selectFirst(".mx_EventTile_avatar img, [data-testid=event-tile-slot-avatar] img")
                val mxid = tile.select("[title]").map { it.attr("title") }.firstOrNull { it.startsWith('@') && it.contains(':') }
                val sender = mxid ?: if (senderName != null) "@html-${org.matrix.android.sdk.api.session.profile.IdentityAliases.digest(senderName).take(20)}:archive.invalid" else previousSender
                previousSender = sender
                if (senderName != null) profiles[sender] = mapOf("event_id" to "\$profile-${profiles.size}", "sender" to sender, "type" to "m.room.member", "state_key" to sender,
                        "origin_server_ts" to 0, "content" to mapOf("membership" to "join", "displayname" to senderName, "avatar_url" to media.path(avatar?.attr("src"), file)))
                val body = tile.selectFirst(".mx_EventTile_body, .mx_MTextBody, .mx_EventTile_content") ?: continue
                val image = body.selectFirst("img.mx_MImageBody_thumbnail, .mx_MImageBody img, img.mx_MStickerBody_image")
                val audio = body.selectFirst("audio, video")
                val download = body.selectFirst("a[download], .mx_MFileBody a[href], .mx_MImageBody a[href], .mx_MVideoBody a[href]")
                val source = audio?.attr("src")?.ifBlank { audio.selectFirst("source")?.attr("src") } ?: image?.attr("src") ?: download?.attr("href")
                val path = media.path(source, file)
                val mime = path?.let { media.assets[it]?.mime }
                val msgtype = when { mime?.startsWith("image/") == true -> "m.image"; mime?.startsWith("video/") == true -> "m.video"; mime?.startsWith("audio/") == true -> "m.audio"; path != null -> "m.file"; else -> "m.text" }
                val timestamp = tile.selectFirst("time")?.attr("datetime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                        ?: tile.selectFirst("[data-timestamp]")?.attr("data-timestamp")?.toLongOrNull() ?: raw.size.toLong()
                raw.add(mapOf("event_id" to id, "sender" to sender, "origin_server_ts" to timestamp, "type" to "m.room.message", "content" to mapOf(
                        "msgtype" to msgtype, "body" to body.text().ifBlank { image?.attr("alt") ?: download?.text().orEmpty() }, "format" to "org.matrix.custom.html", "formatted_body" to safeHtml(body.html(), media, file),
                        "url" to path, "info" to mapOf("mimetype" to mime))))
            }
            progress((fileIndex + 1) * 40 / files.size)
        }
        warnings.add(context.getString(CommonStrings.archive_html_metadata_warning))
        val snapshot = fromEvents(profiles.values.toList() + raw.distinctBy { it["event_id"] }, mapOf("room_name" to roomName), media) { progress(40 + it * 60 / 100) }
        return snapshot.copy(events = snapshot.events.filterNot { it.id.startsWith("\$profile-") }, issues = warnings)
    }
}
