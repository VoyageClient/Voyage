/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.settings

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.epoxy.profiles.buildProfileAction
import im.vector.app.core.epoxy.profiles.buildProfileSection
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.verticalMarginItem
import im.vector.app.core.utils.DimensionConverter
import im.vector.app.features.form.formEditTextItem
import im.vector.app.features.form.formEditableRoomHeaderItem
import im.vector.app.features.form.formSwitchItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.BannerRenderer
import im.vector.app.features.home.room.detail.timeline.format.RoomHistoryVisibilityFormatter
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.util.toDisplayMatrixItem
import javax.inject.Inject

class RoomSettingsController @Inject constructor(
        private val stringProvider: StringProvider,
        private val avatarRenderer: AvatarRenderer,
        private val bannerRenderer: BannerRenderer,
        private val dimensionConverter: DimensionConverter,
        private val roomHistoryVisibilityFormatter: RoomHistoryVisibilityFormatter,
        private val vectorPreferences: VectorPreferences
) : TypedEpoxyController<RoomSettingsViewState>() {

    interface Callback {
        // Delete the avatar, or cancel an avatar change
        fun onAvatarDelete()
        fun onAvatarChange()

        // Delete the banner, or cancel a banner change
        fun onBannerDelete()
        fun onBannerChange()
        fun onNameChanged(name: String)
        fun onTopicChanged(topic: String)
        fun onHistoryVisibilityClicked()
        fun onJoinRuleClicked()
        fun onToggleGuestAccess()
    }

    var callback: Callback? = null

    override fun buildModels(data: RoomSettingsViewState?) {
        val roomSummary = data?.roomSummary?.invoke() ?: return
        val host = this
        val roomAvatarUrl = data.currentRoomAvatarUrl?.takeIf { it.isNotEmpty() }

        formEditableRoomHeaderItem {
            id("header")
            avatarEnabled(data.actionPermissions.canChangeAvatar)
            bannerEnabled(data.actionPermissions.canChangeBanner)
            bannerRenderer(host.bannerRenderer)
            when (val avatarAction = data.avatarAction) {
                RoomSettingsViewState.AvatarAction.None -> {
                    // Only the room's own m.room.avatar: editing must not present a DM's derived peer avatar as the room's.
                    avatarRenderer(host.avatarRenderer)
                    matrixItem(roomSummary.toDisplayMatrixItem().updateAvatar(roomAvatarUrl))
                    hasRoomAvatar(roomAvatarUrl != null)
                }
                RoomSettingsViewState.AvatarAction.DeleteAvatar -> {
                    avatarRenderer(host.avatarRenderer)
                    matrixItem(roomSummary.toDisplayMatrixItem().updateAvatar(null))
                }
                is RoomSettingsViewState.AvatarAction.UpdateAvatar -> avatarImageUri(avatarAction.newAvatarUri)
            }
            when (val bannerAction = data.bannerAction) {
                RoomSettingsViewState.BannerAction.None -> bannerMxcUrl(data.currentRoomBannerUrl)
                RoomSettingsViewState.BannerAction.DeleteBanner -> {
                    bannerMxcUrl(null)
                    bannerImageUri(null)
                }
                is RoomSettingsViewState.BannerAction.UpdateBanner -> bannerImageUri(bannerAction.newBannerUri)
            }
            avatarClickListener { host.callback?.onAvatarChange() }
            avatarDeleteListener { host.callback?.onAvatarDelete() }
            bannerClickListener { host.callback?.onBannerChange() }
            bannerDeleteListener { host.callback?.onBannerDelete() }
        }

        buildProfileSection(
                stringProvider.getString(CommonStrings.settings)
        )

        verticalMarginItem {
            id("margin")
            heightInPx(host.dimensionConverter.dpToPx(16))
        }

        formEditTextItem {
            id("name")
            enabled(data.actionPermissions.canChangeName)
            value(data.newName ?: roomSummary.displayName)
            hint(host.stringProvider.getString(CommonStrings.room_settings_name_hint))
            autoCapitalize(true)

            onTextChange { text ->
                host.callback?.onNameChanged(text)
            }
        }
        formEditTextItem {
            id("topic")
            enabled(data.actionPermissions.canChangeTopic)
            value(data.newTopic ?: roomSummary.topic)
            singleLine(false)
            hint(host.stringProvider.getString(CommonStrings.room_settings_topic_hint))

            onTextChange { text ->
                host.callback?.onTopicChanged(text)
            }
        }
        dividerItem {
            id("topicDivider")
        }
        buildProfileAction(
                id = "historyReadability",
                title = stringProvider.getString(CommonStrings.room_settings_room_read_history_rules_pref_title),
                subtitle = roomHistoryVisibilityFormatter.getSetting(data.newHistoryVisibility ?: data.currentHistoryVisibility),
                divider = true,
                editable = data.actionPermissions.canChangeHistoryVisibility,
                action = { if (data.actionPermissions.canChangeHistoryVisibility) callback?.onHistoryVisibilityClicked() }
        )

        buildProfileAction(
                id = "joinRule",
                title = stringProvider.getString(CommonStrings.room_settings_room_access_title),
                subtitle = data.getJoinRuleWording(stringProvider),
                divider = true,
                editable = data.actionPermissions.canChangeJoinRule,
                action = { if (data.actionPermissions.canChangeJoinRule) callback?.onJoinRuleClicked() }
        )

        val isPublic = (data.newRoomJoinRules.newJoinRules ?: data.currentRoomJoinRules) == RoomJoinRules.PUBLIC
        if (vectorPreferences.developerMode() && isPublic) {
            val guestAccess = data.newRoomJoinRules.newGuestAccess ?: data.currentGuestAccess
            // add guest access option?
            formSwitchItem {
                id("guest_access")
                title(host.stringProvider.getString(CommonStrings.room_settings_guest_access_title))
                switchChecked(guestAccess == GuestAccess.CanJoin)
                listener {
                    host.callback?.onToggleGuestAccess()
                }
            }
            dividerItem {
                id("guestAccessDivider")
            }
        }
    }
}
