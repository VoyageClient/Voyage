/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.multibindings.IntoMap

@InstallIn(HomeMavericksViewModelComponent::class)
@Module
interface HomeMavericksViewModelModule {

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.auth.ReAuthViewModel")
    fun vm0(factory: im.vector.app.features.auth.ReAuthViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.HomeActivityViewModel")
    fun vm1(factory: im.vector.app.features.home.HomeActivityViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.HomeDetailViewModel")
    fun vm2(factory: im.vector.app.features.home.HomeDetailViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.NewHomeDetailViewModel")
    fun vm3(factory: im.vector.app.features.home.NewHomeDetailViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.UnknownDeviceDetectorSharedViewModel")
    fun vm4(factory: im.vector.app.features.home.UnknownDeviceDetectorSharedViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.UnreadMessagesSharedViewModel")
    fun vm5(factory: im.vector.app.features.home.UnreadMessagesSharedViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.UserColorAccountDataViewModel")
    fun vm6(factory: im.vector.app.features.home.UserColorAccountDataViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.breadcrumbs.BreadcrumbsViewModel")
    fun vm7(factory: im.vector.app.features.home.room.breadcrumbs.BreadcrumbsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.detail.TimelineViewModel")
    fun vm8(factory: im.vector.app.features.home.room.detail.TimelineViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.detail.composer.MessageComposerViewModel")
    fun vm9(factory: im.vector.app.features.home.room.detail.composer.MessageComposerViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.detail.composer.link.SetLinkViewModel")
    fun vm10(factory: im.vector.app.features.home.room.detail.composer.link.SetLinkViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.detail.search.SearchViewModel")
    fun vm11(factory: im.vector.app.features.home.room.detail.search.SearchViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.detail.timeline.action.MessageActionsViewModel")
    fun vm12(factory: im.vector.app.features.home.room.detail.timeline.action.MessageActionsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.detail.timeline.edithistory.ViewEditHistoryViewModel")
    fun vm13(factory: im.vector.app.features.home.room.detail.timeline.edithistory.ViewEditHistoryViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.detail.timeline.reactions.ViewReactionsViewModel")
    fun vm14(factory: im.vector.app.features.home.room.detail.timeline.reactions.ViewReactionsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.detail.upgrade.MigrateRoomViewModel")
    fun vm15(factory: im.vector.app.features.home.room.detail.upgrade.MigrateRoomViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.list.RoomListViewModel")
    fun vm16(factory: im.vector.app.features.home.room.list.RoomListViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.list.actions.RoomTagViewModel")
    fun vm17(factory: im.vector.app.features.home.room.list.actions.RoomTagViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.list.home.HomeRoomListViewModel")
    fun vm18(factory: im.vector.app.features.home.room.list.home.HomeRoomListViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.home.room.list.home.invites.InvitesViewModel")
    fun vm19(factory: im.vector.app.features.home.room.list.home.invites.InvitesViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.login.LoginViewModel")
    fun vm21(factory: im.vector.app.features.login.LoginViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.onboarding.OnboardingViewModel")
    fun vm22(factory: im.vector.app.features.onboarding.OnboardingViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.pin.lockscreen.ui.LockScreenViewModel")
    fun vm23(factory: im.vector.app.features.pin.lockscreen.ui.LockScreenViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.room.RequireActiveMembershipViewModel")
    fun vm24(factory: im.vector.app.features.room.RequireActiveMembershipViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.SpaceListViewModel")
    fun vm25(factory: im.vector.app.features.spaces.SpaceListViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.SpaceMenuViewModel")
    fun vm26(factory: im.vector.app.features.spaces.SpaceMenuViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.start.StartAppViewModel")
    fun vm27(factory: im.vector.app.features.start.StartAppViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.workers.signout.ServerBackupStatusViewModel")
    fun vm28(factory: im.vector.app.features.workers.signout.ServerBackupStatusViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.workers.signout.SignoutCheckViewModel")
    fun vm29(factory: im.vector.app.features.workers.signout.SignoutCheckViewModel.Factory): MavericksAssistedViewModelFactory<*, *>
}

@InstallIn(SettingsMavericksViewModelComponent::class)
@Module
interface SettingsMavericksViewModelModule {

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.account.deactivation.DeactivateAccountViewModel")
    fun vm0(factory: im.vector.app.features.settings.account.deactivation.DeactivateAccountViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.crosssigning.CrossSigningSettingsViewModel")
    fun vm1(factory: im.vector.app.features.settings.crosssigning.CrossSigningSettingsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devices.DeviceVerificationInfoBottomSheetViewModel")
    fun vm2(factory: im.vector.app.features.settings.devices.DeviceVerificationInfoBottomSheetViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devices.DevicesViewModel")
    fun vm3(factory: im.vector.app.features.settings.devices.DevicesViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devices.v2.DevicesViewModel")
    fun vm4(factory: im.vector.app.features.settings.devices.v2.DevicesViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devices.v2.details.SessionDetailsViewModel")
    fun vm5(factory: im.vector.app.features.settings.devices.v2.details.SessionDetailsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devices.v2.more.SessionLearnMoreViewModel")
    fun vm6(factory: im.vector.app.features.settings.devices.v2.more.SessionLearnMoreViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devices.v2.othersessions.OtherSessionsViewModel")
    fun vm7(factory: im.vector.app.features.settings.devices.v2.othersessions.OtherSessionsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devices.v2.overview.SessionOverviewViewModel")
    fun vm8(factory: im.vector.app.features.settings.devices.v2.overview.SessionOverviewViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devices.v2.rename.RenameSessionViewModel")
    fun vm9(factory: im.vector.app.features.settings.devices.v2.rename.RenameSessionViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devtools.AccountDataViewModel")
    fun vm10(factory: im.vector.app.features.settings.devtools.AccountDataViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devtools.GossipingEventsPaperTrailViewModel")
    fun vm11(factory: im.vector.app.features.settings.devtools.GossipingEventsPaperTrailViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devtools.KeyRequestListViewModel")
    fun vm12(factory: im.vector.app.features.settings.devtools.KeyRequestListViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.devtools.KeyRequestViewModel")
    fun vm13(factory: im.vector.app.features.settings.devtools.KeyRequestViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.font.FontScaleSettingViewModel")
    fun vm14(factory: im.vector.app.features.settings.font.FontScaleSettingViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.homeserver.HomeserverSettingsViewModel")
    fun vm15(factory: im.vector.app.features.settings.homeserver.HomeserverSettingsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.ignored.IgnoredUsersViewModel")
    fun vm16(factory: im.vector.app.features.settings.ignored.IgnoredUsersViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.labs.VectorSettingsLabsViewModel")
    fun vm17(factory: im.vector.app.features.settings.labs.VectorSettingsLabsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.legals.LegalsViewModel")
    fun vm18(factory: im.vector.app.features.settings.legals.LegalsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.locale.LocalePickerViewModel")
    fun vm19(factory: im.vector.app.features.settings.locale.LocalePickerViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.notifications.VectorSettingsNotificationViewModel")
    fun vm20(factory: im.vector.app.features.settings.notifications.VectorSettingsNotificationViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.notifications.VectorSettingsPushRuleNotificationViewModel")
    fun vm21(factory: im.vector.app.features.settings.notifications.VectorSettingsPushRuleNotificationViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.push.PushGatewaysViewModel")
    fun vm22(factory: im.vector.app.features.settings.push.PushGatewaysViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.threepids.ThreePidsSettingsViewModel")
    fun vm23(factory: im.vector.app.features.settings.threepids.ThreePidsSettingsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.settings.timezone.TimezonePickerViewModel")
    fun vm24s(factory: im.vector.app.features.settings.timezone.TimezonePickerViewModel.Factory): MavericksAssistedViewModelFactory<*, *>
}

@InstallIn(SpacesMavericksViewModelComponent::class)
@Module
interface SpacesMavericksViewModelModule {

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomdirectory.RoomDirectoryViewModel")
    fun vm0(factory: im.vector.app.features.roomdirectory.RoomDirectoryViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomdirectory.createroom.CreateRoomViewModel")
    fun vm1(factory: im.vector.app.features.roomdirectory.createroom.CreateRoomViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomdirectory.pendingrequests.PendingJoinRequestsViewModel")
    fun vm2(factory: im.vector.app.features.roomdirectory.pendingrequests.PendingJoinRequestsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomdirectory.picker.RoomDirectoryPickerViewModel")
    fun vm3(factory: im.vector.app.features.roomdirectory.picker.RoomDirectoryPickerViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomdirectory.roompreview.RoomPreviewViewModel")
    fun vm4(factory: im.vector.app.features.roomdirectory.roompreview.RoomPreviewViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.create.CreateSpaceViewModel")
    fun vm5(factory: im.vector.app.features.spaces.create.CreateSpaceViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.explore.SpaceDirectoryViewModel")
    fun vm6(factory: im.vector.app.features.spaces.explore.SpaceDirectoryViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.invite.SpaceInviteBottomSheetViewModel")
    fun vm7(factory: im.vector.app.features.spaces.invite.SpaceInviteBottomSheetViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.leave.SpaceLeaveAdvancedViewModel")
    fun vm8(factory: im.vector.app.features.spaces.leave.SpaceLeaveAdvancedViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.manage.SpaceAddRoomsViewModel")
    fun vm9(factory: im.vector.app.features.spaces.manage.SpaceAddRoomsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.manage.SpaceManageRoomsViewModel")
    fun vm10(factory: im.vector.app.features.spaces.manage.SpaceManageRoomsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.manage.SpaceManageSharedViewModel")
    fun vm11(factory: im.vector.app.features.spaces.manage.SpaceManageSharedViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.people.SpacePeopleViewModel")
    fun vm12(factory: im.vector.app.features.spaces.people.SpacePeopleViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.preview.SpacePreviewViewModel")
    fun vm13(factory: im.vector.app.features.spaces.preview.SpacePreviewViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.spaces.share.ShareSpaceViewModel")
    fun vm14(factory: im.vector.app.features.spaces.share.ShareSpaceViewModel.Factory): MavericksAssistedViewModelFactory<*, *>
}

@InstallIn(RoomProfileMavericksViewModelComponent::class)
@Module
interface RoomProfileMavericksViewModelModule {

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roommemberprofile.RoomMemberProfileViewModel")
    fun vm0(factory: im.vector.app.features.roommemberprofile.RoomMemberProfileViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roommemberprofile.devices.DeviceListBottomSheetViewModel")
    fun vm1(factory: im.vector.app.features.roommemberprofile.devices.DeviceListBottomSheetViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.RoomProfileViewModel")
    fun vm2(factory: im.vector.app.features.roomprofile.RoomProfileViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.alias.RoomAliasViewModel")
    fun vm3(factory: im.vector.app.features.roomprofile.alias.RoomAliasViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.alias.detail.RoomAliasBottomSheetViewModel")
    fun vm4(factory: im.vector.app.features.roomprofile.alias.detail.RoomAliasBottomSheetViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.banned.RoomBannedMemberListViewModel")
    fun vm5(factory: im.vector.app.features.roomprofile.banned.RoomBannedMemberListViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.knock.RoomKnockRequestsViewModel")
    fun vm6(factory: im.vector.app.features.roomprofile.knock.RoomKnockRequestsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.members.RoomMemberListViewModel")
    fun vm7(factory: im.vector.app.features.roomprofile.members.RoomMemberListViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.notifications.RoomNotificationSettingsViewModel")
    fun vm8(factory: im.vector.app.features.roomprofile.notifications.RoomNotificationSettingsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.permissions.RoomPermissionsViewModel")
    fun vm9(factory: im.vector.app.features.roomprofile.permissions.RoomPermissionsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.pinned.RoomPinnedMessagesViewModel")
    fun vm10(factory: im.vector.app.features.roomprofile.pinned.RoomPinnedMessagesViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.polls.RoomPollsViewModel")
    fun vm11(factory: im.vector.app.features.roomprofile.polls.RoomPollsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.polls.detail.ui.RoomPollDetailViewModel")
    fun vm12(factory: im.vector.app.features.roomprofile.polls.detail.ui.RoomPollDetailViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.settings.RoomSettingsViewModel")
    fun vm13(factory: im.vector.app.features.roomprofile.settings.RoomSettingsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.settings.joinrule.advanced.RoomJoinRuleChooseRestrictedViewModel")
    fun vm14(factory: im.vector.app.features.roomprofile.settings.joinrule.advanced.RoomJoinRuleChooseRestrictedViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roomprofile.uploads.RoomUploadsViewModel")
    fun vm15(factory: im.vector.app.features.roomprofile.uploads.RoomUploadsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.roommemberprofile.mutualrooms.MutualRoomsViewModel")
    fun vm16(factory: im.vector.app.features.roommemberprofile.mutualrooms.MutualRoomsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>
}

@InstallIn(MiscMavericksViewModelComponent::class)
@Module
interface MiscMavericksViewModelModule {

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.attachments.AttachmentTypeSelectorViewModel")
    fun vm0(factory: im.vector.app.features.attachments.AttachmentTypeSelectorViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.contactsbook.ContactsBookViewModel")
    fun vm1(factory: im.vector.app.features.contactsbook.ContactsBookViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.createdirect.CreateDirectRoomViewModel")
    fun vm2(factory: im.vector.app.features.createdirect.CreateDirectRoomViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.crypto.keysbackup.settings.KeysBackupSettingsViewModel")
    fun vm3(factory: im.vector.app.features.crypto.keysbackup.settings.KeysBackupSettingsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.crypto.quads.SharedSecureStorageViewModel")
    fun vm4(factory: im.vector.app.features.crypto.quads.SharedSecureStorageViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.crypto.recover.BootstrapSharedViewModel")
    fun vm5(factory: im.vector.app.features.crypto.recover.BootstrapSharedViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.crypto.verification.self.SelfVerificationViewModel")
    fun vm6(factory: im.vector.app.features.crypto.verification.self.SelfVerificationViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.crypto.verification.user.UserVerificationViewModel")
    fun vm7(factory: im.vector.app.features.crypto.verification.user.UserVerificationViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.devtools.RoomDevToolViewModel")
    fun vm8(factory: im.vector.app.features.devtools.RoomDevToolViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.discovery.DiscoverySettingsViewModel")
    fun vm9(factory: im.vector.app.features.discovery.DiscoverySettingsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.discovery.change.SetIdentityServerViewModel")
    fun vm10(factory: im.vector.app.features.discovery.change.SetIdentityServerViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.invite.InviteUsersToRoomViewModel")
    fun vm11(factory: im.vector.app.features.invite.InviteUsersToRoomViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.location.LocationSharingViewModel")
    fun vm12(factory: im.vector.app.features.location.LocationSharingViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.location.live.map.LiveLocationMapViewModel")
    fun vm13(factory: im.vector.app.features.location.live.map.LiveLocationMapViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.location.preview.LocationPreviewViewModel")
    fun vm14(factory: im.vector.app.features.location.preview.LocationPreviewViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.matrixto.MatrixToBottomSheetViewModel")
    fun vm15(factory: im.vector.app.features.matrixto.MatrixToBottomSheetViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.media.VectorAttachmentViewerViewModel")
    fun vm16(factory: im.vector.app.features.media.VectorAttachmentViewerViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.poll.create.CreatePollViewModel")
    fun vm17(factory: im.vector.app.features.poll.create.CreatePollViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.qrcode.QrCodeScannerViewModel")
    fun vm18(factory: im.vector.app.features.qrcode.QrCodeScannerViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.reactions.EmojiSearchResultViewModel")
    fun vm19(factory: im.vector.app.features.reactions.EmojiSearchResultViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.share.IncomingShareViewModel")
    fun vm20(factory: im.vector.app.features.share.IncomingShareViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.signout.soft.SoftLogoutViewModel")
    fun vm21(factory: im.vector.app.features.signout.soft.SoftLogoutViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.terms.ReviewTermsViewModel")
    fun vm22(factory: im.vector.app.features.terms.ReviewTermsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.userdirectory.UserListViewModel")
    fun vm24(factory: im.vector.app.features.userdirectory.UserListViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.widgets.WidgetViewModel")
    fun vm25(factory: im.vector.app.features.widgets.WidgetViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.widgets.permissions.RoomWidgetPermissionViewModel")
    fun vm26(factory: im.vector.app.features.widgets.permissions.RoomWidgetPermissionViewModel.Factory): MavericksAssistedViewModelFactory<*, *>
}

