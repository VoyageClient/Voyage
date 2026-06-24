/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.command

import org.matrix.android.sdk.api.session.identity.ThreePid

/**
 * Represent a parsed command.
 */
sealed interface ParsedCommand {
    // This is not a Slash command
    object ErrorNotACommand : ParsedCommand

    object ErrorEmptySlashCommand : ParsedCommand

    class ErrorCommandNotSupportedInThreads(val command: Command) : ParsedCommand

    // Unknown/Unsupported slash command
    data class ErrorUnknownSlashCommand(val slashCommand: String) : ParsedCommand

    // A slash command is detected, but there is an error
    data class ErrorSyntax(val command: Command) : ParsedCommand

    // Valid commands:
    data class SendGreentext(val message: CharSequence) : ParsedCommand
    data class SendBlockquote(val message: CharSequence) : ParsedCommand
    data class ConvertToDm(val targetUserId: String? = null) : ParsedCommand
    object ConvertToRoom : ParsedCommand
    data class SendPlainText(val message: CharSequence) : ParsedCommand
    data class SendFormattedText(val message: CharSequence, val formattedMessage: String) : ParsedCommand
    data class SendEmote(val message: CharSequence) : ParsedCommand
    data class SendNotice(val message: CharSequence) : ParsedCommand
    data class SendRainbow(val message: CharSequence) : ParsedCommand
    data class SendRainbowEmote(val message: CharSequence) : ParsedCommand
    data class BanUser(val userId: String, val reason: String?) : ParsedCommand
    data class UnbanUser(val userId: String, val reason: String?) : ParsedCommand
    data class IgnoreUser(val userId: String) : ParsedCommand
    data class UnignoreUser(val userId: String) : ParsedCommand
    data class SetUserPowerLevel(val userId: String, val powerLevel: Int?) : ParsedCommand
    data class ChangeRoomName(val name: String) : ParsedCommand
    data class Invite(val userId: String, val reason: String?) : ParsedCommand
    data class Invite3Pid(val threePid: ThreePid) : ParsedCommand
    data class JoinRoom(val roomAlias: String, val reason: String?) : ParsedCommand
    data class PartRoom(val roomAlias: String?) : ParsedCommand
    data class ChangeTopic(val topic: String) : ParsedCommand
    data class KickUser(val userId: String, val reason: String?) : ParsedCommand
    data class ChangeDisplayName(val displayName: String) : ParsedCommand
    data class ChangeDisplayNameForRoom(val displayName: String) : ParsedCommand
    data class ChangeRoomAvatar(val url: String) : ParsedCommand
    data class ChangeAvatarForRoom(val url: String) : ParsedCommand
    data class SetMarkdown(val enable: Boolean) : ParsedCommand
    object ClearScalarToken : ParsedCommand
    object DevTools : ParsedCommand
    data class SendSpoiler(val message: CharSequence) : ParsedCommand
    data class SendShrug(val message: CharSequence) : ParsedCommand
    data class SendTableFlip(val message: CharSequence) : ParsedCommand
    data class SendLenny(val message: CharSequence) : ParsedCommand
    object DiscardSession : ParsedCommand
    data class ShowUser(val userId: String) : ParsedCommand
    data class CreateSpace(val name: String, val invitees: List<String>) : ParsedCommand
    data class AddToSpace(val spaceId: String) : ParsedCommand
    data class JoinSpace(val spaceIdOrAlias: String) : ParsedCommand
    data class LeaveRoom(val roomId: String) : ParsedCommand
    data class UpgradeRoom(val newVersion: String) : ParsedCommand
    data class Tombstone(val replacementRoomId: String, val body: String) : ParsedCommand
    object JumpToStart : ParsedCommand
    data class JumpToEvent(val eventId: String) : ParsedCommand
    data class JumpToDate(val date: String) : ParsedCommand
    object TogglePgpMode : ParsedCommand
    data class SendPgpEncrypted(val message: CharSequence) : ParsedCommand
}
