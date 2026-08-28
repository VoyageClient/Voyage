/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.create

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import im.vector.app.core.epoxy.TextListener
import im.vector.app.core.epoxy.profiles.buildProfileAction
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.discovery.settingsSectionTitleItem
import im.vector.app.features.form.formEditTextItem
import im.vector.app.features.form.formEditableSquareAvatarItem
import im.vector.app.features.form.formMultiLineEditTextItem
import im.vector.app.features.form.formSwitchItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.roomdirectory.createroom.AdvancedRoomOptionsListener
import im.vector.app.features.roomdirectory.createroom.RoomAliasErrorFormatter
import im.vector.app.features.roomdirectory.createroom.buildAdvancedRoomOptions
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.MatrixConstants
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.room.alias.RoomAliasError
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject

class SpaceDetailEpoxyController @Inject constructor(
        private val stringProvider: StringProvider,
        private val avatarRenderer: AvatarRenderer,
        private val roomAliasErrorFormatter: RoomAliasErrorFormatter
) : TypedEpoxyController<CreateSpaceState>() {

    var listener: Listener? = null

    /**
     * Alias text can be automatically set when changing the room name,
     * We have to be able to make a difference between a programming change versus
     * a user change.
     */
    var aliasTextIsFocused = false
    private val aliasTextWatcher: TextListener = {
        if (aliasTextIsFocused) {
            listener?.setAliasLocalPart(it)
        }
    }

    override fun buildModels(data: CreateSpaceState?) {
        val host = this
        genericFooterItem {
            id("info_help")
            // Wording is not specific to a private space, and this one is already translated.
            text(host.stringProvider.getString(CommonStrings.create_spaces_details_private_header).toEpoxyCharSequence())
        }

        formEditableSquareAvatarItem {
            id("avatar")
            enabled(true)
            imageUri(data?.avatarUri)
            avatarRenderer(host.avatarRenderer)
            matrixItem(data?.name?.let { MatrixItem.SpaceItem("!", it, null).takeIf { !it.displayName.isNullOrBlank() } })
            clickListener { host.listener?.onAvatarChange() }
            deleteListener { host.listener?.onAvatarDelete() }
        }

        formEditTextItem {
            id("name")
            enabled(true)
            value(data?.name)
            hint(host.stringProvider.getString(CommonStrings.create_room_name_hint))
            errorMessage(data?.nameInlineError)
            onTextChange { text ->
                host.listener?.onNameChange(text)
            }
        }

        formMultiLineEditTextItem {
            id("topic")
            enabled(true)
            value(data?.topic)
            hint(host.stringProvider.getString(CommonStrings.create_space_topic_hint))
            textSizeSp(16)
            onTextChange { text ->
                host.listener?.onTopicChange(text)
            }
        }

        settingsSectionTitleItem {
            id("accessSection")
            titleResId(CommonStrings.room_settings_space_access_title)
        }

        buildProfileAction(
                id = "joinRule",
                title = host.joinRuleTitle(data?.joinRule),
                subtitle = host.joinRuleSubtitle(data?.joinRule),
                divider = false,
                editable = true,
                action = { host.listener?.selectJoinRule() }
        )

        if (data?.isPublic == true) {
            formEditTextItem {
                id("alias")
                enabled(true)
                forceUpdateValue(!data.aliasManuallyModified)
                value(data.aliasLocalPart)
                hint(host.stringProvider.getString(CommonStrings.create_space_alias_hint))
                suffixText(":" + data.homeServerName)
                prefixText("#")
                maxLength(MatrixConstants.maxAliasLocalPartLength(data.homeServerName))
                onFocusChange { hasFocus ->
                    host.aliasTextIsFocused = hasFocus
                }
                errorMessage(
                        host.roomAliasErrorFormatter.format(
                                (((data.aliasVerificationTask as? Fail)?.error) as? RoomAliasError)
                        )
                )
                onTextChange(host.aliasTextWatcher)
            }
        } else {
            formSwitchItem {
                id("encryption")
                enabled(true)
                title(host.stringProvider.getString(CommonStrings.create_room_encryption_title))
                summary(host.stringProvider.getString(CommonStrings.create_space_encryption_description))
                switchChecked(data?.isEncrypted.orFalse())
                listener { value -> host.listener?.setIsEncrypted(value) }
            }
        }

        if (data != null) {
            buildAdvancedRoomOptions(
                    options = data,
                    stringProvider = stringProvider,
                    homeServerName = data.homeServerName,
                    enabled = true,
                    listener = listener,
            )
        }
    }

    private fun joinRuleTitle(joinRule: RoomJoinRules?): String {
        return stringProvider.getString(
                when (joinRule) {
                    RoomJoinRules.PUBLIC -> CommonStrings.room_settings_room_access_public_title
                    RoomJoinRules.KNOCK -> CommonStrings.room_settings_room_access_knock_title
                    else -> CommonStrings.room_settings_room_access_private_title
                }
        )
    }

    private fun joinRuleSubtitle(joinRule: RoomJoinRules?): String {
        return stringProvider.getString(
                when (joinRule) {
                    RoomJoinRules.PUBLIC -> CommonStrings.room_settings_space_access_public_description
                    RoomJoinRules.KNOCK -> CommonStrings.room_settings_space_access_knock_description
                    else -> CommonStrings.room_settings_room_access_private_description
                }
        )
    }

    interface Listener : AdvancedRoomOptionsListener {
        fun onAvatarDelete()
        fun onAvatarChange()
        fun onNameChange(newName: String)
        fun onTopicChange(newTopic: String)
        fun setAliasLocalPart(aliasLocalPart: String)
        fun selectJoinRule()
        fun setIsEncrypted(isEncrypted: Boolean)
    }
}
