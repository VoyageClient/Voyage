/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.core.net.toUri
import im.vector.app.core.utils.safeStartActivity
import im.vector.app.features.media.domain.usecase.DownloadMediaUseCase
import im.vector.app.features.navigation.Navigator
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeImage
import org.matrix.android.sdk.api.util.MimeTypes.isMimeTypeVideo
import java.io.File
import javax.inject.Inject

/** Opens a bare (unencrypted) mxc:// URL the way /view does: images and videos in-app, anything else in another app. */
class MxcMediaOpener @Inject constructor(
        private val session: Session,
        private val downloadMediaUseCase: DownloadMediaUseCase,
        private val navigator: Navigator,
) {

    data class Media(val mxcUrl: String, val file: File, val mimeType: String?, val externalUri: String?)

    suspend fun download(mxcUrl: String): Media {
        val fileName = mxcUrl.substringAfterLast('/')
        val file = session.fileService().downloadFile(
                fileName = fileName,
                mimeType = null,
                url = mxcUrl,
                elementToDecrypt = null,
        )
        val mimeType = downloadMediaUseCase.mimeTypeOf(file)
        val externalUri = if (mimeType.isMimeTypeImage() || mimeType.isMimeTypeVideo()) {
            null
        } else {
            requireNotNull(session.fileService().getTemporarySharableURI(
                    mxcUrl = mxcUrl,
                    fileName = fileName,
                    mimeType = null,
                    elementToDecrypt = null,
            ))
        }
        return Media(mxcUrl, file, mimeType, externalUri)
    }

    /** Returns whether [media] went to the in-app viewer rather than to a chooser for another app. */
    fun open(activity: Activity, roomId: String, media: Media, view: View): Boolean {
        val imageData = ImageContentRenderer.Data(
                eventId = media.mxcUrl,
                filename = media.file.name,
                mimeType = media.mimeType,
                url = media.mxcUrl,
                elementToDecrypt = null,
                height = null,
                maxHeight = 1024,
                width = null,
                maxWidth = 2048,
        )
        val mediaData = when {
            media.mimeType.isMimeTypeImage() -> imageData
            media.mimeType.isMimeTypeVideo() -> VideoContentRenderer.Data(
                    eventId = media.mxcUrl,
                    filename = media.file.name,
                    mimeType = media.mimeType,
                    url = media.mxcUrl,
                    elementToDecrypt = null,
                    thumbnailMediaData = imageData,
            )
            else -> {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(requireNotNull(media.externalUri).toUri(), media.mimeType ?: MimeTypes.Any)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.safeStartActivity(Intent.createChooser(intent, null))
                return false
            }
        }
        navigator.openMediaViewer(
                activity = activity,
                roomId = roomId,
                mediaData = mediaData,
                view = view,
                standalonePreview = true,
                hideShowInChat = true,
                morphFromView = false,
                options = null,
        )
        return true
    }
}
