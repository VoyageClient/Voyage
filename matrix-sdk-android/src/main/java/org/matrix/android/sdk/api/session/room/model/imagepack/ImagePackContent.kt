/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.imagepack

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.room.model.message.ImageInfo

/**
 * MSC2545 image pack, as stored in a `m.room.image_pack` / `im.ponies.room_emotes` state event or in
 * the `im.ponies.user_emotes` account data event.
 */
@JsonClass(generateAdapter = true)
data class ImagePackContent(
        @Json(name = "images") val images: Map<String, ImagePackImage>? = null,
        @Json(name = "pack") val pack: ImagePackMeta? = null,
        // Legacy MSC2545 image maps kept for read compatibility. `emoticons` is the previous name of `images`
        // (same shape); the older `short` maps a (possibly colon-wrapped) shortcode straight to an mxc URL.
        @Json(name = "emoticons") val emoticons: Map<String, ImagePackImage>? = null,
        @Json(name = "short") val shortLegacy: Map<String, String>? = null,
)

/**
 * The pack's images in display order, reading the current `images` key first and falling back to the legacy
 * `emoticons` / `short` keys so packs authored by older clients still load. Sorted by MSC4389 order (missing
 * = 0); servers return the map with its keys sorted, so ties stay alphabetical.
 */
fun ImagePackContent.effectiveImages(): Map<String, ImagePackImage>? {
    val raw = images?.takeIf { it.isNotEmpty() }
            ?: emoticons?.takeIf { it.isNotEmpty() }
            ?: shortLegacy?.takeIf { it.isNotEmpty() }
                    ?.entries
                    ?.associate { (key, url) -> key.trim(':') to ImagePackImage(url = url) }
            ?: return null
    if (raw.values.none { it.effectiveOrder != 0L }) return raw
    return raw.entries.sortedBy { it.value.effectiveOrder }.associateTo(LinkedHashMap()) { it.key to it.value }
}

@JsonClass(generateAdapter = true)
data class ImagePackImage(
        @Json(name = "url") val url: String,
        @Json(name = "body") val body: String? = null,
        @Json(name = "info") val info: ImageInfo? = null,
        // Legacy per-image usage (kept for read compatibility); the stable schema only carries usage on the pack.
        @Json(name = "usage") val usage: List<String>? = null,
        // MSC4389 position within the pack; written under both keys, stable read first.
        @Json(name = "order") val order: Long? = null,
        @Json(name = "fi.mau.msc4389.order") val orderUnstable: Long? = null,
) {
    val effectiveOrder: Long get() = order ?: orderUnstable ?: 0L

    fun withOrder(position: Long): ImagePackImage = copy(order = position, orderUnstable = position)
}

/** Numbers [images] 1..n in their iteration order (MSC4389). */
fun Map<String, ImagePackImage>.withSequentialOrder(): Map<String, ImagePackImage> {
    var position = 0L
    return entries.associateTo(LinkedHashMap()) { (shortcode, image) -> shortcode to image.withOrder(++position) }
}

@JsonClass(generateAdapter = true)
data class ImagePackMeta(
        @Json(name = "display_name") val displayName: String? = null,
        @Json(name = "avatar_url") val avatarUrl: String? = null,
        @Json(name = "usage") val usage: List<String>? = null,
        // Other pack fields (e.g. attribution) are intentionally not modelled: they are preserved as opaque
        // pass-through on save rather than read, so unknown keys are never dropped.
)

object ImagePackUsage {
    const val EMOTICON = "emoticon"
    const val STICKER = "sticker"
}

/**
 * Effective usages for an image: the pack's usage wins when it restricts to a type; when the pack allows
 * everything (absent/empty usage), a legacy im.ponies pack ([allowPerImage]) may narrow per image via the
 * image's own usage; otherwise the image is usable everywhere. Per-image usage is not part of the current
 * MSC2545 schema, hence the gate.
 */
fun ImagePackImage.resolveUsages(pack: ImagePackMeta?, allowPerImage: Boolean): Set<String> {
    pack?.usage?.takeIf { it.isNotEmpty() }?.let { return it.toSet() }
    if (allowPerImage) usage?.takeIf { it.isNotEmpty() }?.let { return it.toSet() }
    return setOf(ImagePackUsage.EMOTICON, ImagePackUsage.STICKER)
}
