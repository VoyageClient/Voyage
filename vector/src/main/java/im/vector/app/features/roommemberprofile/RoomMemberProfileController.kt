/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import android.content.Context
import android.graphics.Color
import androidx.annotation.StringRes
import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.expandableTextItem
import im.vector.app.core.epoxy.profiles.buildProfileAction
import im.vector.app.core.epoxy.profiles.buildProfileSection
import im.vector.app.core.epoxy.profiles.profileSectionActionItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.colorpicker.ProfileColorPickerDialogFragment
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.form.formSwitchItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.app.features.home.room.detail.timeline.tools.formatProfileBio
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeProvider
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.profile.UserBio
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.powerlevels.UserPowerLevel
import org.matrix.android.sdk.api.util.MatrixItem
import javax.inject.Inject

class RoomMemberProfileController @Inject constructor(
        private val stringProvider: StringProvider,
        private val session: Session,
        private val vectorPreferences: VectorPreferences,
        private val avatarRenderer: AvatarRenderer,
        private val matrixItemColorProvider: MatrixItemColorProvider,
        private val themeProvider: ThemeProvider,
        private val context: Context,
) : TypedEpoxyController<RoomMemberProfileViewState>() {

    var callback: Callback? = null

    // Persisted here (not in the recreated epoxy model) so the bio stays expanded across rebuilds.
    private var isBioExpanded = false
    private var bioCacheKey: UserBio? = null
    private var bioCacheValue: CharSequence? = null
    private var noteCacheKey: UserBio? = null
    private var noteCacheValue: CharSequence? = null

    // Note edit state lives here (not in the recycled view) so scrolling the note off screen
    // and back resumes the edit; the fragment reads it to save a pending draft on pause.
    var personalNoteEditing = false
        private set
    var personalNoteDraft: String? = null
        private set
    var personalNoteSelection: Pair<Int, Int>? = null
        private set

    fun clearPersonalNoteEdit() {
        personalNoteEditing = false
        personalNoteDraft = null
        personalNoteSelection = null
    }

    /** Draft/selection updates typed into the fragment's off-list proxy input while the editor is recycled. */
    fun stashPersonalNoteEdit(draft: String, selection: Pair<Int, Int>) {
        if (!personalNoteEditing) return
        personalNoteDraft = draft
        personalNoteSelection = selection
    }

    interface Callback {
        fun onPersonalNoteChanged(note: String)
        fun onPersonalNoteUnlockClicked()
        fun onPersonalNoteEditorDetached()
        fun onMutualRoomsClicked()
        fun onOverrideDisplayNameClicked()
        fun onOverrideAvatarClicked()
        fun onResetProfileOverridesClicked()
        fun onIgnoreClicked()
        fun onTapVerify()
        fun onShowDeviceList()
        fun onShowDeviceListNoCrossSigning()
        fun onOpenDmClicked()
        fun onOverrideColorClicked(theme: ProfileColorPickerDialogFragment.Theme)
        fun onProfileColorPerThemeChanged(perTheme: Boolean)
        fun onJumpToReadReceiptClicked()
        fun onMentionClicked()
        fun onEditPowerLevel(userPowerLevel: UserPowerLevel.Value)
        fun onKickClicked(isSpace: Boolean)
        fun onRedactAllClicked()
        fun onBanClicked(isSpace: Boolean, isUserBanned: Boolean)
        fun onCancelInviteClicked()
        fun onInviteClicked()
        fun onViewSourceClicked()
        fun onViewProfileSourceClicked()
    }

    override fun buildModels(data: RoomMemberProfileViewState?) {
        if (data?.userMatrixItem?.invoke() == null) {
            return
        }
        buildBiography(data)
        buildPersonalNoteSection(data)
        if (data.showAsMember) {
            buildRoomMemberActions(data)
        } else {
            buildUserActions(data)
        }
    }

    // The profile dict is fetched for any user, member of this room or not (e.g. reached via /whois).
    private fun canViewProfileSource(state: RoomMemberProfileViewState) = vectorPreferences.developerMode() && state.profileJson != null

    private fun buildProfileSourceAction(state: RoomMemberProfileViewState) {
        if (!canViewProfileSource(state)) return
        buildProfileAction(
                id = "view_profile_source",
                editable = false,
                divider = true,
                title = stringProvider.getString(CommonStrings.room_member_view_profile_source),
                action = { callback?.onViewProfileSourceClicked() }
        )
    }

    private fun buildBiography(state: RoomMemberProfileViewState) {
        val host = this
        val bio = state.bio?.takeIf { it.body.isNotBlank() } ?: return
        buildProfileSection(stringProvider.getString(CommonStrings.settings_biography))
        expandableTextItem {
            id("biography")
            content(host.formattedBio(bio))
            maxLines(3)
            expanded(host.isBioExpanded)
            onExpandedChange { host.isBioExpanded = it }
            movementMethod(createLinkMovementMethod(null))
        }
    }

    // Rendering allocates a fresh Spannable, so cache it: an unstable content attribute rebinds the item
    // on every state emission, which flickers the expand state (same reason as the room topic).
    private fun formattedBio(bio: UserBio): CharSequence {
        bioCacheValue?.let { if (bioCacheKey == bio) return it }
        return bio.body.formatProfileBio(bio.formattedBody).also {
            bioCacheKey = bio
            bioCacheValue = it
        }
    }

    private fun buildPersonalNoteSection(state: RoomMemberProfileViewState) {
        val host = this
        buildProfileSection(stringProvider.getString(CommonStrings.personal_note_section))
        if (state.personalNoteLocked) {
            buildProfileAction(
                    id = "personal_note_unlock",
                    editable = false,
                    divider = false,
                    title = stringProvider.getString(CommonStrings.personal_note_unlock),
                    subtitle = stringProvider.getString(CommonStrings.personal_note_locked_hint),
                    action = { callback?.onPersonalNoteUnlockClicked() }
            )
        } else {
            val note = state.personalNote
            personalNoteItem {
                id("personal_note")
                noteSource(note?.body.orEmpty())
                renderedNote(note?.let { host.formattedNote(it) })
                generation(state.personalNoteGeneration)
                editingProvider { host.personalNoteEditing }
                draftProvider { host.personalNoteDraft }
                selectionProvider { host.personalNoteSelection }
                onSelectionStashed { start, end -> host.personalNoteSelection = start to end }
                movementMethod(createLinkMovementMethod(null))
                onNoteChanged { host.callback?.onPersonalNoteChanged(it) }
                onEditingChanged { editing ->
                    host.personalNoteEditing = editing
                    if (!editing) {
                        host.personalNoteDraft = null
                        host.personalNoteSelection = null
                    }
                }
                onDraftChanged { host.personalNoteDraft = it }
                onDetachedWhileEditing { host.callback?.onPersonalNoteEditorDetached() }
            }
        }
    }

    private fun formattedNote(note: UserBio): CharSequence {
        noteCacheValue?.let { if (noteCacheKey == note) return it }
        return note.body.formatProfileBio(note.formattedBody).also {
            noteCacheKey = note
            noteCacheValue = it
        }
    }

    private fun buildUserActions(state: RoomMemberProfileViewState) {
        val ignoreActionTitle = state.buildIgnoreActionTitle()
        if (ignoreActionTitle == null && state.isMine && !canViewProfileSource(state)) return
        // More
        buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_more))
        if (!state.isMine) {
            buildProfileAction(
                    id = "direct",
                    editable = false,
                    title = stringProvider.getString(CommonStrings.room_member_open_or_create_dm),
                    action = { callback?.onOpenDmClicked() }
            )
        }
        buildMutualRoomsAction(state)
        buildProfileSourceAction(state)
        if (ignoreActionTitle != null) {
            buildProfileAction(
                    id = "ignore",
                    title = ignoreActionTitle,
                    destructive = true,
                    editable = false,
                    divider = false,
                    action = { callback?.onIgnoreClicked() }
            )
        }
        buildPersonalizationSection(state)
    }

    private fun buildRoomMemberActions(state: RoomMemberProfileViewState) {
        if (!state.isSpace) {
            buildSecuritySection(state)
        }
        buildMoreSection(state)
        buildPersonalizationSection(state)
        buildAdminSection(state)
    }

    private fun buildPersonalizationSection(state: RoomMemberProfileViewState) {
        if (state.isMine || state.isHistoricalOrWatchedRoom) return
        val host = this
        profileSectionActionItem {
            id("section_personalization")
            title(host.stringProvider.getString(CommonStrings.user_personalization_section))
            actionEnabled(state.hasProfileOverrides || state.userColorOverride != null)
            actionClickListener { host.callback?.onResetProfileOverridesClicked() }
        }
        buildProfileAction(
                id = "override_avatar",
                editable = false,
                title = stringProvider.getString(CommonStrings.avatar),
                divider = true,
                accessoryMatrixItem = state.userMatrixItem(),
                avatarRenderer = avatarRenderer,
                action = { callback?.onOverrideAvatarClicked() }
        )
        buildProfileAction(
                id = "override_display_name",
                editable = false,
                title = stringProvider.getString(CommonStrings.settings_display_name),
                subtitle = state.profileOverrideDisplayName ?: state.userMatrixItem()?.getBestName(),
                divider = true,
                action = { callback?.onOverrideDisplayNameClicked() }
        )
        if (state.profileColorSameForThemes) {
            buildProfileColorAction(state, "override_color", CommonStrings.settings_profile_color, ProfileColorPickerDialogFragment.Theme.CURRENT)
        } else {
            buildProfileColorAction(state, "override_color_light", CommonStrings.settings_profile_color_light, ProfileColorPickerDialogFragment.Theme.LIGHT)
            buildProfileColorAction(state, "override_color_dark", CommonStrings.settings_profile_color_dark, ProfileColorPickerDialogFragment.Theme.DARK)
        }
        formSwitchItem {
            id("override_color_per_theme")
            title(host.stringProvider.getString(CommonStrings.settings_profile_color_per_theme))
            switchChecked(!state.profileColorSameForThemes)
            listener { host.callback?.onProfileColorPerThemeChanged(it) }
        }
    }

    private fun buildProfileColorAction(
            state: RoomMemberProfileViewState,
            id: String,
            @StringRes titleRes: Int,
            theme: ProfileColorPickerDialogFragment.Theme,
    ) {
        val light = when (theme) {
            ProfileColorPickerDialogFragment.Theme.LIGHT -> true
            ProfileColorPickerDialogFragment.Theme.DARK -> false
            ProfileColorPickerDialogFragment.Theme.CURRENT -> themeProvider.isLightTheme()
        }
        val item = state.userMatrixItem() ?: MatrixItem.UserItem(state.userId)
        val overrideHex = matrixItemColorProvider.overrideAxis(state.userId, light)
        val hex = overrideHex ?: matrixItemColorProvider.ownColorHex(item, light) ?: matrixItemColorProvider.defaultColorHex(state.userId, light)
        buildProfileAction(
                id = id,
                editable = false,
                title = stringProvider.getString(titleRes),
                subtitle = ProfileColorPickerDialogFragment.describe(context, hex, light, isDefault = overrideHex == null),
                divider = false,
                accessoryColor = Color.parseColor(hex),
                action = { callback?.onOverrideColorClicked(theme) }
        )
    }

    private fun buildMutualRoomsAction(state: RoomMemberProfileViewState) {
        if (state.isMine) return
        buildProfileAction(
                id = "mutual_rooms",
                editable = true,
                divider = true,
                title = stringProvider.getString(CommonStrings.room_member_profile_mutual_rooms),
                action = { callback?.onMutualRoomsClicked() }
        )
    }

    private fun buildSecuritySection(state: RoomMemberProfileViewState) {
        // Security
        val host = this

        if (state.isRoomEncrypted) {
            if (!state.isAlgorithmSupported) {
                // TODO find sensible message to display here
                // For now we just remove the verify actions as well as the Security status
            } else if (state.userMXCrossSigningInfo != null) {
                buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_security))
                // Cross signing is enabled for this user
                if (state.userMXCrossSigningInfo.isTrusted()) {
                    // User is trusted
                    val (icon, titleRes) = if (state.allDevicesAreCrossSignedTrusted) {
                        Pair(R.drawable.ic_shield_trusted, CommonStrings.verification_profile_verified)
                    } else {
                        Pair(R.drawable.ic_shield_warning, CommonStrings.verification_profile_warning)
                    }

                    buildProfileAction(
                            id = "learn_more",
                            title = stringProvider.getString(titleRes),
                            editable = true,
                            icon = icon,
                            tintIcon = false,
                            divider = false,
                            action = { callback?.onShowDeviceList() }
                    )
                } else {
                    // Not trusted, propose to verify
                    if (!state.isMine) {
                        buildProfileAction(
                                id = "learn_more",
                                title = stringProvider.getString(CommonStrings.verification_profile_verify),
                                editable = true,
                                icon = R.drawable.ic_shield_black,
                                divider = false,
                                action = { callback?.onTapVerify() }
                        )
                    } else {
                        buildProfileAction(
                                id = "learn_more",
                                title = stringProvider.getString(CommonStrings.room_profile_section_security_learn_more),
                                editable = false,
                                divider = false,
                                action = { callback?.onShowDeviceListNoCrossSigning() }
                        )
                    }

                    genericFooterItem {
                        id("verify_footer")
                        text(host.stringProvider.getString(CommonStrings.room_profile_encrypted_subtitle).toEpoxyCharSequence())
                        centered(false)
                    }
                }
            } else {
                buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_security))

                buildProfileAction(
                        id = "learn_more",
                        title = stringProvider.getString(CommonStrings.room_profile_section_security_learn_more),
                        editable = false,
                        divider = false,
                        subtitle = stringProvider.getString(CommonStrings.room_profile_encrypted_subtitle),
                        action = { callback?.onShowDeviceListNoCrossSigning() }
                )
            }
        } else {
            buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_security))

            genericFooterItem {
                id("verify_footer_not_encrypted")
                text(host.stringProvider.getString(CommonStrings.room_profile_not_encrypted_subtitle).toEpoxyCharSequence())
                centered(false)
            }
        }
    }

    private fun buildMoreSection(state: RoomMemberProfileViewState) {
        // More
        buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_more))

        if (!state.isMine) {
            buildProfileAction(
                    id = "direct",
                    editable = false,
                    title = stringProvider.getString(CommonStrings.room_member_open_or_create_dm),
                    action = { callback?.onOpenDmClicked() }
            )
        }

        if (vectorPreferences.developerMode()) {
            buildProfileSourceAction(state)
            buildProfileAction(
                    id = "view_membership_source",
                    editable = false,
                    divider = true,
                    title = stringProvider.getString(CommonStrings.room_member_view_membership_source),
                    action = { callback?.onViewSourceClicked() }
            )
        }

        if (!state.isMine) {
            val membership = state.asyncMembership() ?: return

            if (!state.isSpace && state.hasReadReceipt) {
                buildProfileAction(
                        id = "read_receipt",
                        editable = false,
                        title = stringProvider.getString(CommonStrings.room_member_jump_to_read_receipt),
                        action = { callback?.onJumpToReadReceiptClicked() }
                )
            }

            val ignoreActionTitle = state.buildIgnoreActionTitle()
            if (!state.isSpace && !state.isHistoricalOrWatchedRoom) {
                buildProfileAction(
                        id = "mention",
                        title = stringProvider.getString(CommonStrings.room_participants_action_mention),
                        editable = false,
                        divider = true,
                        action = { callback?.onMentionClicked() }
                )
            }

            val canInvite = state.actionPermissions.canInvite

            if (canInvite && (membership == Membership.LEAVE || membership == Membership.KNOCK)) {
                buildProfileAction(
                        id = "invite",
                        title = stringProvider.getString(CommonStrings.room_participants_action_invite),
                        destructive = false,
                        editable = false,
                        divider = true,
                        action = { callback?.onInviteClicked() }
                )
            }
            buildMutualRoomsAction(state)

            if (ignoreActionTitle != null) {
                buildProfileAction(
                        id = "ignore",
                        title = ignoreActionTitle,
                        destructive = true,
                        editable = false,
                        divider = false,
                        action = { callback?.onIgnoreClicked() }
                )
            }
        }

        if (state.isMine) {
            buildProfileAction(
                    id = "redact_all",
                    editable = false,
                    destructive = true,
                    divider = false,
                    title = stringProvider.getString(CommonStrings.room_participants_action_redact_all),
                    action = { callback?.onRedactAllClicked() }
            )
        }
    }

    private fun buildAdminSection(state: RoomMemberProfileViewState) {
        val powerLevelsStr = state.userPowerLevelString() ?: return
        val roomPowerLevels = state.roomPowerLevels ?: return
        val userPowerLevel = roomPowerLevels.getUserPowerLevel(state.userId)
        val myPowerLevel = roomPowerLevels.getUserPowerLevel(session.myUserId)
        if (userPowerLevel !is UserPowerLevel.Value) return
        val membership = state.asyncMembership() ?: return
        // Kick/ban/role can only be applied to someone you outrank. Redacting another user's messages,
        // however, depends only on your own redact power level — not the target's rank — so it must also
        // show for fellow moderators/admins. Self-redaction lives under "More" (see buildMoreSection).
        val hasPowerOverTarget = state.isMine || myPowerLevel > userPowerLevel
        val canKick = hasPowerOverTarget && !state.isMine && state.actionPermissions.canKick
        val canBan = hasPowerOverTarget && !state.isMine && state.actionPermissions.canBan
        val canEditPowerLevel = hasPowerOverTarget && state.actionPermissions.canEditPowerLevel
        val canRedactAll = !state.isMine && state.actionPermissions.canRedact
        if (canKick || canBan || canEditPowerLevel || canRedactAll) {
            buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_admin))
        }
        if (canEditPowerLevel) {
            buildProfileAction(
                    id = "edit_power_level",
                    editable = true,
                    title = stringProvider.getString(CommonStrings.power_level_title),
                    subtitle = powerLevelsStr,
                    divider = canRedactAll || canKick || canBan,
                    editableRes = R.drawable.ic_edit,
                    action = { callback?.onEditPowerLevel(userPowerLevel) }
            )
        }
        if (canRedactAll) {
            buildProfileAction(
                    id = "redact_all",
                    editable = false,
                    destructive = true,
                    divider = canKick || canBan,
                    title = stringProvider.getString(CommonStrings.room_participants_action_redact_all),
                    action = { callback?.onRedactAllClicked() }
            )
        }

        if (canKick) {
            when (membership) {
                Membership.JOIN -> {
                    buildProfileAction(
                            id = "kick",
                            editable = false,
                            divider = canBan,
                            destructive = true,
                            title = stringProvider.getString(CommonStrings.room_participants_action_kick),
                            action = { callback?.onKickClicked(state.isSpace) }
                    )
                }
                Membership.INVITE -> {
                    buildProfileAction(
                            id = "cancel_invite",
                            title = stringProvider.getString(CommonStrings.room_participants_action_cancel_invite),
                            divider = canBan,
                            destructive = true,
                            editable = false,
                            action = { callback?.onCancelInviteClicked() }
                    )
                }
                else -> Unit
            }
        }
        if (canBan) {
            val banActionTitle = if (membership == Membership.BAN) {
                stringProvider.getString(CommonStrings.room_participants_action_unban)
            } else {
                stringProvider.getString(CommonStrings.room_participants_action_ban)
            }
            buildProfileAction(
                    id = "ban",
                    editable = false,
                    destructive = true,
                    title = banActionTitle,
                    action = { callback?.onBanClicked(state.isSpace, membership == Membership.BAN) }
            )
        }
    }

    private fun RoomMemberProfileViewState.buildIgnoreActionTitle(): String? {
        val isIgnored = isIgnored() ?: return null
        return if (isIgnored) {
            stringProvider.getString(CommonStrings.room_participants_action_unignore_title)
        } else {
            stringProvider.getString(CommonStrings.room_participants_action_ignore_title)
        }
    }
}
