/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.command

import androidx.core.text.HtmlCompat
import im.vector.app.core.extensions.isMsisdn
import im.vector.app.core.extensions.orEmpty
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.extensions.isEmail
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import org.matrix.android.sdk.api.session.room.model.relation.MassRedactionRange
import timber.log.Timber
import javax.inject.Inject

class CommandParser @Inject constructor(
        private val vectorPreferences: VectorPreferences
) {

    /**
     * Convert the text message into a Slash command.
     *
     * @param textMessage the text message in plain text
     * @param formattedMessage the text messaged in HTML format
     * @param isInThreadTimeline true if the user is currently typing in a thread
     * @return a parsed slash command (ok or error)
     */
    @Suppress("NAME_SHADOWING")
    fun parseSlashCommand(textMessage: CharSequence, formattedMessage: String?, isInThreadTimeline: Boolean): ParsedCommand {
        // check if it has the Slash marker
        val message = formattedMessage ?: textMessage
        return if (!message.startsWith("/")) {
            ParsedCommand.ErrorNotACommand
        } else {
            // "/" only
            if (message.length == 1) {
                return ParsedCommand.ErrorEmptySlashCommand
            }

            // Exclude "//"
            if ("/" == message.substring(1, 2)) {
                return ParsedCommand.ErrorNotACommand
            }

            val (messageParts, message) = extractMessage(message) ?: return ParsedCommand.ErrorEmptySlashCommand
            val slashCommand = messageParts.first()

            getNotSupportedByThreads(isInThreadTimeline, slashCommand)?.let {
                return ParsedCommand.ErrorCommandNotSupportedInThreads(it)
            }

            when {
                Command.GREENTEXT.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.SendGreentext(message = message)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.GREENTEXT)
                    }
                }

                Command.BLOCKQUOTE.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.SendBlockquote(message = message)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.BLOCKQUOTE)
                    }
                }

                Command.HTML.matches(slashCommand) -> {
                    val rawHtml = extractMessage(textMessage)?.second?.toString() ?: ""
                    if (rawHtml.isNotEmpty()) {
                        val plainText = HtmlCompat.fromHtml(rawHtml, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                        ParsedCommand.SendFormattedText(message = plainText, formattedMessage = rawHtml)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.HTML)
                    }
                }

                Command.PLAIN.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        if (formattedMessage != null) {
                            val trimmedPlainTextMessage = extractMessage(textMessage)?.second?.toString().orEmpty()
                            ParsedCommand.SendFormattedText(message = trimmedPlainTextMessage, formattedMessage = message.toString())
                        } else {
                            ParsedCommand.SendPlainText(message = message)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.PLAIN)
                    }
                }
                Command.CHANGE_DISPLAY_NAME.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.ChangeDisplayName(displayName = message.toString())
                    } else {
                        ParsedCommand.ErrorSyntax(Command.CHANGE_DISPLAY_NAME)
                    }
                }
                Command.CHANGE_DISPLAY_NAME_FOR_ROOM.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.ChangeDisplayNameForRoom(displayName = message.toString())
                    } else {
                        ParsedCommand.ErrorSyntax(Command.CHANGE_DISPLAY_NAME_FOR_ROOM)
                    }
                }
                Command.ROOM_AVATAR.matches(slashCommand) -> {
                    if (messageParts.size == 2) {
                        val url = messageParts[1]

                        if (url.isMxcUrl()) {
                            ParsedCommand.ChangeRoomAvatar(url)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.ROOM_AVATAR)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.ROOM_AVATAR)
                    }
                }
                Command.CHANGE_AVATAR_FOR_ROOM.matches(slashCommand) -> {
                    if (messageParts.size == 2) {
                        val url = messageParts[1]

                        if (url.isMxcUrl()) {
                            ParsedCommand.ChangeAvatarForRoom(url)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.CHANGE_AVATAR_FOR_ROOM)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.CHANGE_AVATAR_FOR_ROOM)
                    }
                }
                Command.TOPIC.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.ChangeTopic(topic = message.toString())
                    } else {
                        ParsedCommand.ErrorSyntax(Command.TOPIC)
                    }
                }
                Command.EMOTE.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.SendEmote(message)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.EMOTE)
                    }
                }
                Command.NOTICE.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.SendNotice(message)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.NOTICE)
                    }
                }
                Command.RAINBOW.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.SendRainbow(message)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.RAINBOW)
                    }
                }
                Command.RAINBOW_EMOTE.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.SendRainbowEmote(message)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.RAINBOW_EMOTE)
                    }
                }
                Command.TRANS.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.SendTrans(message)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.TRANS)
                    }
                }
                Command.TRANS_EMOTE.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.SendTransEmote(message)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.TRANS_EMOTE)
                    }
                }
                Command.WATCH_ROOM.matches(slashCommand) -> {
                    val roomAlias = messageParts.getOrNull(1)
                    if (!roomAlias.isNullOrEmpty()) {
                        ParsedCommand.WatchRoom(roomAlias)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.WATCH_ROOM)
                    }
                }
                Command.UNWATCH_ROOM.matches(slashCommand) -> {
                    val roomAlias = messageParts.getOrNull(1)
                    if (!roomAlias.isNullOrEmpty()) {
                        ParsedCommand.UnwatchRoom(roomAlias)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.UNWATCH_ROOM)
                    }
                }
                Command.JOIN_ROOM.matches(slashCommand) -> {
                    if (messageParts.size >= 2) {
                        val roomAlias = messageParts[1]

                        if (roomAlias.isNotEmpty()) {
                            ParsedCommand.JoinRoom(
                                    roomAlias,
                                    trimParts(textMessage, messageParts.take(2))
                            )
                        } else {
                            ParsedCommand.ErrorSyntax(Command.JOIN_ROOM)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.JOIN_ROOM)
                    }
                }
                Command.PART.matches(slashCommand) -> {
                    when (messageParts.size) {
                        1 -> ParsedCommand.PartRoom(null)
                        2 -> ParsedCommand.PartRoom(messageParts[1])
                        else -> ParsedCommand.ErrorSyntax(Command.PART)
                    }
                }
                Command.ROOM_NAME.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.ChangeRoomName(name = message.toString())
                    } else {
                        ParsedCommand.ErrorSyntax(Command.ROOM_NAME)
                    }
                }
                Command.INVITE.matches(slashCommand) -> {
                    if (messageParts.size >= 2) {
                        val userId = messageParts[1]

                        when {
                            MatrixPatterns.isUserId(userId) -> {
                                ParsedCommand.Invite(
                                        userId,
                                        trimParts(textMessage, messageParts.take(2))
                                )
                            }
                            userId.isEmail() -> {
                                ParsedCommand.Invite3Pid(ThreePid.Email(userId))
                            }
                            userId.isMsisdn() -> {
                                ParsedCommand.Invite3Pid(ThreePid.Msisdn(userId))
                            }
                            else -> {
                                ParsedCommand.ErrorSyntax(Command.INVITE)
                            }
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.INVITE)
                    }
                }
                Command.KICK_USER.matches(slashCommand) -> {
                    if (messageParts.size >= 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.KickUser(
                                    userId,
                                    trimParts(textMessage, messageParts.take(2))
                            )
                        } else {
                            ParsedCommand.ErrorSyntax(Command.KICK_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.KICK_USER)
                    }
                }
                Command.MASS_REDACT.matches(slashCommand) -> {
                    val userId = messageParts.getOrNull(1)
                    if (userId == null || !MatrixPatterns.isUserId(userId)) {
                        ParsedCommand.ErrorSyntax(Command.MASS_REDACT)
                    } else {
                        parseMassRedactOptions(userId, messageParts.drop(2))
                    }
                }
                Command.BAN_USER.matches(slashCommand) -> {
                    if (messageParts.size >= 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.BanUser(
                                    userId,
                                    trimParts(textMessage, messageParts.take(2))
                            )
                        } else {
                            ParsedCommand.ErrorSyntax(Command.BAN_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.BAN_USER)
                    }
                }
                Command.UNBAN_USER.matches(slashCommand) -> {
                    if (messageParts.size >= 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.UnbanUser(
                                    userId,
                                    trimParts(textMessage, messageParts.take(2))
                            )
                        } else {
                            ParsedCommand.ErrorSyntax(Command.UNBAN_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.UNBAN_USER)
                    }
                }
                Command.IGNORE_USER.matches(slashCommand) -> {
                    if (messageParts.size == 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.IgnoreUser(userId)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.IGNORE_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.IGNORE_USER)
                    }
                }
                Command.UNIGNORE_USER.matches(slashCommand) -> {
                    if (messageParts.size == 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.UnignoreUser(userId)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.UNIGNORE_USER)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.UNIGNORE_USER)
                    }
                }
                Command.SET_USER_POWER_LEVEL.matches(slashCommand) -> {
                    if (messageParts.size == 3) {
                        val userId = messageParts[1]
                        if (MatrixPatterns.isUserId(userId)) {
                            val powerLevelsAsString = messageParts[2]

                            try {
                                val powerLevelsAsInt = Integer.parseInt(powerLevelsAsString)

                                ParsedCommand.SetUserPowerLevel(userId, powerLevelsAsInt)
                            } catch (e: Exception) {
                                ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                            }
                        } else {
                            ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                    }
                }
                Command.RESET_USER_POWER_LEVEL.matches(slashCommand) -> {
                    if (messageParts.size == 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.SetUserPowerLevel(userId, null)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.SET_USER_POWER_LEVEL)
                    }
                }
                Command.MARKDOWN.matches(slashCommand) -> {
                    if (messageParts.size == 2) {
                        when {
                            "on".equals(messageParts[1], true) -> ParsedCommand.SetMarkdown(true)
                            "off".equals(messageParts[1], true) -> ParsedCommand.SetMarkdown(false)
                            else -> ParsedCommand.ErrorSyntax(Command.MARKDOWN)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.MARKDOWN)
                    }
                }
                Command.DEVTOOLS.matches(slashCommand) -> {
                    if (messageParts.size == 1) {
                        ParsedCommand.DevTools
                    } else {
                        ParsedCommand.ErrorSyntax(Command.DEVTOOLS)
                    }
                }
                Command.CLEAR_SCALAR_TOKEN.matches(slashCommand) -> {
                    if (messageParts.size == 1) {
                        ParsedCommand.ClearScalarToken
                    } else {
                        ParsedCommand.ErrorSyntax(Command.CLEAR_SCALAR_TOKEN)
                    }
                }
                Command.SPOILER.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.SendSpoiler(message)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.SPOILER)
                    }
                }
                Command.SHRUG.matches(slashCommand) -> {
                    ParsedCommand.SendShrug(message)
                }
                Command.LENNY.matches(slashCommand) -> {
                    ParsedCommand.SendLenny(message)
                }
                Command.TABLE_FLIP.matches(slashCommand) -> {
                    ParsedCommand.SendTableFlip(message)
                }
                Command.DISCARD_SESSION.matches(slashCommand) -> {
                    if (messageParts.size == 1) {
                        ParsedCommand.DiscardSession
                    } else {
                        ParsedCommand.ErrorSyntax(Command.DISCARD_SESSION)
                    }
                }
                Command.WHOIS.matches(slashCommand) -> {
                    if (messageParts.size == 2) {
                        val userId = messageParts[1]

                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.ShowUser(userId)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.WHOIS)
                        }
                    } else {
                        ParsedCommand.ErrorSyntax(Command.WHOIS)
                    }
                }
                Command.CREATE_SPACE.matches(slashCommand) -> {
                    if (messageParts.size >= 2) {
                        ParsedCommand.CreateSpace(
                                messageParts[1],
                                messageParts.drop(2)
                        )
                    } else {
                        ParsedCommand.ErrorSyntax(Command.CREATE_SPACE)
                    }
                }
                Command.ADD_TO_SPACE.matches(slashCommand) -> {
                    if (messageParts.size == 2) {
                        ParsedCommand.AddToSpace(spaceId = messageParts.last())
                    } else {
                        ParsedCommand.ErrorSyntax(Command.ADD_TO_SPACE)
                    }
                }
                Command.JOIN_SPACE.matches(slashCommand) -> {
                    if (messageParts.size == 2) {
                        ParsedCommand.JoinSpace(spaceIdOrAlias = messageParts.last())
                    } else {
                        ParsedCommand.ErrorSyntax(Command.JOIN_SPACE)
                    }
                }
                Command.LEAVE_ROOM.matches(slashCommand) -> {
                    ParsedCommand.LeaveRoom(roomId = message.toString())
                }
                Command.UPGRADE_ROOM.matches(slashCommand) -> {
                    if (message.isNotEmpty()) {
                        ParsedCommand.UpgradeRoom(newVersion = message.toString())
                    } else {
                        ParsedCommand.ErrorSyntax(Command.UPGRADE_ROOM)
                    }
                }
                Command.TOMBSTONE.matches(slashCommand) -> {
                    val args = messageParts.drop(1)
                    val roomIdIndices = args.indices.filter { MatrixPatterns.isRoomId(args[it]) || MatrixPatterns.isRoomAlias(args[it]) }
                    when {
                        roomIdIndices.size > 1 -> ParsedCommand.ErrorSyntax(Command.TOMBSTONE)
                        roomIdIndices.isEmpty() -> ParsedCommand.Tombstone(replacementRoomId = "", body = message.toString())
                        else -> {
                            val index = roomIdIndices.single()
                            val before = args.subList(0, index)
                            val after = args.subList(index + 1, args.size)
                            // A replacement room must sit at one end so the reason stays a single run of text
                            if (before.isNotEmpty() && after.isNotEmpty()) {
                                ParsedCommand.ErrorSyntax(Command.TOMBSTONE)
                            } else {
                                ParsedCommand.Tombstone(
                                        replacementRoomId = args[index],
                                        body = (before + after).joinToString(" ")
                                )
                            }
                        }
                    }
                }
                Command.JUMP_TO_START.matches(slashCommand) -> {
                    ParsedCommand.JumpToStart
                }
                Command.CONVERT_TO_DM.matches(slashCommand) -> {
                    if (messageParts.size >= 2) {
                        val userId = messageParts[1]
                        if (MatrixPatterns.isUserId(userId)) {
                            ParsedCommand.ConvertToDm(targetUserId = userId)
                        } else {
                            ParsedCommand.ErrorSyntax(Command.CONVERT_TO_DM)
                        }
                    } else {
                        ParsedCommand.ConvertToDm()
                    }
                }
                Command.CONVERT_TO_ROOM.matches(slashCommand) -> {
                    ParsedCommand.ConvertToRoom
                }
                Command.JUMP_TO.matches(slashCommand) -> {
                    val candidate = message.toString().trim()
                    when {
                        candidate.isEmpty() -> ParsedCommand.ErrorSyntax(Command.JUMP_TO)
                        MatrixPatterns.isEventId(candidate) -> ParsedCommand.JumpToEvent(eventId = candidate)
                        isRoomPermalink(candidate) -> ParsedCommand.JumpToPermalink(link = candidate)
                        else -> ParsedCommand.ErrorSyntax(Command.JUMP_TO)
                    }
                }
                Command.JUMP_TO_DATE.matches(slashCommand) -> {
                    val raw = message.toString().trim()
                    if (raw.matches(Regex("""\d{4}-\d{1,2}-\d{1,2}"""))) {
                        ParsedCommand.JumpToDate(date = raw)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.JUMP_TO_DATE)
                    }
                }
                Command.ENCRYPT.matches(slashCommand) -> {
                    // "/encrypt" alone toggles PGP for the room; "/encrypt <message>" is a one-off.
                    if (message.isEmpty()) {
                        ParsedCommand.TogglePgpMode
                    } else {
                        ParsedCommand.SendPgpEncrypted(message = message)
                    }
                }
                Command.DOWNLOAD.matches(slashCommand) -> {
                    val url = message.toString().trim()
                    if (url.isMxcUrl() && url.none { it.isWhitespace() }) {
                        ParsedCommand.DownloadFile(mxcUrl = url)
                    } else {
                        ParsedCommand.ErrorSyntax(Command.DOWNLOAD)
                    }
                }
                Command.CRASH_APP.matches(slashCommand) && vectorPreferences.developerMode() -> {
                    throw RuntimeException("Application crashed from user demand")
                }
                else -> {
                    // Unknown command
                    ParsedCommand.ErrorUnknownSlashCommand(slashCommand)
                }
            }
        }
    }

    /**
     * Returns the [Command] the text would resolve to without parsing arguments, or null if it is
     * not a slash command. Lets callers resolve mention pills differently per command.
     */
    fun getCommand(textMessage: CharSequence): Command? {
        val message = textMessage.toString()
        if (!message.startsWith("/") || message.length < 2 || message[1] == '/') return null
        val slashCommand = message.split("\\s+".toRegex()).firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return Command.values().firstOrNull { it.matches(slashCommand) }
    }

    // Keep the message part as a span-preserving subSequence so mention pills survive parsing;
    // the split parts stay plain strings, which is all the command keyword/argument matching needs.
    private fun extractMessage(message: CharSequence): Pair<List<String>, CharSequence>? {
        val messageParts = try {
            message.toString().split("\\s+".toRegex()).dropLastWhile { it.isEmpty() }
        } catch (e: Exception) {
            Timber.e(e, "## parseSlashCommand() : split failed")
            null
        }

        // test if the string cut fails
        if (messageParts.isNullOrEmpty()) {
            return null
        }

        val slashCommand = messageParts.first()
        val trimmedMessage = message.subSequence(slashCommand.length, message.length).trimSequence()

        return messageParts to trimmedMessage
    }

    private fun CharSequence.trimSequence(): CharSequence {
        var start = 0
        var end = length
        while (start < end && this[start].isWhitespace()) start++
        while (end > start && this[end - 1].isWhitespace()) end--
        return subSequence(start, end)
    }

    private val notSupportedThreadsCommands: List<Command> by lazy {
        Command.values().filter {
            !it.isThreadCommand
        }
    }

    /**
     * Checks whether or not the current command is not supported by threads.
     * @param isInThreadTimeline if its true we are in a thread timeline
     * @param slashCommand the slash command that will be checked
     * @return The command that is not supported
     */
    private fun getNotSupportedByThreads(isInThreadTimeline: Boolean, slashCommand: String): Command? {
        return if (isInThreadTimeline) {
            notSupportedThreadsCommands.firstOrNull {
                it.command == slashCommand
            }
        } else {
            null
        }
    }

    private fun isRoomPermalink(candidate: String): Boolean {
        if (candidate.any { it.isWhitespace() }) return false
        val looksLikeLink = "://" in candidate || candidate.startsWith(PermalinkService.MATRIX_URI_SCHEME_PREFIX, ignoreCase = true)
        return looksLikeLink && PermalinkParser.parse(candidate) is PermalinkData.RoomLink
    }

    // [options] are the tokens after the user id: an optional bare cooldown in ms, plus optional
    // from:/until: epoch bounds, in any order.
    private fun parseMassRedactOptions(userId: String, options: List<String>): ParsedCommand {
        var cooldown: Long? = null
        var fromTs: Long? = null
        var toTs: Long? = null
        for (option in options) {
            val separator = option.indexOf(':')
            val key = if (separator == -1) null else option.substring(0, separator).lowercase()
            val rawValue = if (separator == -1) option else option.substring(separator + 1)
            val value = rawValue.toLongOrNull()?.takeIf { it >= 0 } ?: return ParsedCommand.ErrorSyntax(Command.MASS_REDACT)
            when (key) {
                null -> if (cooldown == null) cooldown = value else return ParsedCommand.ErrorSyntax(Command.MASS_REDACT)
                "from" -> fromTs = epochToMillis(value)
                "until" -> toTs = epochToMillis(value)
                else -> return ParsedCommand.ErrorSyntax(Command.MASS_REDACT)
            }
        }
        if (fromTs != null && toTs != null && fromTs > toTs) return ParsedCommand.ErrorSyntax(Command.MASS_REDACT)
        return ParsedCommand.MassRedact(userId, cooldown, MassRedactionRange(fromTs, toTs))
    }

    // Accept the bound in either unit: anything below the threshold can only be a sensible date when read
    // as seconds (as milliseconds it would be 1973 or earlier).
    private fun epochToMillis(value: Long) = if (value < EPOCH_MILLIS_THRESHOLD) value * 1000 else value

    private fun trimParts(message: CharSequence, messageParts: List<String>): String? {
        val partsSize = messageParts.sumOf { it.length }
        val gapsNumber = messageParts.size - 1
        return message.substring(partsSize + gapsNumber).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
    }
}
