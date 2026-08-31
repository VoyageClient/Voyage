/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.picker

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.imagepack.ResolvedImage
import im.vector.app.features.imagepack.ResolvedImagePack
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

class StickerPickerController @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val stringProvider: StringProvider,
        private val vectorPreferences: VectorPreferences,
) : TypedEpoxyController<StickerPickerController.Data>() {

    data class Data(
            val frequentlyUsed: List<ResolvedImage>,
            val packs: List<ResolvedImagePack>,
            val searchQuery: String = "",
    )

    interface Listener {
        fun onStickerClicked(image: ResolvedImage)
    }

    var listener: Listener? = null

    fun packTitle(pack: ResolvedImagePack): String = pack.displayName?.takeIf { it.isNotBlank() }
            ?: if (pack.source == im.vector.app.features.imagepack.ImagePackSource.ACCOUNT) {
                stringProvider.getString(CommonStrings.image_pack_personal_pack)
            } else {
                ""
            }

    override fun buildModels(data: Data?) {
        val host = this
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
        val query = data?.searchQuery.orEmpty().trim()
        val allPacks = data?.packs?.filter { it.images.isNotEmpty() }.orEmpty()
        val allFrequent = data?.frequentlyUsed.orEmpty()

        if (allPacks.isEmpty() && allFrequent.isEmpty()) {
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(CommonStrings.sticker_picker_empty).toEpoxyCharSequence())
            }
            return
        }

        val packs = if (query.isEmpty()) allPacks else allPacks.mapNotNull { pack ->
            pack.images.filter { it.matchesQuery(query) }.takeIf { it.isNotEmpty() }?.let { pack.copy(images = it) }
        }
        val frequent = if (query.isEmpty()) allFrequent else allFrequent.filter { it.matchesQuery(query) }

        if (packs.isEmpty() && frequent.isEmpty()) {
            genericFooterItem {
                id("search_empty")
                text(host.stringProvider.getString(CommonStrings.search_no_results).toEpoxyCharSequence())
            }
            return
        }

        if (frequent.isNotEmpty()) {
            stickerPackHeaderItem {
                id("header_frequent")
                title(host.stringProvider.getString(CommonStrings.sticker_picker_frequently_used))
                iconRes(R.drawable.ic_clock)
                spanSizeOverride { totalSpanCount, _, _ -> totalSpanCount }
            }
            frequent.forEach { image ->
                stickerItem {
                    id("frequent_${image.mxcUrl}")
                    // Full (original) file: it animates, and server thumbnails flatten transparency.
                    resolvedUrl(contentUrlResolver?.resolveFullSize(image.mxcUrl))
                    contentDescription(image.body ?: image.shortcode)
                    autoplay(host.vectorPreferences.autoplayAnimatedImages())
                    onClickListener { host.listener?.onStickerClicked(image) }
                }
            }
        }

        packs.forEach { pack ->
            val packKey = "${pack.source}_${pack.roomId}_${pack.stateKey}"
            stickerPackHeaderItem {
                id("header_$packKey")
                title(host.packTitle(pack))
                // Header spans the whole row; stickers take one column each (see spanCount on the grid).
                spanSizeOverride { totalSpanCount, _, _ -> totalSpanCount }
            }
            pack.images.forEach { image ->
                stickerItem {
                    id("${packKey}_${image.shortcode}")
                    // Full (original) file: it animates, and server thumbnails flatten transparency.
                    resolvedUrl(contentUrlResolver?.resolveFullSize(image.mxcUrl))
                    contentDescription(image.body ?: image.shortcode)
                    autoplay(host.vectorPreferences.autoplayAnimatedImages())
                    onClickListener { host.listener?.onStickerClicked(image) }
                }
            }
        }
    }
}

internal fun ResolvedImage.matchesQuery(query: String): Boolean {
    return shortcode.contains(query, ignoreCase = true) ||
            body?.contains(query, ignoreCase = true) == true ||
            packDisplayName?.contains(query, ignoreCase = true) == true
}
