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
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

class StickerPickerController @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val stringProvider: StringProvider,
) : TypedEpoxyController<StickerPickerController.Data>() {

    data class Data(
            val frequentlyUsed: List<ResolvedImage>,
            val packs: List<ResolvedImagePack>,
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
        val packs = data?.packs?.filter { it.images.isNotEmpty() }.orEmpty()
        val frequent = data?.frequentlyUsed.orEmpty()

        if (packs.isEmpty() && frequent.isEmpty()) {
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(CommonStrings.sticker_picker_empty).toEpoxyCharSequence())
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
                    onClickListener { host.listener?.onStickerClicked(image) }
                }
            }
        }

        packs.forEachIndexed { packIndex, pack ->
            stickerPackHeaderItem {
                id("header_$packIndex")
                title(host.packTitle(pack))
                // Header spans the whole row; stickers take one column each (see spanCount on the grid).
                spanSizeOverride { totalSpanCount, _, _ -> totalSpanCount }
            }
            pack.images.forEach { image ->
                stickerItem {
                    id("${packIndex}_${image.shortcode}")
                    // Full (original) file: it animates, and server thumbnails flatten transparency.
                    resolvedUrl(contentUrlResolver?.resolveFullSize(image.mxcUrl))
                    contentDescription(image.body ?: image.shortcode)
                    onClickListener { host.listener?.onStickerClicked(image) }
                }
            }
        }
    }
}
