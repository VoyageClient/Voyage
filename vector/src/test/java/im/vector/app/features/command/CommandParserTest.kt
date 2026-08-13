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

private const val A_SPACE_ID = "!my-space-id"

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

    private fun test(message: String, expectedResult: ParsedCommand) {
        val commandParser = CommandParser(fakeVectorPreferences.instance)
        val result = commandParser.parseSlashCommand(message, null, false)
        result shouldBeEqualTo expectedResult
    }
}
