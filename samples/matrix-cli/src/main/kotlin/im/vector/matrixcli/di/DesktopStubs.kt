/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.session.content.ContentUriResolver
import org.matrix.android.sdk.internal.session.content.ImageExifTagRemover
import org.matrix.android.sdk.internal.session.content.ThumbnailExtractor
import org.matrix.android.sdk.internal.session.media.LinkPreviewPrefetcher
import org.matrix.android.sdk.internal.session.media.WebUrlPattern
import org.matrix.android.sdk.internal.session.room.send.VideoMetadataExtractor
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils
import org.matrix.android.sdk.internal.session.workmanager.WorkManagerConfig
import org.matrix.android.sdk.internal.util.time.Clock
import java.io.File
import javax.inject.Inject

// The seams android fills with its media and text stacks. Desktop has no equivalent for these, so
// each one degrades to the mildest sensible answer rather than failing the send that reached it.

internal class DesktopThumbnailExtractor @Inject constructor() : ThumbnailExtractor {
    override fun extractThumbnail(attachment: ContentAttachmentData, withBlurHash: Boolean): ThumbnailExtractor.ThumbnailData? = null
    override fun extractVideoThumbnailFromFile(file: File): ThumbnailExtractor.ThumbnailData? = null
}

internal class DesktopImageExifTagRemover @Inject constructor() : ImageExifTagRemover {
    override suspend fun stripImageMetadata(imageFile: File): File = imageFile
}

internal class DesktopContentUriResolver @Inject constructor(
        @SessionFilesDirectory private val sessionDirectory: File,
        private val clock: Clock,
) : ContentUriResolver {

    override suspend fun copyToTempFile(uriString: String): File = withContext(Dispatchers.IO) {
        val source = uriString.toLocalFile()
        val directory = File(sessionDirectory, "tmp").also { it.mkdirs() }
        source.copyTo(File(directory, "${clock.epochMillis()}-${source.name}"), overwrite = true)
    }
}

internal class DesktopVideoMetadataExtractor @Inject constructor() : VideoMetadataExtractor {

    // No media stack to demux with. The dimensions only feed the event's info block, and android
    // reports the same 0x0 when a file carries no dimension metadata, so the send still goes through.
    override fun getVideoSize(attachment: ContentAttachmentData): Pair<Int, Int> = 0 to 0
}

internal class DesktopTextPillsUtils @Inject constructor() : TextPillsUtils {
    override fun processSpecialSpansToHtml(text: CharSequence): String? = null
    override fun processSpecialSpansToMarkdown(text: CharSequence): String? = null
}

internal class DesktopLinkPreviewPrefetcher @Inject constructor() : LinkPreviewPrefetcher {
    override suspend fun prefetch(roomId: String, text: CharSequence, encrypt: Boolean) = Unit
    override suspend fun bundleUrlPreviews(event: Event, encrypt: Boolean): Event = event
}

internal class DesktopWebUrlPattern @Inject constructor() : WebUrlPattern {

    // Deliberately simpler than android's Patterns.WEB_URL: enough to find the links in a message.
    override val regex = Regex("""https?://\S+""")
}

internal class DesktopWorkManagerConfig @Inject constructor() : WorkManagerConfig {
    override fun withNetworkConstraint(): Boolean = false
}
