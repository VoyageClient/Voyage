/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.R
import im.vector.app.core.epoxy.expandableTextItem
import im.vector.app.core.epoxy.profiles.buildProfileAction
import im.vector.app.core.epoxy.profiles.buildProfileSection
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.DrawableProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.core.ui.list.genericPositiveButtonItem
import im.vector.app.features.form.formSwitchItem
import im.vector.app.features.home.ShortcutCreator
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.tools.RoomTopicRenderer
import im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod
import im.vector.app.features.home.room.detail.timeline.tools.formatTopic
import im.vector.app.features.settings.VectorPreferences
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import me.gujun.android.span.image
import me.gujun.android.span.span
import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomEncryptionAlgorithm
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.notification.RoomNotificationState
import javax.inject.Inject

class RoomProfileController @Inject constructor(
        private val stringProvider: StringProvider,
        private val colorProvider: ColorProvider,
        private val vectorPreferences: VectorPreferences,
        private val drawableProvider: DrawableProvider,
        private val shortcutCreator: ShortcutCreator,
        private val roomTopicRenderer: RoomTopicRenderer,
) : TypedEpoxyController<RoomProfileViewState>() {

    var callback: Callback? = null

    // Persisted here (not in the recreated epoxy model) so the topic stays expanded across rebuilds.
    private var isTopicExpanded = false

    // formatTopic() allocates a fresh Spannable each build; caching by (topic, formattedTopic, roomId)
    // keeps the epoxy content attribute stable so the item isn't needlessly rebound (which flickered the
    // expand state) and the HTML/markdown render doesn't run on every state emission.
    private var topicCacheKey: Triple<String, String?, String>? = null
    private var topicCacheValue: CharSequence? = null

    private fun formattedTopic(topic: String, formattedTopic: String?, roomId: String, callback: TimelineEventController.UrlClickCallback): CharSequence {
        val key = Triple(topic, formattedTopic, roomId)
        topicCacheValue?.let { if (topicCacheKey == key) return it }
        return topic.formatTopic(roomId, formattedTopic, callback).also {
            topicCacheKey = key
            topicCacheValue = it
        }
    }

    interface Callback {
        fun onLearnMoreClicked()
        fun onEnableEncryptionClicked()
        fun onMemberListClicked()
        fun onKnockRequestsClicked()
        fun onBannedMemberListClicked()
        fun onNotificationsClicked()
        fun onPollHistoryClicked()
        fun onPinnedMessagesClicked()
        fun onUploadsClicked()
        fun onImagePacksClicked()
        fun onPersonalizationClicked()
        fun createShortcut()
        fun onSettingsClicked()
        fun onLeaveRoomClicked()
        fun onRoomAliasesClicked()
        fun onRoomPermissionsClicked()
        fun onRoomAclClicked()
        fun onRoomIdClicked()
        fun onRoomDevToolsClicked()
        fun onUrlInTopicClicked(url: String, title: String): Boolean
        fun onUrlInTopicLongClicked(url: String)
        fun doMigrateToVersion(newVersion: String)
        fun restoreEncryptionState()
        fun setEncryptedToVerifiedDevicesOnly(enabled: Boolean)
        fun openGlobalBlockSettings()
    }

    override fun buildModels(data: RoomProfileViewState?) {
        data ?: return
        val host = this
        val roomSummary = data.roomSummary() ?: return
        // Not joined (world-readable preview, or a frozen kicked/banned room): everything renders
        // read-only; only actions that are meaningless without membership are hidden below.
        val isPreview = roomSummary.membership != Membership.JOIN

        // Topic
        roomSummary
                .topic
                .takeIf { it.isNotEmpty() }
                ?.let {
                    buildProfileSection(stringProvider.getString(CommonStrings.room_settings_topic))
                    val topicCallback = object : TimelineEventController.UrlClickCallback {
                        override fun onUrlClicked(url: String, title: String): Boolean {
                            return host.callback?.onUrlInTopicClicked(url, title) ?: false
                        }

                        override fun onUrlLongClicked(url: String): Boolean {
                            host.callback?.onUrlInTopicLongClicked(url)
                            return true
                        }
                    }
                    expandableTextItem {
                        id("topic")
                        content(host.formattedTopic(it, roomSummary.topicFormatted, roomSummary.roomId, topicCallback))
                        host.roomTopicRenderer.richSegments(it, roomSummary.topicFormatted)?.let { segments ->
                            renderRichContent { container, movement ->
                                host.roomTopicRenderer.renderRich(container, segments, roomSummary.roomId, topicCallback, movement)
                            }
                        }
                        maxLines(2)
                        expandedProvider { host.isTopicExpanded }
                        onExpandedChange { host.isTopicExpanded = it }
                        movementMethod(createLinkMovementMethod(topicCallback))
                    }
                }

        buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_security))

        // Upgrade warning
        val roomVersion = data.roomCreateContent()?.roomVersion
        if (data.canUpgradeRoom &&
                !data.isTombstoned &&
                roomVersion != null &&
                data.isUsingUnstableRoomVersion &&
                data.recommendedRoomVersion != null) {
            genericFooterItem {
                id("version_warning")
                text(host.stringProvider.getString(CommonStrings.room_using_unstable_room_version, roomVersion).toEpoxyCharSequence())
                textColor(host.colorProvider.getColorFromAttribute(com.google.android.material.R.attr.colorError))
                centered(false)
            }

            genericPositiveButtonItem {
                id("migrate_button")
                text(host.stringProvider.getString(CommonStrings.room_upgrade_to_recommended_version))
                buttonClickAction { host.callback?.doMigrateToVersion(data.recommendedRoomVersion) }
            }
        }

        var encryptionMisconfigured = false
        val e2eInfoText = if (roomSummary.isEncrypted) {
            if (roomSummary.roomEncryptionAlgorithm is RoomEncryptionAlgorithm.SupportedAlgorithm) {
                stringProvider.getString(
                        if (roomSummary.isDirect) CommonStrings.direct_room_profile_encrypted_subtitle
                        else CommonStrings.room_profile_encrypted_subtitle
                )
            } else {
                encryptionMisconfigured = true
                buildString {
                    append(stringProvider.getString(CommonStrings.encryption_has_been_misconfigured))
                    append(" ")
                    apply {
                        if (!data.canUpdateRoomState) {
                            append(stringProvider.getString(CommonStrings.contact_admin_to_restore_encryption))
                        }
                    }
                }
            }
        } else {
            stringProvider.getString(
                    if (roomSummary.isDirect) CommonStrings.direct_room_profile_not_encrypted_subtitle
                    else CommonStrings.room_profile_not_encrypted_subtitle
            )
        }
        genericFooterItem {
            id("e2e info")
            centered(false)
            compact(true)
            text(
                    span {
                        apply {
                            if (encryptionMisconfigured) {
                                host.drawableProvider.getDrawable(R.drawable.ic_warning_badge)?.let {
                                    image(it, "baseline")
                                }
                                +" "
                            }
                        }
                        +e2eInfoText
                    }.toEpoxyCharSequence()
            )
        }

        if (encryptionMisconfigured && data.canUpdateRoomState) {
            genericPositiveButtonItem {
                id("restore_encryption")
                text(host.stringProvider.getString(CommonStrings.room_profile_section_restore_security))
                iconRes(R.drawable.ic_shield_black_no_border)
                buttonClickAction {
                    host.callback?.restoreEncryptionState()
                }
            }
        }
        buildEncryptionAction(data.actionPermissions, roomSummary)

        if (roomSummary.isEncrypted && !encryptionMisconfigured) {
            data.globalCryptoConfig.invoke()?.let { globalConfig ->
                if (globalConfig.globalBlockUnverifiedDevices) {
                    genericFooterItem {
                        id("globalConfig")
                        centered(false)
                        text(
                                span {
                                    +host.stringProvider.getString(CommonStrings.room_settings_global_block_unverified_info_text)
                                    apply {
                                        if (data.unverifiedDevicesInTheRoom.invoke() == true) {
                                            +"\n"
                                            +host.stringProvider.getString(CommonStrings.some_devices_will_not_be_able_to_decrypt)
                                        }
                                    }
                                }.toEpoxyCharSequence()
                        )
                        itemClickAction {
                            host.callback?.openGlobalBlockSettings()
                        }
                    }
                } else {
                    // per room setting is available
                    val shouldBlockUnverified = data.encryptToVerifiedDeviceOnly.invoke()
                    formSwitchItem {
                        id("send_to_unverified")
                        enabled(shouldBlockUnverified != null)
                        title(host.stringProvider.getString(CommonStrings.encryption_never_send_to_unverified_devices_in_room))

                        switchChecked(shouldBlockUnverified ?: false)

                        apply {
                            if (shouldBlockUnverified == true && data.unverifiedDevicesInTheRoom.invoke() == true) {
                                summary(
                                        host.stringProvider.getString(CommonStrings.some_devices_will_not_be_able_to_decrypt)
                                )
                            } else {
                                summary(null)
                            }
                        }
                        listener { value ->
                            host.callback?.setEncryptedToVerifiedDevicesOnly(value)
                        }
                    }
                }
            }
        }
        buildPreferencesSection(data, isPreview)
        buildPeopleSection(data, roomSummary)
        buildSharedContentSection(data)
        buildAdministrationSection(data, roomSummary, roomVersion)

        if (!isPreview) {
            buildProfileSection("", id = "leave")
            buildProfileAction(
                    id = "leave",
                    title = stringProvider.getString(
                            if (roomSummary.isDirect) {
                                CommonStrings.direct_room_profile_section_more_leave
                            } else {
                                CommonStrings.room_profile_section_more_leave
                            }
                    ),
                    destructive = true,
                    icon = R.drawable.ic_room_actions_leave,
                    editable = false,
                    action = { callback?.onLeaveRoomClicked() }
            )
        }
    }

    private fun buildPeopleSection(data: RoomProfileViewState, roomSummary: RoomSummary) {
        buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_people))
        val joined = roomSummary.joinedMembersCount ?: 0
        val invited = roomSummary.invitedMembersCount ?: 0
        val joinedText = stringProvider.getQuantityString(CommonPlurals.room_profile_members_joined, joined, joined)
        val hasWarning = roomSummary.isEncrypted && roomSummary.roomEncryptionTrustLevel == RoomEncryptionTrustLevel.Warning
        buildProfileAction(
                id = "member_list",
                title = stringProvider.getString(CommonStrings.list_members),
                subtitle = if (invited > 0) {
                    stringProvider.getString(
                            CommonStrings.room_profile_members_summary,
                            joinedText,
                            stringProvider.getQuantityString(CommonPlurals.room_profile_members_invited, invited, invited)
                    )
                } else {
                    joinedText
                },
                icon = R.drawable.ic_room_profile_member_list,
                accessory = R.drawable.ic_shield_warning.takeIf { hasWarning } ?: 0,
                action = { callback?.onMemberListClicked() }
        )

        val knockRequestsCount = data.knockRequests.invoke()?.size ?: 0
        val isKnockable = roomSummary.joinRules == RoomJoinRules.KNOCK || roomSummary.joinRules == RoomJoinRules.KNOCK_RESTRICTED
        if (knockRequestsCount > 0 || (isKnockable && data.actionPermissions.canInvite)) {
            buildProfileAction(
                    id = "knock_requests",
                    title = stringProvider.getString(CommonStrings.room_knock_requests_title),
                    subtitle = if (knockRequestsCount > 0) {
                        stringProvider.getQuantityString(CommonPlurals.room_profile_pending_requests, knockRequestsCount, knockRequestsCount)
                    } else {
                        stringProvider.getString(CommonStrings.room_profile_no_pending_requests)
                    },
                    icon = R.drawable.ic_room_profile_knock_requests,
                    notificationBadge = knockRequestsCount > 0,
                    action = { callback?.onKnockRequestsClicked() }
            )
        }

        val bannedCount = data.bannedMembership.invoke()?.size ?: 0
        if (bannedCount > 0) {
            buildProfileAction(
                    id = "banned_list",
                    title = stringProvider.getString(CommonStrings.room_settings_banned_users_title),
                    subtitle = stringProvider.getQuantityString(CommonPlurals.room_profile_banned_users_count, bannedCount, bannedCount),
                    icon = R.drawable.ic_room_profile_block,
                    action = { callback?.onBannedMemberListClicked() }
            )
        }
    }

    private fun buildSharedContentSection(data: RoomProfileViewState) {
        buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_shared_content))
        buildProfileAction(
                id = "pinned_messages",
                title = stringProvider.getString(CommonStrings.room_profile_section_more_pinned),
                subtitle = if (data.pinnedCount > 0) {
                    stringProvider.getQuantityString(CommonPlurals.room_profile_pinned_count, data.pinnedCount, data.pinnedCount)
                } else {
                    stringProvider.getString(CommonStrings.room_profile_pinned_none)
                },
                icon = R.drawable.ic_pin,
                action = { callback?.onPinnedMessagesClicked() }
        )
        buildProfileAction(
                id = "uploads",
                title = stringProvider.getString(CommonStrings.room_profile_section_more_uploads),
                subtitle = stringProvider.getString(CommonStrings.room_profile_uploads_summary),
                icon = R.drawable.ic_room_profile_uploads,
                action = { callback?.onUploadsClicked() }
        )
        buildProfileAction(
                id = "poll_history",
                title = stringProvider.getString(CommonStrings.room_profile_section_more_polls),
                subtitle = stringProvider.getString(CommonStrings.room_profile_polls_summary),
                icon = R.drawable.ic_attachment_poll,
                action = { callback?.onPollHistoryClicked() }
        )
        buildProfileAction(
                id = "image_packs",
                title = stringProvider.getString(CommonStrings.image_pack_settings_title),
                subtitle = if (data.imagePackCount > 0) {
                    stringProvider.getQuantityString(CommonPlurals.room_profile_image_packs_count, data.imagePackCount, data.imagePackCount)
                } else {
                    stringProvider.getString(CommonStrings.room_profile_image_packs_none)
                },
                icon = R.drawable.ic_attachment_sticker,
                action = { callback?.onImagePacksClicked() }
        )
    }

    private fun buildPreferencesSection(data: RoomProfileViewState, isPreview: Boolean) {
        buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_preferences))
        buildProfileAction(
                id = "settings",
                title = stringProvider.getString(CommonStrings.room_profile_section_more_settings),
                icon = R.drawable.ic_room_profile_tune,
                action = { callback?.onSettingsClicked() }
        )
        if (!isPreview) {
            buildProfileAction(
                    id = "notifications",
                    title = stringProvider.getString(CommonStrings.room_profile_section_more_notifications),
                    subtitle = data.notificationState?.let { notificationStateLabel(it) },
                    icon = R.drawable.ic_room_profile_notification,
                    action = { callback?.onNotificationsClicked() }
            )
            buildProfileAction(
                    id = "personalization",
                    title = stringProvider.getString(CommonStrings.room_profile_section_more_personalization),
                    subtitle = stringProvider.getString(CommonStrings.room_profile_personalization_summary),
                    icon = R.drawable.ic_room_profile_personalization,
                    action = { callback?.onPersonalizationClicked() }
            )
        }
        if (!isPreview && shortcutCreator.canCreateShortcut()) {
            buildProfileAction(
                    id = "shortcut",
                    title = stringProvider.getString(CommonStrings.room_settings_add_homescreen_shortcut),
                    editable = false,
                    icon = R.drawable.ic_add_to_home_screen_24dp,
                    action = { callback?.createShortcut() }
            )
        }
    }

    private fun notificationStateLabel(state: RoomNotificationState): String? = when (state) {
        RoomNotificationState.ALL_MESSAGES -> CommonStrings.room_settings_follow_account_default
        RoomNotificationState.ALL_MESSAGES_NOISY -> CommonStrings.room_settings_all_messages
        RoomNotificationState.MENTIONS_ONLY -> CommonStrings.room_settings_mention_and_keyword_only
        RoomNotificationState.MUTE -> CommonStrings.room_settings_none
        else -> null
    }?.let { stringProvider.getString(it) }

    private fun buildAdministrationSection(data: RoomProfileViewState, roomSummary: RoomSummary, roomVersion: String?) {
        buildProfileSection(stringProvider.getString(CommonStrings.room_profile_section_administration))
        buildProfileAction(
                id = "alias",
                title = stringProvider.getString(CommonStrings.room_settings_alias_title),
                subtitle = when {
                    data.canonicalAlias == null -> stringProvider.getString(CommonStrings.room_profile_addresses_none)
                    data.altAliasCount > 0 -> stringProvider.getQuantityString(
                            CommonPlurals.room_profile_addresses_with_aliases,
                            data.altAliasCount,
                            data.altAliasCount
                    )
                    else -> stringProvider.getString(CommonStrings.room_profile_addresses_main_only)
                },
                icon = R.drawable.ic_tag_24,
                action = { callback?.onRoomAliasesClicked() }
        )
        buildProfileAction(
                id = "permissions",
                title = stringProvider.getString(CommonStrings.room_settings_permissions_title),
                subtitle = stringProvider.getString(CommonStrings.room_settings_permissions_subtitle),
                icon = R.drawable.ic_room_profile_key,
                action = { callback?.onRoomPermissionsClicked() }
        )
        buildProfileAction(
                id = "acl",
                title = stringProvider.getString(CommonStrings.room_acl_title),
                subtitle = stringProvider.getString(CommonStrings.room_acl_subtitle),
                icon = R.drawable.ic_room_profile_dns,
                action = { callback?.onRoomAclClicked() }
        )

        if (vectorPreferences.developerMode()) {
            buildProfileSection(stringProvider.getString(CommonStrings.room_settings_category_advanced_title))
            buildProfileAction(
                    id = "roomId",
                    title = stringProvider.getString(CommonStrings.room_settings_room_internal_id),
                    subtitle = roomSummary.roomId,
                    editable = false,
                    icon = R.drawable.ic_view_source,
                    action = { callback?.onRoomIdClicked() }
            )
            roomVersion?.let {
                buildProfileAction(
                        id = "roomVersion",
                        title = stringProvider.getString(CommonStrings.room_settings_room_version_title),
                        subtitle = it,
                        editable = false,
                        icon = R.drawable.ic_view_source
                )
            }
            buildProfileAction(
                    id = "devTools",
                    title = stringProvider.getString(CommonStrings.dev_tools_menu_name),
                    editable = true,
                    icon = R.drawable.ic_view_source,
                    action = { callback?.onRoomDevToolsClicked() }
            )
        }
    }

    private fun buildEncryptionAction(actionPermissions: RoomProfileViewState.ActionPermissions, roomSummary: RoomSummary) {
        if (!roomSummary.isEncrypted && actionPermissions.canEnableEncryption) {
            buildProfileAction(
                    id = "enableEncryption",
                    title = stringProvider.getString(CommonStrings.room_settings_enable_encryption),
                    icon = R.drawable.ic_shield_black,
                    editable = false,
                    action = { callback?.onEnableEncryptionClicked() }
            )
        }
    }
}
