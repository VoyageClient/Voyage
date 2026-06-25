/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.epoxy.loadingItem
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import javax.inject.Inject

class ImagePackEditController @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val stringProvider: StringProvider,
) : TypedEpoxyController<List<EditableImage>>() {

    interface Listener {
        fun onDeleteImage(image: EditableImage)
        fun onAddImage()
        // A row's shortcode / usage was edited in place (so the screen can refresh the Apply state).
        fun onImageEdited()
    }

    var listener: Listener? = null
    var editable: Boolean = true
    // Hidden when the pack declares a single usage (the usage is then decided by the pack).
    var showUsageToggles: Boolean = true
    // Shows a spinner where the picked image will land while its upload is in flight.
    var uploading: Boolean = false

    // Current image order as shown (the drag helper reorders the models, not our backing list).
    fun currentOrderedImages(): List<EditableImage> =
            adapter.copyOfModels.filterIsInstance<ImagePackEditItem_>().map { it.image() }

    override fun buildModels(data: List<EditableImage>?) {
        val host = this
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()
        val images = data.orEmpty()

        images.forEach { image ->
            imagePackEditItem {
                // Stable id (object identity) so live shortcode edits don't recreate the row / lose focus.
                id(System.identityHashCode(image).toLong())
                image(image)
                editable(host.editable)
                showUsageToggles(host.showUsageToggles)
                resolvedUrl(contentUrlResolver?.resolveThumbnail(image.mxcUrl, 96, 96, ContentUrlResolver.ThumbnailMethod.SCALE))
                onDeleteClick { host.listener?.onDeleteImage(image) }
                onEdited { host.listener?.onImageEdited() }
            }
        }

        if (uploading) {
            loadingItem {
                id("uploading")
            }
        }

        if (editable) {
            // "Add image" row, mirroring the create-pack row in the image-pack list. Row separators are drawn
            // by an ItemDecoration (not divider models) so reordering never inserts/removes a divider — which
            // jumped the scroll position when dragging an item to the top.
            imagePackListItem {
                id("add_image")
                title(host.stringProvider.getString(CommonStrings.image_pack_add_image))
                placeholderIconRes(R.drawable.ic_plus)
                onClickListener { host.listener?.onAddImage() }
            }
        }
    }
}
