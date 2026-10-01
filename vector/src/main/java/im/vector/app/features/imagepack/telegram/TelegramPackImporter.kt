/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.telegram

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.imagepack.EmojiShortNames
import im.vector.app.features.imagepack.edit.ImagePackArchiver
import im.vector.app.features.imagepack.edit.ImagePackRepository
import im.vector.app.features.imagepack.edit.ImportedAdditions
import im.vector.app.features.imagepack.edit.ImportedImage
import im.vector.app.features.imagepack.edit.sanitizeShortcode
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackContent
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackImage
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackMeta
import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.matrix.android.sdk.api.session.room.model.imagepack.effectiveImages
import org.matrix.android.sdk.api.session.room.model.imagepack.withSequentialOrder
import org.matrix.android.sdk.api.util.JsonDict
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Imports Telegram sticker sets as room image packs. The pack remembers its source set and which Telegram
 * sticker became which upload (see [TelegramMarker]), so importing the same set again only adds what the
 * pack is missing.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
class TelegramPackImporter @Inject constructor(
        @ApplicationContext private val context: Context,
        private val botApi: TelegramBotApi,
        private val converter: TelegramStickerConverter,
        private val archiver: ImagePackArchiver,
        private val repository: ImagePackRepository,
        private val vectorPreferences: VectorPreferences,
        private val stringProvider: StringProvider,
        private val emojiNames: EmojiShortNames,
) {

    sealed interface Result {
        val packName: String
        val skipped: List<String>

        data class Created(override val packName: String, override val skipped: List<String>) : Result
        data class Additions(override val packName: String, val stateKey: String, val additions: ImportedAdditions, override val skipped: List<String>) : Result
        data class UpToDate(override val packName: String) : Result {
            override val skipped: List<String> get() = emptyList()
        }
    }

    /** [onProgress] gets (packName, done, total), possibly off the main thread. */
    suspend fun import(roomId: String, setName: String, onProgress: (String, Int, Int) -> Unit): Result {
        val token = requireToken()
        val set = botApi.getStickerSet(token, setName)
        val existing = findExistingPack(roomId, set.name)
        val workDir = newWorkDir()
        try {
            if (existing == null) return createPack(roomId, token, set, workDir, onProgress)
            val (stateKey, raw) = existing
            val content = raw.toModel<ImagePackContent>()
            val images = content?.effectiveImages().orEmpty()
            val marker = TelegramMarker.read(raw)
            val missing = TelegramMarker.missing(set.stickers, marker?.stickers.orEmpty(), images.values.map { it.url }.toSet())
            val packName = content?.pack?.displayName ?: set.title
            if (missing.isEmpty()) return Result.UpToDate(packName)
            val pending = pendingFor(set, missing, images.keys.toMutableSet())
            val uploaded = uploadAll(token, pending, workDir) { done, total -> onProgress(packName, done, total) }
            val added = pending.zip(uploaded).mapNotNull { (item, image) -> image?.let { item to it } }
            if (added.isEmpty()) throw IllegalStateException(stringProvider.getString(CommonStrings.image_pack_telegram_skipped, pending.joinToString(", ") { it.shortcode }))
            val stickers = HashMap(marker?.stickers.orEmpty())
            added.forEach { (item, image) -> stickers[item.sticker.fileUniqueId] = image.url }
            val additions = ImportedAdditions(
                    images = added.map { (item, image) ->
                        ImportedImage(
                                shortcode = item.shortcode,
                                mxcUrl = image.url,
                                body = image.body,
                                mimeType = image.info?.mimeType,
                                width = image.info?.width ?: 0,
                                height = image.info?.height ?: 0,
                                size = image.info?.size ?: 0,
                                telegramIndex = item.index,
                        )
                    },
                    telegramSet = set.name,
                    telegramStickers = stickers,
                    telegramOrder = set.stickers.map { it.fileUniqueId },
            )
            return Result.Additions(packName, stateKey, additions, skippedOf(pending, uploaded))
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Builds a Misskey-style zip of [setName] in the cache; the caller deletes the zip's parent directory. */
    suspend fun buildZip(setName: String, onProgress: (String, Int, Int) -> Unit): File {
        val token = requireToken()
        val set = botApi.getStickerSet(token, setName)
        val workDir = newWorkDir()
        try {
            val pending = pendingFor(set, set.stickers, mutableSetOf())
            val total = pending.size + if (set.thumbnailFileId != null) 1 else 0
            val done = AtomicInteger(0)
            val semaphore = Semaphore(PARALLELISM)
            val converted = coroutineScope {
                pending.map { item ->
                    async {
                        semaphore.withPermit {
                            fetchAndConvert(token, item.sticker.fileId, workDir, item.shortcode).also { onProgress(set.title, done.incrementAndGet(), total) }
                        }
                    }
                }.awaitAll()
            }
            val avatar = set.thumbnailFileId?.let { fetchAndConvert(token, it, workDir, "pack_icon") }?.also { onProgress(set.title, done.incrementAndGet(), total) }
            val entries = pending.zip(converted).mapNotNull { (item, sticker) ->
                sticker ?: return@mapNotNull null
                ImagePackArchiver.MisskeyZipEntry(
                        file = sticker.file,
                        fileName = "${item.shortcode}.${sticker.file.extension}",
                        shortcode = item.shortcode,
                        emoticon = set.isCustomEmoji,
                        sticker = !set.isCustomEmoji,
                )
            }
            if (entries.isEmpty()) throw IllegalStateException(stringProvider.getString(CommonStrings.image_pack_import_empty, set.title))
            val zipDir = File(context.cacheDir, "telegram_pack_${UUID.randomUUID()}").apply { mkdirs() }
            val zipFile = File(zipDir, "${set.name}.zip")
            val avatarEntry = avatar?.let { ImagePackArchiver.MisskeyZipEntry(it.file, "pack_icon.${it.file.extension}", "pack_icon", emoticon = false, sticker = false) }
            try {
                withContext(Dispatchers.IO) { archiver.writeMisskeyZip(zipFile, set.title, entries, avatarEntry?.fileName, avatarEntry) }
            } catch (failure: Throwable) {
                zipDir.deleteRecursively()
                throw failure
            }
            return zipFile
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun createPack(roomId: String, token: String, set: TelegramStickerSet, workDir: File, onProgress: (String, Int, Int) -> Unit): Result {
        if (set.stickers.isEmpty()) throw IllegalStateException(stringProvider.getString(CommonStrings.image_pack_import_empty, set.title))
        val pending = pendingFor(set, set.stickers, mutableSetOf())
        val hasThumbnail = set.thumbnailFileId != null
        val total = pending.size + if (hasThumbnail) 1 else 0
        val uploaded = uploadAll(token, pending, workDir, totalOverride = total) { done, all -> onProgress(set.title, done, all) }
        val images = LinkedHashMap<String, ImagePackImage>()
        val stickers = HashMap<String, String>()
        pending.zip(uploaded).forEach { (item, image) ->
            image ?: return@forEach
            images[item.shortcode] = image
            stickers[item.sticker.fileUniqueId] = image.url
        }
        if (images.isEmpty()) throw IllegalStateException(stringProvider.getString(CommonStrings.image_pack_import_empty, set.title))
        val avatarUrl = set.thumbnailFileId?.let { fileId ->
            val result = runCatchingNonCancel {
                fetchAndConvert(token, fileId, workDir, "pack_icon")?.let { converted ->
                    archiver.uploadImageFile(converted.file, converted.mimeType, "pack_icon", body = null, compress = !converted.animated, knownSize = converted.size).url
                }
            }
            onProgress(set.title, total, total)
            result
        }
        val content = ImagePackContent(
                images = images.withSequentialOrder(),
                pack = ImagePackMeta(
                        displayName = set.title,
                        avatarUrl = avatarUrl,
                        usage = listOf(if (set.isCustomEmoji) ImagePackUsage.EMOTICON else ImagePackUsage.STICKER),
                ),
        )
        repository.saveRoomPack(
                roomId,
                UUID.randomUUID().toString(),
                content,
                includeUsage = true,
                extraTopLevel = TelegramMarker.toTopLevel(set.name, stickers),
        )
        return Result.Created(set.title, skippedOf(pending, uploaded))
    }

    // [index] is the sticker's position in the Telegram set.
    private class PendingSticker(val sticker: TelegramSticker, val shortcode: String, val index: Int)

    // Named after each sticker's emoji, falling back to <set>_<position> when the emoji isn't in our data.
    private suspend fun pendingFor(set: TelegramStickerSet, stickers: List<TelegramSticker>, used: MutableSet<String>): List<PendingSticker> =
            withContext(Dispatchers.Default) {
                stickers.map { sticker ->
                    val index = set.stickers.indexOf(sticker)
                    val base = emojiNames.nameFor(sticker.emoji)?.let { sanitizeShortcode(it) }
                            ?: "${sanitizeShortcode(set.name)}_${index + 1}"
                    PendingSticker(sticker, uniqueShortcode(base, used), index)
                }
            }

    // Null entries are stickers that failed to download/convert/upload; the rest of the set still goes in.
    private suspend fun uploadAll(
            token: String,
            pending: List<PendingSticker>,
            workDir: File,
            totalOverride: Int? = null,
            onProgress: (Int, Int) -> Unit,
    ): List<ImagePackImage?> {
        val total = totalOverride ?: pending.size
        val done = AtomicInteger(0)
        val semaphore = Semaphore(PARALLELISM)
        return coroutineScope {
            pending.map { item ->
                async {
                    semaphore.withPermit {
                        val image = runCatchingNonCancel {
                            fetchAndConvert(token, item.sticker.fileId, workDir, item.shortcode)?.let { converted ->
                                archiver.uploadImageFile(
                                        converted.file,
                                        converted.mimeType,
                                        item.shortcode,
                                        body = item.shortcode,
                                        compress = !converted.animated,
                                        knownSize = converted.size,
                                )
                            }
                        }
                        onProgress(done.incrementAndGet(), total)
                        image
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchAndConvert(token: String, fileId: String, workDir: File, baseName: String): ConvertedSticker? = runCatchingNonCancel {
        val downloaded = botApi.download(token, fileId, workDir, "src_$baseName")
        withContext(Dispatchers.Default) { converter.convert(downloaded, File(workDir, "$baseName.webp")) }
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Timber.w(failure, "Telegram sticker import failed")
        null
    }

    private fun skippedOf(pending: List<PendingSticker>, uploaded: List<ImagePackImage?>): List<String> =
            pending.zip(uploaded).filter { it.second == null }.map { it.first.shortcode }

    private fun findExistingPack(roomId: String, setName: String): Pair<String, JsonDict>? =
            repository.getRoomOwnPacks(roomId).firstNotNullOfOrNull { pack ->
                val stateKey = pack.stateKey ?: return@firstNotNullOfOrNull null
                val raw = repository.getRoomPackRawContent(roomId, stateKey) ?: return@firstNotNullOfOrNull null
                (stateKey to raw).takeIf { TelegramMarker.read(raw)?.set.equals(setName, ignoreCase = true) }
            }

    private fun requireToken(): String =
            vectorPreferences.telegramBotToken() ?: throw IllegalStateException(stringProvider.getString(CommonStrings.image_pack_telegram_token_missing))

    private fun newWorkDir() = File(context.cacheDir, "telegram_import_${UUID.randomUUID()}").apply { mkdirs() }

    private fun uniqueShortcode(base: String, used: MutableSet<String>): String {
        var candidate = base
        var suffix = 2
        while (!used.add(candidate)) candidate = "${base}_${suffix++}"
        return candidate
    }

    private companion object {
        // Conversion is CPU and memory heavy (a 512px ARGB frame plus YUV planes per job), so keep it low.
        const val PARALLELISM = 3
    }
}

/** The `im.voyage.telegram` key on a pack event: its source set and Telegram file_unique_id → mxc url. */
object TelegramMarker {
    const val KEY = "im.voyage.telegram"

    data class Marker(val set: String, val stickers: Map<String, String>)

    fun read(raw: JsonDict): Marker? {
        val value = raw[KEY] as? Map<*, *> ?: return null
        val set = value["set"] as? String ?: return null
        val stickers = (value["stickers"] as? Map<*, *>)
                ?.mapNotNull { (key, url) -> if (key is String && url is String) key to url else null }
                ?.toMap()
                .orEmpty()
        return Marker(set, stickers)
    }

    fun toTopLevel(set: String, stickers: Map<String, String>): JsonDict = mapOf(KEY to mapOf("set" to set, "stickers" to stickers))

    /**
     * Stickers the pack lacks: never imported, or imported but since removed from the pack. A renamed
     * image still counts as present, since it keeps its url.
     */
    fun missing(stickers: List<TelegramSticker>, imported: Map<String, String>, packUrls: Set<String>): List<TelegramSticker> =
            stickers.filter { sticker -> imported[sticker.fileUniqueId]?.let { it in packUrls } != true }

    /**
     * Where a re-added sticker at [telegramIndex] goes in a pack listed as [packUrls]: before the first image
     * that comes later in the set, else after the set's last image, else at the end. Images that aren't
     * from the set keep their place.
     */
    fun insertionIndex(packUrls: List<String>, telegramIndexByUrl: Map<String, Int>, telegramIndex: Int): Int {
        val later = packUrls.indexOfFirst { (telegramIndexByUrl[it] ?: -1) > telegramIndex }
        if (later >= 0) return later
        val lastFromSet = packUrls.indexOfLast { it in telegramIndexByUrl }
        return if (lastFromSet >= 0) lastFromSet + 1 else packUrls.size
    }
}
