/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.content.Context
import android.net.Uri
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import im.vector.app.core.glide.MediaCache
import im.vector.lib.mediatranscode.VideoEditExporter
import im.vector.lib.mediatranscode.VideoEditProgressListener
import im.vector.lib.mediatranscode.VideoEditSpec
import java.io.File
import java.util.UUID

/** Runs a [VideoEditorEdits] through the transcode library and publishes the result. */
object VideoEditorExporter {

    data class Result(
            val uri: Uri,
            val width: Int,
            val height: Int,
            val size: Long,
            val mimeType: String,
            val durationMs: Long,
            val audioDropped: Boolean,
    )

    private const val FILE_PROVIDER_SUFFIX = ".multipicker.fileprovider"
    private const val OUTPUT_MIME_TYPE = "video/mp4"

    @RequiresApi(18)
    suspend fun export(
            context: Context,
            source: Uri,
            displayName: String?,
            edits: VideoEditorEdits,
            progressListener: VideoEditProgressListener?,
    ): Result {
        val destination = createOutputFile(context, displayName)
        val output = try {
            VideoEditExporter.export(
                    context,
                    VideoEditSpec(
                            sourceUri = source,
                            startUs = edits.startUs,
                            endUs = edits.endUs,
                            crop = edits.crop,
                            rotationDegrees = edits.rotationDegrees,
                            muted = edits.volume.muted,
                            volume = edits.volume.gain,
                            outputFile = destination,
                            speed = edits.speed.speed,
                            changePitch = edits.speed.changePitch
                    ),
                    progressListener
            )
        } catch (throwable: Throwable) {
            // Cancelling or failing leaves nothing worth keeping, and filesDir is never reclaimed.
            destination.parentFile?.deleteRecursively()
            throw throwable
        }
        return Result(
                // DocumentFile cannot stat a file:// URI, so the result must be published through
                // our FileProvider or it stays invisible until the upload completes.
                uri = FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, output.file),
                width = output.width,
                height = output.height,
                size = output.file.length(),
                mimeType = OUTPUT_MIME_TYPE,
                durationMs = output.durationMs,
                audioDropped = output.audioDropped
        )
    }

    /** Always .mp4: a remuxed .3gp mislabelled by its source extension confuses the recipient. */
    private fun createOutputFile(context: Context, displayName: String?): File {
        val directory = File(MediaCache.editedMediaDirectory(context), UUID.randomUUID().toString()).also { it.mkdirs() }
        val baseName = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "video"
        return File(directory, "$baseName.mp4")
    }
}
