/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.command

import im.vector.app.test.fakes.FakeVectorPreferences
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.relation.MassRedactionRange
import java.util.Calendar

private const val A_SPACE_ID = "!my-space-id"
private const val A_USER_ID = "@alice:matrix.org"

class CommandParserTest {
    private val fakeVectorPreferences = FakeVectorPreferences()

    @Test
    fun parseSlashCommandEmpty() {
        test("/", ParsedCommand.ErrorEmptySlashCommand)
    }

    @Test
    fun parseSlashCommandUnknown() {
        test("/unknown", ParsedCommand.ErrorUnknownSlashCommand("/unknown"))
        test("/unknown with param", ParsedCommand.ErrorUnknownSlashCommand("/unknown"))
    }

    @Test
    fun parseSlashAddToSpaceCommand() {
        test("/addToSpace $A_SPACE_ID", ParsedCommand.AddToSpace(A_SPACE_ID))
    }
    @Test
    fun parseSlashJoinSpaceCommand() {
        test("/joinSpace $A_SPACE_ID", ParsedCommand.JoinSpace(A_SPACE_ID))
    }

    @Test
    fun parseSlashCommandNotACommand() {
        test("", ParsedCommand.ErrorNotACommand)
        test("test", ParsedCommand.ErrorNotACommand)
        test("// test", ParsedCommand.ErrorNotACommand)
    }

    @Test
    fun parseSlashCommandEmote() {
        test("/me test", ParsedCommand.SendEmote("test"))
        test("/me", ParsedCommand.ErrorSyntax(Command.EMOTE))
    }

    @Test
    fun parseSlashCommandKick() {
        // Nominal
        test("/kick @foo:bar", ParsedCommand.KickUser("@foo:bar", null))
        // With a reason
        test("/kick @foo:bar a reason", ParsedCommand.KickUser("@foo:bar", "a reason"))
        // Trim the reason
        test("/kick @foo:bar    a    reason    ", ParsedCommand.KickUser("@foo:bar", "a    reason"))
        // Alias
        test("/remove @foo:bar", ParsedCommand.KickUser("@foo:bar", null))
        // Mass-redaction flag, with and without a reason, on either side of it
        test("/kick @foo:bar massredact", ParsedCommand.KickUser("@foo:bar", null, true))
        test("/kick @foo:bar massredact a reason", ParsedCommand.KickUser("@foo:bar", "a reason", true))
        test("/kick @foo:bar a reason massredact", ParsedCommand.KickUser("@foo:bar", "a reason", true))
        test("/kick @foo:bar MassRedact a reason", ParsedCommand.KickUser("@foo:bar", "a reason", true))
        // The old token is now ordinary reason text
        test("/kick @foo:bar redact", ParsedCommand.KickUser("@foo:bar", "redact"))
        // Only a leading or trailing occurrence is the flag
        test("/kick @foo:bar a massredact reason", ParsedCommand.KickUser("@foo:bar", "a massredact reason"))
        // Error
        test("/kick", ParsedCommand.ErrorSyntax(Command.KICK_USER))
    }

    @Test
    fun parseSlashCommandBan() {
        test("/ban @foo:bar", ParsedCommand.BanUser("@foo:bar", null))
        test("/ban @foo:bar a reason", ParsedCommand.BanUser("@foo:bar", "a reason"))
        test("/ban @foo:bar massredact", ParsedCommand.BanUser("@foo:bar", null, true))
        test("/ban @foo:bar massredact a reason", ParsedCommand.BanUser("@foo:bar", "a reason", true))
        test("/ban @foo:bar a reason massredact", ParsedCommand.BanUser("@foo:bar", "a reason", true))
        test("/ban @foo:bar redact", ParsedCommand.BanUser("@foo:bar", "redact"))
        test("/ban", ParsedCommand.ErrorSyntax(Command.BAN_USER))
        test("/ban not-a-user", ParsedCommand.ErrorSyntax(Command.BAN_USER))
    }

    @Test
    fun parseSlashCommandJumpToDate() {
        test("/jumptodate 2026-01-02", ParsedCommand.JumpToDate("2026-01-02", ymd(2026, 1, 2)))
        test("/jumptodate 20260102", ParsedCommand.JumpToDate("20260102", ymd(2026, 1, 2)))
        test("/jumptodate 2026/01/02", ParsedCommand.JumpToDate("2026/01/02", ymd(2026, 1, 2)))
        test("/jumptodate not-a-date", ParsedCommand.ErrorSyntax(Command.JUMP_TO_DATE))
        test("/jumptodate", ParsedCommand.ErrorSyntax(Command.JUMP_TO_DATE))
    }

    @Test
    fun parseSlashCommandJumpTo() {
        test("/jumpto \$DQX4VfNeVDCt83Fo5apF862XoS101ZW-xhQRbrE9oR8", ParsedCommand.JumpToEvent("\$DQX4VfNeVDCt83Fo5apF862XoS101ZW-xhQRbrE9oR8"))

        val matrixTo = "https://matrix.to/#/!l6JeugDLfp4TTrYZORQJEPqCSCfWlFoy26BI-WP3SU4/" +
                "\$DQX4VfNeVDCt83Fo5apF862XoS101ZW-xhQRbrE9oR8?via=matrix.org"
        test("/jumpto $matrixTo", ParsedCommand.JumpToPermalink(matrixTo))

        val matrixUri = "matrix:roomid/l6JeugDLfp4TTrYZORQJEPqCSCfWlFoy26BI-WP3SU4/e/DQX4VfNeVDCt83Fo5apF862XoS101ZW-xhQRbrE9oR8"
        test("/jumpto $matrixUri", ParsedCommand.JumpToPermalink(matrixUri))

        test("/jumpto", ParsedCommand.ErrorSyntax(Command.JUMP_TO))
        test("/jumpto not-an-event", ParsedCommand.ErrorSyntax(Command.JUMP_TO))
        test("/jumpto https://matrix.to/#/@alice:example.org", ParsedCommand.ErrorSyntax(Command.JUMP_TO))
    }

    @Test
    fun parseMassRedactDefaultsToMessagesOnly() {
        test("/massredact $A_USER_ID", ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(messagesOnly = true)))
    }

    @Test
    fun parseMassRedactEpochBounds() {
        // Seconds and milliseconds are both accepted and normalised to ms.
        test("/massredact $A_USER_ID after:1700000000 before:1700003600000",
                ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(1700000000_000L, 1700003600000L, messagesOnly = true)))
    }

    @Test
    fun parseMassRedactDelayAndSingleBound() {
        test("/massredact $A_USER_ID delay:500 before:1700000000",
                ParsedCommand.MassRedact(A_USER_ID, 500L, MassRedactionRange(null, 1700000000_000L, messagesOnly = true)))
    }

    @Test
    fun parseMassRedactOptionsAreOrderIndependent() {
        test("/massredact $A_USER_ID type:all before:1700000000 delay:500",
                ParsedCommand.MassRedact(A_USER_ID, 500L, MassRedactionRange(null, 1700000000_000L, messagesOnly = false)))
    }

    @Test
    fun parseMassRedactDateBounds() {
        test("/massredact $A_USER_ID after:2026-01-02 before:2026-03-04",
                ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(ymd(2026, 1, 2), ymd(2026, 3, 4), messagesOnly = true)))
        test("/massredact $A_USER_ID after:20260102 before:2026/03/04",
                ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(ymd(2026, 1, 2), ymd(2026, 3, 4), messagesOnly = true)))
        // A bare year or year-month means the start of it.
        test("/massredact $A_USER_ID after:2026 before:2026-03",
                ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(ymd(2026, 1, 1), ymd(2026, 3, 1), messagesOnly = true)))
    }

    @Test
    fun parseMassRedactType() {
        test("/massredact $A_USER_ID type:messages",
                ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(messagesOnly = true)))
        test("/massredact $A_USER_ID type:ALL",
                ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(messagesOnly = false)))
        test("/massredact $A_USER_ID type:state", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
    }

    @Test
    fun parseMassRedactRejectsPositionalOptions() {
        // The cooldown and the messages-only flag are now named options only.
        test("/massredact $A_USER_ID 500", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
        test("/massredact $A_USER_ID messagesOnly", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
        test("/massredact $A_USER_ID messagesOnly:true", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
    }

    @Test
    fun parseMassRedactRejectsBadBounds() {
        test("/massredact $A_USER_ID before:not-a-date", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
        test("/massredact $A_USER_ID after:2026-02-31", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
        test("/massredact $A_USER_ID after:1700003600 before:1700000000", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
        test("/massredact $A_USER_ID delay:500 delay:600", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
        test("/massredact $A_USER_ID delay:-1", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
    }

    private fun ymd(year: Int, month: Int, day: Int): Long =
            Calendar.getInstance().apply { clear(); set(year, month - 1, day) }.timeInMillis

    private fun test(message: String, expectedResult: ParsedCommand) {
        val commandParser = CommandParser(fakeVectorPreferences.instance)
        val result = commandParser.parseSlashCommand(message, null, false)
        result shouldBeEqualTo expectedResult
    }
}
