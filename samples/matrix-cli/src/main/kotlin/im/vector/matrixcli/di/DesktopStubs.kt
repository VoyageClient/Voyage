/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli.di

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.session.content.ContentUriResolver
import org.matrix.android.sdk.internal.session.content.ImageExifTagRemover
import org.matrix.android.sdk.internal.session.content.ThumbnailExtractor
import org.matrix.android.sdk.internal.session.media.ImageDimensionsReader
import org.matrix.android.sdk.internal.session.media.WebUrlPattern
import org.matrix.android.sdk.internal.session.room.send.VideoMetadataExtractor
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils
import org.matrix.android.sdk.internal.session.workmanager.WorkManagerConfig
import org.matrix.android.sdk.internal.util.time.Clock
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import javax.inject.Inject

// The seams android fills with its media and text stacks. These answer null/empty because that is
// the correct answer off-android, not because they are unfinished: the thumbnail and metadata seams
// are video-only (android returns null for anything else too, and a JVM has no demuxer), and the
// pill converter reads Spannable spans, which desktop text does not carry.

internal class DesktopThumbnailExtractor @Inject constructor() : ThumbnailExtractor {
    // Video frames only — see above. Image attachments never reach this seam on any platform.
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

internal class DesktopImageDimensionsReader @Inject constructor() : ImageDimensionsReader {

    override fun read(bytes: ByteArray): Pair<Int, Int>? = tryOrNull {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@use null
            try {
                reader.input = stream
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }
}

internal class DesktopWebUrlPattern @Inject constructor() : WebUrlPattern {

    // Deliberately simpler than android's Patterns.WEB_URL: enough to find the links in a message.
    override val regex = Regex("""https?://\S+""")
}

internal class DesktopWorkManagerConfig @Inject constructor() : WorkManagerConfig {
    override fun withNetworkConstraint(): Boolean = false
}
