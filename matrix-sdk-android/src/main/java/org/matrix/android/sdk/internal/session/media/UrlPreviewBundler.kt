/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import android.graphics.BitmapFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.settings.LinkPreviewMode
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.crypto.attachments.MXEncryptedAttachments
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.session.content.FileUploader
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryDataSource
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.util.time.Clock
import javax.inject.Inject

private const val MAX_PREVIEWS = 4

// How long a send is willing to wait for a preview it does not already have. Typing the link starts the
// work early, but paste-and-send is common and a page fetch plus thumbnail upload takes several seconds,
// so the wait must cover a full build; the local echo is already showing, and a site that cannot be
// previewed is remembered so it stalls the queue at most once.
private const val SEND_GRACE_MS = 10_000L

private val DISPLAYABLE_KEYS = setOf(
        "og:title",
        "og:description",
        "og:site_name",
        "og:image",
        BundledUrlPreviews.IMAGE_ENCRYPTED
)

/**
 * Generates the MSC4095 `m.url_previews` of an outgoing message, so that receiving clients do not have
 * to hand the links over to their own homeserver — which is what makes previews usable in encrypted
 * rooms at all.
 *
 * Where the preview itself comes from is the user's choice ([LinkPreviewMode]): the page is either read
 * by this device, which tells nobody but the site, or by our homeserver, which is one server rather
 * than everyone's but does learn the link. Either way the thumbnail is reuploaded as our own media
 * (encrypted for an encrypted room) so it outlives whatever cache it came from.
 */
internal class UrlPreviewBundler @Inject constructor(
        private val urlsExtractor: UrlsExtractor,
        private val urlPreviewFetcher: UrlPreviewFetcher,
        private val homeServerUrlPreviewFetcher: HomeServerUrlPreviewFetcher,
        private val fileUploader: FileUploader,
        private val bundleCache: UrlPreviewBundleCache,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        private val roomSummaryDataSource: RoomSummaryDataSource,
        private val localEchoRepository: LocalEchoRepository,
        private val taskExecutor: TaskExecutor,
        private val clock: Clock,
) : LinkPreviewPrefetcher {

    /**
     * @return [event] with its bundled previews added, or [event] itself when there is nothing to bundle.
     * The local echo is updated along the way, so the sender sees the same previews as everybody else.
     */
    override suspend fun bundleUrlPreviews(event: Event, encrypt: Boolean): Event {
        val eventId = event.eventId ?: return event
        val roomId = event.roomId ?: return event
        val content = event.content?.takeIf { event.getClearType() == EventType.MESSAGE } ?: return event
        if (content.containsKey(BundledUrlPreviews.CONTENT_KEY) || content.containsKey(BundledUrlPreviews.CONTENT_KEY_UNSTABLE)) {
            // Already bundled, this is a resend.
            return event
        }
        // An edit previews the text it replaces the message with.
        @Suppress("UNCHECKED_CAST")
        val newContent = content["m.new_content"] as? Map<String, Any>
        val previewedContent = newContent ?: content
        val msgType = previewedContent["msgtype"] as? String
        if (msgType != MessageType.MSGTYPE_TEXT && msgType != MessageType.MSGTYPE_NOTICE && msgType != MessageType.MSGTYPE_EMOTE) {
            return event
        }
        val body = previewedContent["body"] as? String ?: return event
        val urls = previewableUrls(ContentUtils.extractUsefulTextFromReply(body))
        if (urls.isEmpty()) return event

        val onDevice = generatesOnDevice(roomId, encrypt)
        // Concurrently: the room's send queue is sequential, so several links must not add up their waits.
        val previews = coroutineScope {
            urls.map { url -> async { previewForSending(url, onDevice, encrypt) } }.awaitAll().filterNotNull()
        }
        if (previews.isEmpty()) return event

        val bundledContent = content.withPreviews(previews)
                .let { if (newContent == null) it else it + ("m.new_content" to newContent.withPreviews(previews)) }
        localEchoRepository.updateEcho(eventId) { it.content = ContentMapper.map(bundledContent) }
        return event.copy(content = bundledContent)
    }

    private fun generatesOnDevice(roomId: String, encrypt: Boolean): Boolean {
        return when (lightweightSettingsStorage.getLinkPreviewMode(roomId)) {
            LinkPreviewMode.ALWAYS -> true
            LinkPreviewMode.NEVER -> false
            LinkPreviewMode.ENCRYPTED_ROOMS -> encrypt
            LinkPreviewMode.DIRECT_MESSAGES -> roomSummaryDataSource.getRoomSummary(roomId)?.isDirect == true
        }
    }

    private fun Map<String, Any>.withPreviews(previews: List<JsonDict>): Content {
        return this + mapOf(
                BundledUrlPreviews.CONTENT_KEY to previews,
                BundledUrlPreviews.CONTENT_KEY_UNSTABLE to previews
        )
    }

    /**
     * Read and upload the previews of the links in [text] before they are needed. Sending a message is what
     * would otherwise wait for the page and the thumbnail upload, which is most of the delay the user sees.
     */
    override suspend fun prefetch(roomId: String, text: CharSequence, encrypt: Boolean) {
        val onDevice = generatesOnDevice(roomId, encrypt)
        previewableUrls(ContentUtils.extractUsefulTextFromReply(text.toString())).forEach { url ->
            val key = UrlPreviewBundleCache.Key(url = url, onDevice = onDevice, encrypted = encrypt)
            bundleCache.getOrBuild(key) { fetchAndUpload(url, onDevice, encrypt) }
        }
    }

    private fun previewableUrls(text: String) = urlsExtractor.extract(text)
            .filterNot { isNotPreviewable(it) }
            .take(MAX_PREVIEWS)

    /**
     * The preview to bundle for [url] when a message is going out. Typing the link is usually what fetched
     * and uploaded it, so this costs nothing; when it is not ready the message waits only a moment for it,
     * and the work carries on in the background for the next one rather than holding the send up.
     */
    private suspend fun previewForSending(url: String, onDevice: Boolean, encrypt: Boolean): JsonDict? {
        val key = UrlPreviewBundleCache.Key(url = url, onDevice = onDevice, encrypted = encrypt)
        bundleCache.peek(key)?.let { return it.preview?.withMatchedUrl(url) }
        val building = taskExecutor.executorScope.async {
            bundleCache.getOrBuild(key) { fetchAndUpload(url, onDevice, encrypt) }
        }
        return withTimeoutOrNull(SEND_GRACE_MS) { building.await() }?.withMatchedUrl(url)
    }

    private fun JsonDict.withMatchedUrl(url: String): JsonDict = this + mapOf(
            BundledUrlPreviews.MATCHED_URL to url,
            BundledUrlPreviews.MATCHED_URL_UNSTABLE to url
    )

    private suspend fun fetchAndUpload(url: String, onDevice: Boolean, encrypt: Boolean): JsonDict? {
        val fetched = tryOrNull("Failed to preview $url") {
            if (onDevice) urlPreviewFetcher.fetch(url) else homeServerUrlPreviewFetcher.fetch(url)
        }
        fetched ?: return null
        val preview = fetched.fields.toMutableMap()
        // The image entry always describes our own reupload, never the site's URL: an encrypted room's
        // upload lands under matrix:image:encrypted, which would otherwise leave the external og:image
        // in place for receivers to fetch from the site directly.
        preview.remove("og:image")
        fetched.image?.let { uploadImage(it, encrypt) }?.let { preview += it }
        // An entry nobody can display is one the receiver would take as an invitation to ask their own
        // homeserver about the link — the very thing bundling is for.
        return preview.takeIf { it.keys.any { key -> key in DISPLAYABLE_KEYS } }
    }

    /**
     * Reupload the thumbnail as our own media, encrypting it for an encrypted room, and describe it the
     * way `/preview_url` describes the images it caches.
     */
    private suspend fun uploadImage(image: FetchedImage, encrypt: Boolean): JsonDict? {
        return tryOrNull("Failed to upload preview image") {
            val uploaded = if (encrypt) {
                val encrypted = MXEncryptedAttachments.encryptAttachment(image.bytes.inputStream(), clock)
                val response = fileUploader.uploadByteArray(
                        byteArray = encrypted.encryptedByteArray,
                        filename = null,
                        mimeType = MimeTypes.OctetStream
                )
                val encryptedFileInfo = encrypted.encryptedFileInfo.copy(url = response.contentUri).toContent()
                mapOf(
                        BundledUrlPreviews.IMAGE_ENCRYPTED to encryptedFileInfo,
                        BundledUrlPreviews.IMAGE_ENCRYPTED_UNSTABLE to encryptedFileInfo
                )
            } else {
                val response = fileUploader.uploadByteArray(
                        byteArray = image.bytes,
                        filename = null,
                        mimeType = image.mimeType ?: MimeTypes.OctetStream
                )
                mapOf("og:image" to response.contentUri)
            }
            uploaded + buildMap {
                put(BundledUrlPreviews.IMAGE_SIZE, image.bytes.size)
                image.mimeType?.let { put("og:image:type", it) }
                putAll(image.bytes.dimensions())
            }
        }
    }

    private fun ByteArray.dimensions(): JsonDict {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(this, 0, size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            mapOf("og:image:width" to options.outWidth, "og:image:height" to options.outHeight)
        } else {
            emptyMap()
        }
    }

    // matrix.to links are permalinks to rooms, events and users: there is nothing to preview, and
    // fetching one would tell that server which permalinks are being sent.
    private fun isNotPreviewable(url: String) = url.startsWith("https://matrix.to/")
}
