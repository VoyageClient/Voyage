/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.lib.core.utils.compat.use
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackContent
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackImage
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackMeta
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/**
 * Imports and exports image packs as zip archives: the images plus an optional/emitted `meta.json`
 * (metaVersion 2: `emojis` / `stickers` lists carrying fileName, name and category per entry, and our
 * `packAvatar` pointer to whichever entry holds the pack icon).
 */
class ImagePackArchiver @Inject constructor(
        @ApplicationContext private val context: Context,
        private val activeSessionHolder: ActiveSessionHolder,
        private val repository: ImagePackRepository,
) {

    /**
     * Parses [zipUri], uploads every image and saves the result as a new pack in [roomId] under a fresh
     * state key. Returns the pack name. [onProgress] is invoked on the caller's context as
     * (packName, uploadedCount, totalCount).
     */
    suspend fun importPack(
            zipUri: Uri,
            roomId: String,
            onProgress: (String, Int, Int) -> Unit,
    ): String? {
        val zipBaseName = context.queryDisplayName(zipUri)?.removeSuffixIgnoreCase(".zip")?.takeIf { it.isNotBlank() }
        val extraction = withContext(Dispatchers.IO) { extractZip(zipUri) }
        try {
            val avatarImage = extraction.avatarImage()
            val pendings = resolveEntries(extraction, skip = avatarImage?.file)
            if (pendings.isEmpty()) {
                throw IllegalStateException(context.getString(CommonStrings.image_pack_import_empty, zipBaseName ?: "zip"))
            }
            // One shared non-blank category across every meta entry names the pack; otherwise the zip does.
            val packName = extraction.categories.singleOrNull() ?: zipBaseName
            val packUsage = when {
                pendings.all { it.emoticon && !it.sticker } -> listOf(ImagePackUsage.EMOTICON)
                pendings.all { it.sticker && !it.emoticon } -> listOf(ImagePackUsage.STICKER)
                else -> null
            }
            // Entries differing in usage can't be expressed by the stable schema (usage is pack-level there),
            // so such a pack is written with the legacy im.ponies id, which allows per-image usage.
            val perImageUsage = packUsage == null && pendings.any { it.emoticon != it.sticker }
            // The pack icon is only its own upload when it isn't one of the pack's images.
            val avatarPending = avatarImage?.takeIf { image -> pendings.none { it.file == image.file } }
                    ?.let { PendingImage(it.file, "pack_icon", emoticon = false, sticker = false) }
            val total = pendings.size + if (avatarPending != null) 1 else 0
            // Parallel uploads; the map is assembled by index afterwards, so pack order stays the
            // meta/zip order no matter which uploads finish first.
            val done = AtomicInteger(0)
            val semaphore = Semaphore(TRANSFER_PARALLELISM)
            val uploaded: List<ImagePackImage> = coroutineScope {
                (pendings + listOfNotNull(avatarPending)).map { pending ->
                    async {
                        semaphore.withPermit {
                            val image = uploadEntry(pending, perImageUsage)
                            onProgress(packName ?: "", done.incrementAndGet(), total)
                            image
                        }
                    }
                }.awaitAll()
            }
            val images = LinkedHashMap<String, ImagePackImage>()
            pendings.forEachIndexed { index, pending -> images[pending.shortcode] = uploaded[index] }
            val avatarUrl = when {
                avatarPending != null -> uploaded.last().url
                avatarImage != null -> uploaded[pendings.indexOfFirst { it.file == avatarImage.file }].url
                else -> null
            }
            val content = ImagePackContent(
                    images = images,
                    pack = ImagePackMeta(displayName = packName, avatarUrl = avatarUrl, usage = packUsage),
            )
            repository.saveRoomPack(roomId, UUID.randomUUID().toString(), content, includeUsage = true, forceLegacy = perImageUsage)
            return packName
        } finally {
            extraction.tempDir.deleteRecursively()
        }
    }

    private suspend fun uploadEntry(pending: PendingImage, perImageUsage: Boolean = false): ImagePackImage {
        val sourceUri = Uri.fromFile(pending.file)
        val sourceMime = mimeForExtension(pending.file.extension)
        var compressedTemp: File? = null
        try {
            val (uploadUri, uploadMime) = try {
                repository.compressImage(sourceUri, sourceMime, COMPRESS_MAX_DIMENSION)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                sourceUri to sourceMime
            }
            compressedTemp = uploadUri.takeIf { it != sourceUri && it.scheme == "file" }?.path?.let { File(it) }
            val info = withContext(Dispatchers.IO) { imageInfoOf(uploadUri, uploadMime) }
            val mxcUrl = repository.uploadImageWithRetry(uploadUri, "${pending.shortcode}.${pending.file.extension}", uploadMime)
            return ImagePackImage(
                    url = mxcUrl,
                    body = pending.shortcode,
                    info = info.takeIf { it.width > 0 && it.height > 0 },
                    usage = if (perImageUsage && pending.emoticon != pending.sticker) {
                        listOf(if (pending.emoticon) ImagePackUsage.EMOTICON else ImagePackUsage.STICKER)
                    } else {
                        null
                    },
            )
        } finally {
            compressedTemp?.let { runCatching { it.delete() } }
        }
    }

    private class ExtractedImage(val file: File, val baseName: String, val entryName: String)

    private class PendingImage(val file: File, var shortcode: String, var emoticon: Boolean, var sticker: Boolean)

    private class Extraction(
            val tempDir: File,
            val images: List<ExtractedImage>,
            val meta: JSONObject?,
            val categories: MutableSet<String> = mutableSetOf(),
    ) {
        fun avatarImage(): ExtractedImage? {
            val fileName = meta?.optJSONObject(META_PACK_AVATAR)?.optString("fileName")?.takeIf { it.isNotBlank() } ?: return null
            return images.firstOrNull { it.entryName == fileName } ?: images.firstOrNull { it.baseName == fileName.substringAfterLast('/') }
        }
    }

    private fun extractZip(zipUri: Uri): Extraction {
        val tempDir = File(context.cacheDir, "image_pack_import_${UUID.randomUUID()}").apply { mkdirs() }
        val images = mutableListOf<ExtractedImage>()
        var meta: JSONObject? = null
        val stream = context.contentResolver.openInputStream(zipUri) ?: throw FileNotFoundException(zipUri.toString())
        stream.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var index = 0
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    val base = name.substringAfterLast('/')
                    when {
                        entry.isDirectory || name.contains("__MACOSX") || base.startsWith(".") -> Unit
                        base.equals("meta.json", ignoreCase = true) && meta == null ->
                            meta = runCatching { JSONObject(zip.readBytes().toString(Charsets.UTF_8)) }.getOrNull()
                        base.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS -> {
                            // Extract under our own names — entry paths are untrusted (zip-slip).
                            val file = File(tempDir, "img_${index++}.${base.substringAfterLast('.').lowercase()}")
                            file.outputStream().use { out -> zip.copyTo(out) }
                            images += ExtractedImage(file, base, name)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return Extraction(tempDir, images, meta)
    }

    // Meta-listed entries first (their order), then any images the meta didn't mention, in zip order.
    // An image listed as both emoji and sticker collapses into one entry usable as both.
    private fun resolveEntries(extraction: Extraction, skip: File?): List<PendingImage> {
        val byEntryName = extraction.images.associateBy { it.entryName }
        val byBaseName = mutableMapOf<String, ExtractedImage>()
        extraction.images.forEach { if (it.baseName !in byBaseName) byBaseName[it.baseName] = it }
        val pendings = LinkedHashMap<File, PendingImage>()

        fun processList(key: String, asEmoji: Boolean) {
            val array = extraction.meta?.optJSONArray(key) ?: return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                // Misskey semantics: downloaded=false marks a remote emoji whose file isn't in the zip.
                // (Misskey also skips when the field is absent; we stay lenient for hand-made zips.)
                if (item.has("downloaded") && !item.optBoolean("downloaded")) continue
                val fileName = item.optString("fileName")
                val image = byEntryName[fileName] ?: byBaseName[fileName.substringAfterLast('/')] ?: continue
                val detail = item.optJSONObject("emoji") ?: item.optJSONObject("sticker")
                detail?.optString("category")?.takeIf { it.isNotBlank() }?.let { extraction.categories += it }
                val existing = pendings[image.file]
                if (existing != null) {
                    if (asEmoji) existing.emoticon = true else existing.sticker = true
                } else {
                    val name = detail?.optString("name")?.takeIf { it.isNotBlank() }
                            ?: image.baseName.substringBeforeLast('.')
                    pendings[image.file] = PendingImage(image.file, sanitizeShortcode(name), emoticon = asEmoji, sticker = !asEmoji)
                }
            }
        }
        processList("emojis", asEmoji = true)
        processList("stickers", asEmoji = false)
        extraction.images.forEach { image ->
            // A standalone pack icon ([skip]) is not an image of the pack; one that doubles as an entry was
            // already picked up from the meta lists above.
            if (image.file !in pendings && image.file != skip) {
                pendings[image.file] = PendingImage(image.file, sanitizeShortcode(image.baseName.substringBeforeLast('.')), emoticon = true, sticker = true)
            }
        }
        // The images map is keyed by shortcode, so colliding names (a.png + a.gif) must be uniquified.
        val used = mutableSetOf<String>()
        pendings.values.forEach { pending ->
            var candidate = pending.shortcode
            var suffix = 2
            while (!used.add(candidate)) candidate = "${pending.shortcode}_${suffix++}"
            pending.shortcode = candidate
        }
        return pendings.values.toList()
    }

    data class ExportResult(val zipFile: File, val skippedShortcodes: List<String>)

    private class ExportEntry(val image: EditableImage, val shortcode: String, val fileName: String)

    /**
     * Builds a zip (images, pack icon, meta.json) for the given editor state in the cache dir and returns it.
     * Images download [TRANSFER_PARALLELISM] at a time with up to [DOWNLOAD_MAX_ATTEMPTS] tries each;
     * a persistently-failing image is skipped and reported in the result. Runs on IO — [onProgress]
     * is invoked from there as (doneCount, totalCount).
     */
    suspend fun exportPack(
            packName: String?,
            images: List<EditableImage>,
            packUsage: List<String>?,
            packAvatarUrl: String?,
            onProgress: (Int, Int) -> Unit,
    ): ExportResult = withContext(Dispatchers.IO) {
        val session = activeSessionHolder.getActiveSession()
        val baseName = packName?.takeIf { it.isNotBlank() } ?: "image_pack"
        // Unique parent dir (deleted by the caller) so the zip itself can carry the exact pack name.
        val exportDir = File(context.cacheDir, "image_pack_export_${UUID.randomUUID()}").apply { mkdirs() }
        val zipFile = File(exportDir, "${sanitizeFileName(baseName)}.zip")
        val fixedUsage = packUsage?.singleOrNull()

        // Zip entry names assigned up front, in pack order, uniquified against collisions.
        val usedNames = mutableSetOf("meta.json")
        val entries = images.map { image ->
            val shortcode = image.shortcode.takeIf { it.isNotBlank() } ?: "image"
            val extension = extensionForMime(image.info?.mimeType)
            var fileName = "$shortcode.$extension"
            var suffix = 2
            while (!usedNames.add(fileName)) fileName = "${shortcode}_${suffix++}.$extension"
            ExportEntry(image, shortcode, fileName)
        }

        // An icon that IS one of the pack's images is referenced by that image's zip entry rather than stored twice.
        val avatarEntryIndex = packAvatarUrl?.let { url -> entries.indexOfFirst { it.image.mxcUrl == url }.takeIf { it >= 0 } }
        val downloadAvatar = packAvatarUrl != null && avatarEntryIndex == null
        val total = entries.size + if (downloadAvatar) 1 else 0

        val done = AtomicInteger(0)
        val semaphore = Semaphore(TRANSFER_PARALLELISM)
        suspend fun download(fileName: String, mimeType: String?, url: String): File? = semaphore.withPermit {
            var file: File? = null
            var attempt = 0
            while (file == null && attempt < DOWNLOAD_MAX_ATTEMPTS) {
                attempt++
                file = try {
                    session.fileService().downloadFile(fileName, mimeType, url, null)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    if (attempt < DOWNLOAD_MAX_ATTEMPTS) delay(DOWNLOAD_RETRY_DELAY_MS)
                    null
                }
            }
            onProgress(done.incrementAndGet(), total)
            file
        }

        val downloaded: List<File?> = coroutineScope {
            val entryFiles = entries.map { entry ->
                async { download(entry.fileName, entry.image.info?.mimeType, entry.image.mxcUrl) }
            }
            val avatar = if (downloadAvatar) async { download("pack_icon", null, packAvatarUrl!!) } else null
            entryFiles.awaitAll() + listOfNotNull(avatar?.await())
        }
        val avatarFile = if (downloadAvatar) downloaded.getOrNull(entries.size) else null

        val skipped = entries.filterIndexed { index, _ -> downloaded[index] == null }.map { it.shortcode }
        if (entries.isNotEmpty() && skipped.size == entries.size) {
            runCatching { exportDir.deleteRecursively() }
            throw IllegalStateException(context.getString(CommonStrings.image_pack_export_all_failed))
        }

        val emojis = JSONArray()
        val stickers = JSONArray()
        try {
            ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zip ->
                entries.forEachIndexed { index, entry ->
                    val file = downloaded[index] ?: return@forEachIndexed
                    coroutineContext.ensureActive()
                    zip.putNextEntry(ZipEntry(entry.fileName))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    fun detail() = JSONObject()
                            .put("name", entry.shortcode)
                            .putOpt("category", packName?.takeIf { it.isNotBlank() })
                            .put("aliases", JSONArray())
                    val image = entry.image
                    val emoticon = fixedUsage?.let { it == ImagePackUsage.EMOTICON } ?: (image.emoticon || !image.sticker)
                    val sticker = fixedUsage?.let { it == ImagePackUsage.STICKER } ?: (image.sticker || !image.emoticon)
                    if (emoticon) emojis.put(JSONObject().put("downloaded", true).put("fileName", entry.fileName).put("emoji", detail()))
                    if (sticker) stickers.put(JSONObject().put("downloaded", true).put("fileName", entry.fileName).put("sticker", detail()))
                }
                val avatarName = when {
                    avatarEntryIndex != null -> entries[avatarEntryIndex].fileName.takeIf { downloaded[avatarEntryIndex] != null }
                    avatarFile != null -> {
                        // The icon can also be a byte-identical copy of an image (uploaded twice, so a different
                        // mxc): reuse that entry rather than zipping the same bytes again.
                        val twin = entries.indices.firstOrNull { downloaded[it]?.let { file -> sameContent(file, avatarFile) } == true }
                        if (twin != null) {
                            entries[twin].fileName
                        } else {
                            val extension = sniffExtension(avatarFile)
                            var name = "pack_icon.$extension"
                            var suffix = 2
                            while (!usedNames.add(name)) name = "pack_icon_${suffix++}.$extension"
                            zip.putNextEntry(ZipEntry(name))
                            avatarFile.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            name
                        }
                    }
                    else -> null
                }
                val meta = JSONObject()
                        .put("metaVersion", 2)
                        .put("exportedAt", isoNow())
                        .putOpt(META_PACK_AVATAR, avatarName?.let { JSONObject().put("fileName", it) })
                        .put("emojis", emojis)
                        .put("stickers", stickers)
                zip.putNextEntry(ZipEntry("meta.json"))
                zip.write(meta.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } catch (failure: Throwable) {
            runCatching { exportDir.deleteRecursively() }
            throw failure
        }
        ExportResult(zipFile, skipped)
    }

    // read() may stop short of the buffer at any point, so fill it explicitly before comparing block by block.
    private fun InputStream.readFully(buffer: ByteArray): Int {
        var filled = 0
        while (filled < buffer.size) {
            val count = read(buffer, filled, buffer.size - filled)
            if (count < 0) break
            filled += count
        }
        return filled
    }

    private fun sameContent(left: File, right: File): Boolean {
        if (left.length() != right.length()) return false
        return runCatching {
            BufferedInputStream(left.inputStream()).use { a ->
                BufferedInputStream(right.inputStream()).use { b ->
                    val bufferA = ByteArray(DEFAULT_BUFFER_SIZE)
                    val bufferB = ByteArray(DEFAULT_BUFFER_SIZE)
                    var same = true
                    var done = false
                    while (same && !done) {
                        val readA = a.readFully(bufferA)
                        val readB = b.readFully(bufferB)
                        same = readA == readB && (0 until readA).all { bufferA[it] == bufferB[it] }
                        done = readA == 0
                    }
                    same
                }
            }
        }.getOrDefault(false)
    }

    // Downloaded media carries no name or mime, so name the icon entry after its magic bytes.
    private fun sniffExtension(file: File): String {
        val header = ByteArray(12)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        fun matches(offset: Int, vararg bytes: Int) =
                read >= offset + bytes.size && bytes.withIndex().all { (i, b) -> header[offset + i] == b.toByte() }
        return when {
            matches(0, 0x47, 0x49, 0x46) -> "gif"
            matches(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            matches(0, 0x52, 0x49, 0x46, 0x46) && matches(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
            matches(0, 0x42, 0x4D) -> "bmp"
            else -> "png"
        }
    }

    private fun isoNow(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date())

    private fun imageInfoOf(uri: Uri, mimeType: String?): ImageInfo {
        var width = 0
        var height = 0
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                width = opts.outWidth.coerceAtLeast(0)
                height = opts.outHeight.coerceAtLeast(0)
            }
        }
        val size = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l >= 0 } ?: 0L } ?: 0L
        }.getOrDefault(0L)
        return ImageInfo(mimeType = mimeType, width = width, height = height, size = size)
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
            if (endsWith(suffix, ignoreCase = true)) substring(0, length - suffix.length) else this

    private fun sanitizeFileName(name: String): String =
            name.map { if (it in "\\/:*?\"<>|") '_' else it }.joinToString("").take(60)

    private fun mimeForExtension(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        // APNG is a PNG on the wire; servers thumbnail it as such.
        else -> "image/png"
    }

    private fun extensionForMime(mimeType: String?): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "png"
    }

    companion object {
        // Not a Misskey meta key: our own pointer from the pack icon to whichever zip entry holds it.
        private const val META_PACK_AVATAR = "packAvatar"
        private const val COMPRESS_MAX_DIMENSION = 1024
        private const val TRANSFER_PARALLELISM = 10
        private const val DOWNLOAD_MAX_ATTEMPTS = 5
        private const val DOWNLOAD_RETRY_DELAY_MS = 500L
        private val IMAGE_EXTENSIONS = setOf("png", "apng", "jpg", "jpeg", "gif", "webp", "bmp")
    }
}

// MSC2545 shortcodes are ASCII [a-zA-Z0-9-_] only; map anything else (incl. Unicode letters) to '_'.
internal fun sanitizeShortcode(base: String): String =
        base.trim()
                .map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_') it else '_' }
                .joinToString("")
                .take(100)
                .ifEmpty { "emote" }

internal fun Context.queryDisplayName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment

// ClipData preserves the user's selection order; pre-16 (no clipData) the picker returns a single uri.
internal fun extractPickedUris(data: Intent?): List<Uri> {
    data ?: return emptyList()
    val clip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) data.clipData else null
    if (clip != null) {
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
    return listOfNotNull(data.data)
}
