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
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import javax.inject.Inject

class ImagePackListController @Inject constructor(
        private val stringProvider: StringProvider,
        private val activeSessionHolder: ActiveSessionHolder,
) : TypedEpoxyController<ImagePackListController.Data>() {

    data class Data(
            val packs: List<ManagedPack>,
            val canCreateRoomPack: Boolean,
            val hasAccountPack: Boolean,
            val inRoom: Boolean,
    )

    interface Listener {
        fun onPackClicked(pack: ManagedPack)
        fun onGlobalToggled(pack: ManagedPack, enabled: Boolean)
        fun onCreateAccountPack()
        fun onCreateRoomPack()
        fun onImportPack()
    }

    var listener: Listener? = null

    override fun buildModels(data: Data?) {
        data ?: return
        val host = this
        val contentUrlResolver = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()

        val showsCreateOptions = data.inRoom && data.canCreateRoomPack
        if (data.packs.isEmpty() && !showsCreateOptions) {
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(CommonStrings.image_pack_list_empty).toEpoxyCharSequence())
            }
        }

        data.packs.forEachIndexed { index, pack ->
            val key = "${pack.kind}_${pack.roomId}_${pack.stateKey}"
            // Divider only between packs, never above the first or below the last.
            if (index > 0) {
                dividerItem { id("divider_$key") }
            }
            // The single personal account pack always shows as "Personal pack" (it has no editable name).
            // Room packs always resolve a name (their own, else the room's — MSC2545 fallback).
            val title = if (pack.kind == ManagedPackKind.ACCOUNT) {
                stringProvider.getString(CommonStrings.image_pack_personal_pack)
            } else {
                pack.displayName.orEmpty()
            }
            imagePackListItem {
                id(key)
                title(title)
                subtitle(host.subtitleFor(pack))
                // Thumbnail, not full size: pack avatars can be multi-megabyte originals
                resolvedAvatarUrl((pack.avatarUrl ?: pack.firstImageUrl)?.let {
                    contentUrlResolver?.resolveThumbnail(it, AVATAR_THUMBNAIL_SIZE, AVATAR_THUMBNAIL_SIZE, ContentUrlResolver.ThumbnailMethod.SCALE)
                })
                showGlobalSwitch(pack.canToggleGlobal)
                globalEnabled(pack.isGloballyEnabled)
                onGlobalToggled { enabled -> host.listener?.onGlobalToggled(pack, enabled) }
                onClickListener { host.listener?.onPackClicked(pack) }
            }
        }

        if (showsCreateOptions) {
            if (data.packs.isNotEmpty()) dividerItem { id("divider_create_room") }
            imagePackListItem {
                id("create_room")
                title(host.stringProvider.getString(CommonStrings.image_pack_create_room))
                placeholderIconRes(R.drawable.ic_plus)
                onClickListener { host.listener?.onCreateRoomPack() }
            }
            dividerItem { id("divider_import_pack") }
            imagePackListItem {
                id("import_pack")
                title(host.stringProvider.getString(CommonStrings.image_pack_import))
                placeholderIconRes(R.drawable.ic_paperclip)
                onClickListener { host.listener?.onImportPack() }
            }
        }
    }

    private fun subtitleFor(pack: ManagedPack): String {
        val count = stringProvider.getString(CommonStrings.image_pack_image_count, pack.imageCount)
        // In the per-room screen every pack is from this room, so the source label is redundant — show
        // only the image count. The settings list still shows which room a pack comes from.
        if (pack.kind == ManagedPackKind.THIS_ROOM) return count
        val source = pack.roomDisplayName ?: stringProvider.getString(
                when (pack.kind) {
                    ManagedPackKind.ACCOUNT -> CommonStrings.image_pack_source_account
                    ManagedPackKind.THIS_ROOM -> CommonStrings.image_pack_source_this_room
                    ManagedPackKind.SPACE -> CommonStrings.image_pack_source_space
                    ManagedPackKind.GLOBAL -> CommonStrings.image_pack_source_global
                }
        )
        return "$source · $count"
    }

    private companion object {
        // 40dp list avatar at up to ~4x density
        const val AVATAR_THUMBNAIL_SIZE = 160
    }
}
