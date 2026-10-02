/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.app.ActivityOptionsCompat
import im.vector.app.R
import im.vector.app.features.media.ImageContentRenderer
import im.vector.app.features.media.VectorAttachmentViewerActivity
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo
import java.io.File

object ImagePackImageViewer {

    /**
     * Opens a pack image (or an unsaved local draft) standalone in the media viewer. With [origin], the
     * viewer grows out of that view's bounds; otherwise it fades in.
     */
    @Suppress("DEPRECATION")
    fun open(
            activity: Activity,
            mxcUrl: String?,
            name: String,
            info: ImageInfo?,
            localFile: File? = null,
            localMimeType: String? = null,
            origin: View? = null,
    ) {
        val source = mxcUrl ?: localFile?.let { Uri.fromFile(it).toString() } ?: return
        val imageData = ImageContentRenderer.Data(
                eventId = "image_pack_${source.hashCode()}",
                filename = name,
                mimeType = info?.mimeType ?: localMimeType,
                url = source,
                elementToDecrypt = null,
                height = info?.height,
                maxHeight = -1,
                width = info?.width,
                maxWidth = -1,
                allowNonMxcUrls = localFile != null,
                preservedFile = localFile,
        )
        val intent = VectorAttachmentViewerActivity.newIntent(
                context = activity,
                mediaData = imageData,
                roomId = null,
                eventId = imageData.eventId,
                inMemoryData = listOf(imageData),
                sharedTransitionName = null,
                standalonePreview = true,
                hideShowInChat = true,
                hideForward = true,
        )
        // ActivityOptions only exist from API 16; below that the pending transition is the only hook.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            activity.startActivity(intent)
            activity.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            return
        }
        val options = if (origin != null && origin.width > 0 && origin.height > 0) {
            ActivityOptionsCompat.makeScaleUpAnimation(origin, 0, 0, origin.width, origin.height)
        } else {
            ActivityOptionsCompat.makeCustomAnimation(activity, R.anim.fade_in, R.anim.fade_out)
        }
        ActivityCompat.startActivity(activity, intent, options.toBundle())
    }
}
