/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import im.vector.app.core.platform.VectorViewEvents
import im.vector.app.features.command.Command
import im.vector.app.features.command.ParsedCommand
import org.matrix.android.sdk.api.session.room.model.relation.MassRedactionRange

sealed class MessageComposerViewEvents : VectorViewEvents {

    data class AnimateSendButtonVisibility(val isVisible: Boolean) : MessageComposerViewEvents()

    data class ShowMessage(val message: String) : MessageComposerViewEvents()

    abstract class SendMessageResult : MessageComposerViewEvents()

    object MessageSent : SendMessageResult()
    data class JoinRoomCommandSuccess(val roomId: String) : SendMessageResult()
    data class SlashCommandError(val command: Command) : SendMessageResult()
    data class SlashCommandUnknown(val command: String) : SendMessageResult()
    data class SlashCommandNotSupportedInThreads(val command: Command) : SendMessageResult()
    object SlashCommandLoading : SendMessageResult()
    data class SlashCommandResultOk(val parsedCommand: ParsedCommand) : SendMessageResult()
    data class SlashCommandResultError(val throwable: Throwable) : SendMessageResult()

    data class SlashCommandConfirmationRequest(val parsedCommand: ParsedCommand) : MessageComposerViewEvents()

    data class OpenRoomMemberProfile(val userId: String) : MessageComposerViewEvents()

    data class ShowMassRedactConfirmation(
            val userId: String,
            val displayName: String,
            val delayMs: Long,
            val range: MassRedactionRange,
    ) : MessageComposerViewEvents()

    /**
     * A /join that couldn't join directly: open the room's matrix.to sheet (Join / Ask to join).
     */
    data class OpenRoomLink(val link: String) : MessageComposerViewEvents()

    // TODO Remove
    object SlashCommandNotImplemented : SendMessageResult()

    data class ShowRoomUpgradeDialog(val newVersion: String, val isPublic: Boolean) : MessageComposerViewEvents()

    data class VoicePlaybackOrRecordingFailure(val throwable: Throwable) : MessageComposerViewEvents()

    data class InsertUserDisplayName(val userId: String) : MessageComposerViewEvents()

    /**
     * Result of a /jumpto or /jumptostart command. [eventId] is the resolved target — null when it
     * couldn't be resolved (unknown event id), or, with [toRoomStart], when the create event isn't
     * cached and the timeline is left to find the room's start on its own.
     */
    data class JumpToEvent(val eventId: String?, val notFoundMessage: String? = null, val toRoomStart: Boolean = false) : MessageComposerViewEvents()

    /** Result of a /jumpto given a permalink, which may target another room. */
    data class JumpToPermalink(val link: String) : MessageComposerViewEvents()

    /** OpenKeychain needs the user (passphrase / key picker) before it can encrypt. */
    data class LaunchPgpInteraction(val pendingIntent: android.app.PendingIntent) : MessageComposerViewEvents()
}
