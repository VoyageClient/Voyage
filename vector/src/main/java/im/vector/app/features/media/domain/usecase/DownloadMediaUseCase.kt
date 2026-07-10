/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media.domain.usecase

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.intent.getMimeTypeFromUri
import im.vector.app.core.utils.saveMedia
import im.vector.app.features.notifications.NotificationUtils
import im.vector.lib.core.utils.timer.Clock
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import java.io.File
import java.net.URLConnection
import javax.inject.Inject

class DownloadMediaUseCase @Inject constructor(
        @ApplicationContext private val appContext: Context,
        private val session: Session,
        private val notificationUtils: NotificationUtils,
        private val clock: Clock,
) {

    suspend fun execute(input: File, title: String? = null): Result<Unit> = withContext(session.coroutineDispatchers.io) {
        runCatching {
            // Fall back to sniffing the file's content when the type can't be derived from its name
            // (e.g. downloaded avatars have no extension), so it's saved with a proper extension.
            val mimeType = getMimeTypeFromUri(appContext, input.toUri()) ?: sniffMimeType(input)
            saveMedia(
                    context = appContext,
                    file = input,
                    title = title ?: input.name,
                    mediaMimeType = mimeType,
                    notificationUtils = notificationUtils,
                    currentTimeMillis = clock.epochMillis()
            )
        }
    }

    private fun sniffMimeType(file: File): String? {
        // BitmapFactory covers the image types URLConnection misses (notably WebP).
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        opts.outMimeType?.let { return it }
        tryOrNull {
            file.inputStream().buffered().use { URLConnection.guessContentTypeFromStream(it) }
        }?.let { return it }
        // Video/audio containers: ask the media framework.
        return tryOrNull {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            } finally {
                retriever.release()
            }
        }
    }
}
