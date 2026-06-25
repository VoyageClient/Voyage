/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import org.matrix.android.sdk.api.session.room.model.imagepack.ImagePackUsage
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo

/**
 * Where a pack was sourced from, in MSC2545 suggestion-priority order (lower ordinal = higher priority).
 */
enum class ImagePackSource {
    ACCOUNT,
    GLOBAL_ROOM,
    CURRENT_ROOM,
    SPACE,
}

data class ResolvedImagePack(
        val source: ImagePackSource,
        val roomId: String?,
        val stateKey: String?,
        val displayName: String?,
        val avatarUrl: String?,
        val images: List<ResolvedImage>,
        // Whether this pack is active in pickers: the personal pack always is; room/space packs only when
        // explicitly enabled via m.image_pack.rooms. Authoring still lists disabled packs so they can be toggled.
        val enabled: Boolean = true,
)

data class ResolvedImage(
        val shortcode: String,
        val mxcUrl: String,
        val body: String?,
        val info: ImageInfo?,
        val usages: Set<String>,
        val packDisplayName: String?,
        // From the user's personal (account) pack rather than a room pack — the UI labels it specially.
        val personal: Boolean = false,
)

/**
 * Filters whole packs down to the images usable for a given purpose, dropping packs left empty.
 */
object ImagePackUsageFilter {
    fun stickerPacks(packs: List<ResolvedImagePack>): List<ResolvedImagePack> = filterBy(packs, ImagePackUsage.STICKER)
    fun emoticonPacks(packs: List<ResolvedImagePack>): List<ResolvedImagePack> = filterBy(packs, ImagePackUsage.EMOTICON)

    private fun filterBy(packs: List<ResolvedImagePack>, usage: String): List<ResolvedImagePack> {
        return packs.mapNotNull { pack ->
            val images = pack.images.filter { usage in it.usages }
            if (images.isEmpty()) null else pack.copy(images = images)
        }
    }
}
