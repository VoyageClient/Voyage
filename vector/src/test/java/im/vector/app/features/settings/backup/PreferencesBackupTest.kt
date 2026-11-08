/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ALICE = "@alice:example.org"
private const val BOB = "@bob:example.org"
private const val ALICE_SESSION = "0123456789abcdef0123456789abcdef"
private const val BOB_SESSION = "fedcba9876543210fedcba9876543210"
private const val ALICE_NEW_SESSION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val STALE_SESSION = "deadbeefdeadbeefdeadbeefdeadbeef"
private const val VPN_KEY = "SETTINGS_VPN_EXCLUDED_SESSION_IDS_KEY"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreferencesBackupTest {

    private val accounts = listOf(BackupAccount(ALICE, ALICE_SESSION), BackupAccount(BOB, BOB_SESSION))

    private val source: Map<String, Any> = mapOf(
            "SETTINGS_THEME" to "dark",
            "SETTINGS_FONT_SIZE" to 3,
            "SETTINGS_SOME_LONG" to 12345678901L,
            "SETTINGS_SOME_FLOAT" to 1.5f,
            "SETTINGS_SHOW_REACTIONS_KEY_!room:example.org" to false,
            "SETTINGS_MEDIA_PREVIEW_KEY_!room:example.org" to "private",
            "SETTINGS_CUSTOM_EMOJI_FONT_NAME_KEY" to "Blobmoji.ttf",
            "SETTINGS_UA_CLIENT_$ALICE" to "element",
            "SETTINGS_REDACTION_PRESERVE_${ALICE}_!room:example.org" to true,
            "SETTINGS_PERSISTED_SPACE_BACKSTACK_$ALICE_SESSION" to "!space:example.org",
            "SETTINGS_UA_CLIENT_$BOB" to "fluffychat",
            VPN_KEY to setOf(ALICE_SESSION, STALE_SESSION),
            "SETTINGS_UA_CLIENT_@gone:example.org" to "nheko",
            "SETTINGS_LAST_ACTIVE_SESSION_ID" to ALICE_SESSION,
            "IS_ON_RUST_CRYPTO" to true,
            "UP_INSTANCE_FOR_SESSION_$ALICE_SESSION" to "instance",
            "SETTINGS_QUICK_REACTIONS_KEY" to "[\"👍\"]",
            "SETTINGS_MEDIA_PREVIEW_KEY" to "off",
            "SETTINGS_BLOCK_INVITES_KEY" to true,
            "SETTINGS_PUSH_RULE_MENTIONS_PREFERENCE_KEY" to false,
    )

    private fun roundTrip(): PreferencesBackupFile = PreferencesBackup.parse(PreferencesBackup.export(source, accounts, exportedAt = 0))

    @Test
    fun `the export keeps real ids and leaves out device and server state`() {
        val json = PreferencesBackup.export(source, accounts, exportedAt = 0)
        json shouldNotContain "{user_id}"
        json shouldNotContain "{session_id}"

        val backup = PreferencesBackup.parse(json)
        backup.global.keys shouldContain "SETTINGS_THEME"
        backup.global.keys shouldContain "SETTINGS_SHOW_REACTIONS_KEY_!room:example.org"
        backup.global.keys shouldContain "SETTINGS_MEDIA_PREVIEW_KEY_!room:example.org"
        backup.global.keys shouldContain "SETTINGS_CUSTOM_EMOJI_FONT_NAME_KEY"
        listOf(
                "SETTINGS_UA_CLIENT_@gone:example.org",
                "SETTINGS_LAST_ACTIVE_SESSION_ID",
                "IS_ON_RUST_CRYPTO",
                "SETTINGS_QUICK_REACTIONS_KEY",
                "SETTINGS_MEDIA_PREVIEW_KEY",
                "SETTINGS_BLOCK_INVITES_KEY",
                "SETTINGS_PUSH_RULE_MENTIONS_PREFERENCE_KEY",
        ).forEach { backup.global.keys shouldNotContain it }
        backup.global.keys.none { it.startsWith("UP_") } shouldBeEqualTo true

        backup.accounts.getValue(ALICE).sessionId shouldBeEqualTo ALICE_SESSION
        assertEquals(
                mapOf(
                        "SETTINGS_UA_CLIENT_$ALICE" to "element",
                        "SETTINGS_REDACTION_PRESERVE_${ALICE}_!room:example.org" to true,
                        "SETTINGS_PERSISTED_SPACE_BACKSTACK_$ALICE_SESSION" to "!space:example.org",
                        VPN_KEY to setOf(ALICE_SESSION),
                ),
                backup.accounts.getValue(ALICE).settings,
        )
        assertEquals(mapOf("SETTINGS_UA_CLIENT_$BOB" to "fluffychat"), backup.accounts.getValue(BOB).settings)
    }

    @Test
    fun `value types survive the round trip`() {
        val global = roundTrip().global

        global["SETTINGS_FONT_SIZE"] shouldBeEqualTo 3
        global["SETTINGS_SOME_LONG"] shouldBeEqualTo 12345678901L
        global["SETTINGS_SOME_FLOAT"] shouldBeEqualTo 1.5f
        global[VPN_KEY] shouldBeEqualTo setOf(STALE_SESSION)
    }

    @Test
    fun `restoring onto a new session of the same user maps session keys to it`() {
        val target = listOf(BackupAccount(ALICE, ALICE_NEW_SESSION))

        val changes = PreferencesBackup.restore(roundTrip(), emptyMap<String, Any>(), restoreGlobal = true, userIds = setOf(ALICE), accounts = target)

        changes.writes["SETTINGS_UA_CLIENT_$ALICE"] shouldBeEqualTo "element"
        changes.writes["SETTINGS_PERSISTED_SPACE_BACKSTACK_$ALICE_NEW_SESSION"] shouldBeEqualTo "!space:example.org"
        changes.writes.keys shouldNotContain "SETTINGS_PERSISTED_SPACE_BACKSTACK_$ALICE_SESSION"
        changes.writes[VPN_KEY] shouldBeEqualTo setOf(STALE_SESSION, ALICE_NEW_SESSION)
        changes.writes.keys shouldNotContain "SETTINGS_UA_CLIENT_$BOB"
    }

    @Test
    fun `an account that is not signed in gets its user keys but not its session keys`() {
        val changes = PreferencesBackup.restore(roundTrip(), emptyMap<String, Any>(), restoreGlobal = false, userIds = setOf(ALICE), accounts = emptyList())

        assertEquals(
                mapOf(
                        "SETTINGS_UA_CLIENT_$ALICE" to "element",
                        "SETTINGS_REDACTION_PRESERVE_${ALICE}_!room:example.org" to true,
                ),
                changes.writes,
        )
    }

    @Test
    fun `a restored scope is replaced while other scopes, device and server state are untouched`() {
        val current = mapOf(
                "SETTINGS_LOCAL_ONLY" to true,
                "SETTINGS_ROOM_OVERRIDE_${ALICE}_!other:example.org" to "x",
                "SETTINGS_UA_CLIENT_$BOB" to "local",
                "IS_ON_RUST_CRYPTO" to true,
                "SETTINGS_QUICK_REACTIONS_KEY" to "[\"🎉\"]",
        )

        val changes = PreferencesBackup.restore(roundTrip(), current, restoreGlobal = true, userIds = setOf(ALICE), accounts = accounts)

        changes.removals shouldBeEqualTo setOf("SETTINGS_LOCAL_ONLY", "SETTINGS_ROOM_OVERRIDE_${ALICE}_!other:example.org")
        changes.writes.keys shouldNotContain "SETTINGS_UA_CLIENT_$BOB"
        changes.writes.keys shouldNotContain "IS_ON_RUST_CRYPTO"
        changes.writes.keys shouldNotContain "SETTINGS_QUICK_REACTIONS_KEY"
    }

    @Test
    fun `server state in a hand-edited file is ignored`() {
        val json = """{"format":"${PreferencesBackup.FORMAT}","version":1,"settings":{
            "SETTINGS_QUICK_REACTIONS_KEY":{"type":"string","value":"[]"},
            "SETTINGS_THEME":{"type":"string","value":"light"}}}"""

        PreferencesBackup.parse(json).global shouldBeEqualTo mapOf("SETTINGS_THEME" to "light")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a json file that is not an export is rejected`() {
        PreferencesBackup.parse("{\"hello\": 1}")
    }
}
