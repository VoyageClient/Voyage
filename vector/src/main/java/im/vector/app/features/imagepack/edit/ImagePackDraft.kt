/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.content.Context
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.File
import java.util.UUID

/** An image held on the device until the pack is applied. */
@Parcelize
data class DraftImage(
        val path: String,
        val shortcode: String,
        val mimeType: String,
        val emoticon: Boolean = true,
        val sticker: Boolean = true,
        // False for already-final encodes (converted Telegram stickers).
        val compress: Boolean = true,
        val width: Int = 0,
        val height: Int = 0,
        val telegramUniqueId: String? = null,
        // Position in the Telegram set, for placing re-added stickers.
        val telegramIndex: Int = -1,
) : Parcelable {
    val file: File get() = File(path)
}

/**
 * Imported content handed to the editor. Nothing in it is on the server yet; the editor uploads it on Apply
 * and deletes [dir] when it closes.
 */
@Parcelize
data class PackDraft(
        val dir: String,
        val images: List<DraftImage>,
        // Name, avatar and usage only apply to a pack the draft creates.
        val packName: String? = null,
        val avatar: DraftImage? = null,
        val usage: List<String>? = null,
        // Entries differ in usage, which only the legacy im.ponies schema can express.
        val perImageUsage: Boolean = false,
        val telegramSet: String? = null,
        // The pack's existing marker: file_unique_id → mxc url.
        val telegramStickers: Map<String, String> = emptyMap(),
        // The set's file_unique_ids in Telegram order.
        val telegramOrder: List<String> = emptyList(),
) : Parcelable

internal fun newDraftDir(context: Context): File =
        File(context.cacheDir, "image_pack_draft_${UUID.randomUUID()}").apply { mkdirs() }
