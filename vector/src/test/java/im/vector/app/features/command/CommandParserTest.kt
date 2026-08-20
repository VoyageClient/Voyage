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
        // Error
        test("/kick", ParsedCommand.ErrorSyntax(Command.KICK_USER))
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
    fun parseMassRedactEpochBounds() {
        // Seconds and milliseconds are both accepted and normalised to ms.
        test("/massredact $A_USER_ID after:1700000000 before:1700003600000",
                ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(1700000000_000L, 1700003600000L)))
    }

    @Test
    fun parseMassRedactCooldownAndSingleBound() {
        test("/massredact $A_USER_ID 500 before:1700000000",
                ParsedCommand.MassRedact(A_USER_ID, 500L, MassRedactionRange(null, 1700000000_000L)))
    }

    @Test
    fun parseMassRedactYmdBounds() {
        test("/massredact $A_USER_ID after:2026-01-02 before:2026-03-04",
                ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(ymd(2026, 1, 2), ymd(2026, 3, 4))))
    }

    @Test
    fun parseMassRedactMessagesOnly() {
        test("/massredact $A_USER_ID messagesOnly",
                ParsedCommand.MassRedact(A_USER_ID, null, MassRedactionRange(messagesOnly = true)))
        test("/massredact $A_USER_ID 200 before:1700000000 messagesOnly:true",
                ParsedCommand.MassRedact(A_USER_ID, 200L, MassRedactionRange(null, 1700000000_000L, messagesOnly = true)))
    }

    @Test
    fun parseMassRedactRejectsBadBounds() {
        // Bare year reads as an implausibly small epoch, unparseable date, and after > before.
        test("/massredact $A_USER_ID after:2026", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
        test("/massredact $A_USER_ID before:not-a-date", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
        test("/massredact $A_USER_ID after:2026-02-31", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
        test("/massredact $A_USER_ID after:1700003600 before:1700000000", ParsedCommand.ErrorSyntax(Command.MASS_REDACT))
    }

    private fun ymd(year: Int, month: Int, day: Int): Long =
            Calendar.getInstance().apply { clear(); set(year, month - 1, day) }.timeInMillis

    private fun test(message: String, expectedResult: ParsedCommand) {
        val commandParser = CommandParser(fakeVectorPreferences.instance)
        val result = commandParser.parseSlashCommand(message, null, false)
        result shouldBeEqualTo expectedResult
    }
}
