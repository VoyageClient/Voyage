/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.media.BundledUrlPreview
import org.matrix.android.sdk.api.session.media.PreviewUrlData
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.util.unescapeHtml

/**
 * Bundled URL previews, as defined by MSC4095.
 */
internal object BundledUrlPreviews {

    const val CONTENT_KEY = "m.url_previews"
    const val CONTENT_KEY_UNSTABLE = "com.beeper.linkpreviews"

    const val MATCHED_URL = "matrix:matched_url"
    const val MATCHED_URL_UNSTABLE = "matched_url"

    const val IMAGE_ENCRYPTED = "matrix:image:encrypted"
    const val IMAGE_ENCRYPTED_UNSTABLE = "beeper:image:encryption"

    const val IMAGE_SIZE = "matrix:image:size"

    /**
     * @return the previews bundled in [content], or null if it does not bundle any. An empty list means
     * that the sender asked for no preview at all to be displayed.
     */
    fun parse(content: Content?): List<BundledUrlPreview>? {
        content ?: return null
        val previews = (content[CONTENT_KEY] ?: content[CONTENT_KEY_UNSTABLE]) as? List<*> ?: return null
        // MSC4095: an entry is only valid if the url it previews is actually part of the message.
        val body = content["body"] as? String ?: return emptyList()
        return previews.mapNotNull { (it as? Map<*, *>)?.toBundledUrlPreview(body) }
    }

    private fun Map<*, *>.toBundledUrlPreview(body: String): BundledUrlPreview? {
        val declared = string(MATCHED_URL) ?: string(MATCHED_URL_UNSTABLE)
        val matchedUrl = declared?.takeIf { it.isNotBlank() && body.contains(it) } ?: return null
        return BundledUrlPreview(matchedUrl = matchedUrl, previewUrlData = toPreviewUrlData(matchedUrl))
    }

    /**
     * @return null for an entry which only whitelists the url, without carrying any data to display.
     */
    private fun Map<*, *>.toPreviewUrlData(matchedUrl: String): PreviewUrlData? {
        val siteName = string("og:site_name")?.unescapeHtml()
        val title = string("og:title")?.unescapeHtml()
        val description = string("og:description")?.unescapeHtml()
        val encryptedImage = ((this[IMAGE_ENCRYPTED] ?: this[IMAGE_ENCRYPTED_UNSTABLE]) as? Map<*, *>)
                ?.toEncryptedFileInfo()
                ?.takeIf { it.isValid() }
        // Only our own media is displayable: a preview must not make the client fetch a remote http url.
        val mxcUrl = string("og:image")?.takeIf { encryptedImage == null && it.isMxcUrl() }
        if (siteName == null && title == null && description == null && mxcUrl == null && encryptedImage == null) {
            return null
        }
        return PreviewUrlData(
                url = string("og:url") ?: matchedUrl,
                siteName = siteName,
                title = title,
                description = description,
                mxcUrl = mxcUrl,
                imageWidth = int("og:image:width"),
                imageHeight = int("og:image:height"),
                imageMimeType = string("og:image:type"),
                encryptedImage = encryptedImage
        )
    }

    private fun Map<*, *>.toEncryptedFileInfo(): EncryptedFileInfo? {
        return MoshiProvider.providesMoshi()
                .adapter(EncryptedFileInfo::class.java)
                .runCatching { fromJsonValue(this@toEncryptedFileInfo) }
                .getOrNull()
    }

    private fun Map<*, *>.string(key: String) = this[key] as? String

    private fun Map<*, *>.int(key: String) = (this[key] as? Number)?.toInt()
}
