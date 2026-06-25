/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.emoji

import android.graphics.Typeface
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.EmojiCompatFontProvider
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.autocomplete.AutocompleteClickListener
import javax.inject.Inject

class AutocompleteEmojiController @Inject constructor(
        private val fontProvider: EmojiCompatFontProvider,
        private val activeSessionHolder: ActiveSessionHolder,
) : TypedEpoxyController<List<AutocompleteEmojiData>>() {

    var emojiTypeface: Typeface? = fontProvider.typeface

    private val fontProviderListener = object : EmojiCompatFontProvider.FontProviderListener {
        override fun compatibilityFontUpdate(typeface: Typeface?) {
            emojiTypeface = typeface
        }
    }

    var listener: AutocompleteClickListener<AutocompleteEmojiData>? = null

    override fun buildModels(data: List<AutocompleteEmojiData>?) {
        if (data.isNullOrEmpty()) {
            return
        }
        val host = this
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
        data
                .take(MAX)
                .forEachIndexed { index, item ->
                    when (item) {
                        is AutocompleteEmojiData.Emoji -> {
                            autocompleteEmojiItem {
                                id("emoji_${item.emojiItem.name}_$index")
                                emojiItem(item.emojiItem)
                                emojiTypeFace(host.emojiTypeface)
                                onClickListener { host.listener?.onItemClick(item) }
                            }
                        }
                        is AutocompleteEmojiData.Emote -> {
                            autocompleteEmoteItem {
                                id("emote_${item.image.shortcode}_$index")
                                image(item.image)
                                resolvedUrl(contentUrlResolver?.resolveThumbnail(item.image.mxcUrl, 96, 96, org.matrix.android.sdk.api.session.content.ContentUrlResolver.ThumbnailMethod.SCALE))
                                onClickListener { host.listener?.onItemClick(item) }
                            }
                        }
                    }
                }

        if (data.size > MAX) {
            autocompleteMoreResultItem {
                id("more_result")
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        fontProvider.addListener(fontProviderListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        fontProvider.removeListener(fontProviderListener)
    }

    companion object {
        const val MAX = 50
    }
}
