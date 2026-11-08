/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class BackupAccount(val userId: String, val sessionId: String)

data class ExportedAccount(val sessionId: String, val settings: Map<String, Any>)

data class PreferencesBackupFile(
        val global: Map<String, Any>,
        val accounts: Map<String, ExportedAccount>,
)

data class PreferencesChanges(val writes: Map<String, Any>, val removals: Set<String>)

/**
 * Converts the default SharedPreferences to and from a JSON settings export. Per-account settings live in the
 * same file as global ones, told apart only by the user id or session id embedded in their key.
 */
object PreferencesBackup {

    const val FORMAT = "im.voyage.preferences"
    const val VERSION = 1

    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_STRING_SET = "string_set"

    private val EXCLUDED_KEYS = setOf(
            // Tied to this install, its pushers, its crypto store or files it holds.
            "SETTINGS_LAST_ACTIVE_SESSION_ID",
            "HAD_EXISTING_LEGACY_DATA",
            "IS_ON_RUST_CRYPTO",
            "DID_MIGRATE_TO_NOTIFICATION_REWORK",
            "SETTINGS_THREAD_MESSAGES_SYNCED",
            "SETTINGS_UA_MIGRATED_V2",
            "SETTINGS_SECURITY_USE_PIN_CODE_FLAG",
            "SETTINGS_SECURITY_USE_BIOMETRICS_FLAG",
            "SETTINGS_ENABLE_THIS_DEVICE_PREFERENCE_KEY",
            "SETTINGS_NOTIFICATION_PERMISSION_ASKED_KEY",
            "SETTINGS_NOTIFICATION_RINGTONE_PREFERENCE_KEY",
            "SETTINGS_NOTIFICATION_RINGTONE_SELECTION_PREFERENCE_KEY",
            "SETTINGS_VPN_LAST_OBSERVED_ON_KEY",
            "SETTINGS_VPN_OFF_ACKNOWLEDGED_KEY",
            "SUPPORTED_LOCALES_CACHE_KEY",
            "SUPPORTED_LOCALES_CACHE_BUILD_KEY",
            "ROOM_DETAIL_ACTIVITY_SHORTCUT_UPDATED",
            "PUSH_GATEWAY",
            "PREFS_CRASH_KEY",
            PreferencesBackupManager.KEY_LAST_LOCATION,
            // Local copies of server state (account data, push rules, crypto store), reloaded from it.
            "SETTINGS_QUICK_REACTIONS_KEY",
            "SETTINGS_QUICK_REACTIONS_SYNCED_KEY",
            "SETTINGS_MEDIA_PREVIEW_KEY",
            "SETTINGS_MEDIA_PREVIEW_SYNCED_KEY",
            "SETTINGS_HIDE_INVITE_AVATARS_KEY",
            "SETTINGS_BLOCK_INVITES_KEY",
            "SETTINGS_ALLOW_INTEGRATIONS_KEY",
            "SETTINGS_ENABLE_ALL_NOTIF_PREFERENCE_KEY",
            "SETTINGS_ENCRYPTION_NEVER_SENT_TO_PREFERENCE_KEY",
    )
    private val EXCLUDED_KEY_PREFIXES = listOf(
            "UP_",
            "SETTINGS_PUSH_RULE_",
            "SETTINGS_LAST_CROSS_SIGNING_STATE_KEY_",
            "SETTINGS_NEW_LOGIN_ALERT_SHOWN_FOR_DEVICE_",
            "SETTINGS_UNVERIFIED_SESSIONS_ALERT_LAST_SHOWN_MILLIS_",
            "SETTINGS_UA_CACHE_",
            "SETTINGS_UA_SDKSHA_",
            "massredact_",
    )

    // Keys of accounts that are no longer signed in here are leftovers; they are neither exported nor cleared.
    private val USER_ID_IN_KEY = Regex("@[^:\\s]+:[A-Za-z0-9.\\-]+")
    private val SESSION_ID_IN_KEY = Regex("(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])")

    private sealed interface Owner {
        object Global : Owner
        data class Account(val account: BackupAccount) : Owner
        object Skip : Owner
    }

    private fun isExcluded(key: String): Boolean = key in EXCLUDED_KEYS || EXCLUDED_KEY_PREFIXES.any { key.startsWith(it) }

    private fun classify(key: String, accounts: List<BackupAccount>): Owner {
        if (isExcluded(key)) return Owner.Skip
        // Longest first, so "@al:x.org" can't claim a key that belongs to "@al:x.org.uk".
        val owner = accounts.sortedByDescending { it.userId.length }.firstOrNull { key.contains(it.userId) }
                ?: accounts.firstOrNull { key.contains(it.sessionId) }
        return when {
            owner != null -> Owner.Account(owner)
            USER_ID_IN_KEY.containsMatchIn(key) || SESSION_ID_IN_KEY.containsMatchIn(key) -> Owner.Skip
            else -> Owner.Global
        }
    }

    fun export(all: Map<String, *>, accounts: List<BackupAccount>, exportedAt: Long): String {
        val global = sortedMapOf<String, Any>()
        val distinctAccounts = accounts.distinctBy { it.userId }
        val perAccount = distinctAccounts.associateWith { sortedMapOf<String, Any>() }
        fun sectionOf(account: BackupAccount) = perAccount.getValue(distinctAccounts.first { it.userId == account.userId })

        all.forEach { (key, value) ->
            value ?: return@forEach
            when (val owner = classify(key, accounts)) {
                Owner.Skip -> Unit
                is Owner.Account -> sectionOf(owner.account)[key] = value
                Owner.Global -> {
                    if (value is Set<*>) {
                        // A shared set naming sessions (e.g. VPN exclusions): each account carries its own membership.
                        val strings = value.filterIsInstance<String>().toSet()
                        val owned = accounts.filter { it.sessionId in strings }
                        owned.forEach { sectionOf(it)[key] = setOf(it.sessionId) }
                        global[key] = strings - owned.map { it.sessionId }.toSet()
                    } else {
                        global[key] = value
                    }
                }
            }
        }
        return JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("exported_at", exportedAt)
                .put("settings", encodeEntries(global))
                .put("accounts", JSONArray().apply {
                    perAccount.forEach { (account, entries) ->
                        put(
                                JSONObject()
                                        .put("user_id", account.userId)
                                        .put("session_id", account.sessionId)
                                        .put("settings", encodeEntries(entries))
                        )
                    }
                })
                .toString(2)
    }

    /** @throws IllegalArgumentException if [text] is not a settings export this version can read. */
    fun parse(text: String): PreferencesBackupFile {
        try {
            val root = JSONObject(text)
            require(root.optString("format") == FORMAT) { "Not a settings export" }
            require(root.optInt("version") in 1..VERSION) { "Unsupported settings export version" }
            val accounts = linkedMapOf<String, ExportedAccount>()
            val accountArray = root.optJSONArray("accounts") ?: JSONArray()
            for (i in 0 until accountArray.length()) {
                val account = accountArray.getJSONObject(i)
                accounts[account.getString("user_id")] = ExportedAccount(
                        sessionId = account.getString("session_id"),
                        settings = decodeEntries(account.optJSONObject("settings")),
                )
            }
            return PreferencesBackupFile(global = decodeEntries(root.optJSONObject("settings")), accounts = accounts)
        } catch (e: JSONException) {
            throw IllegalArgumentException(e)
        }
    }

    /**
     * Computes what restoring the chosen scopes of [backup] changes. Each restored scope is replaced, not merged:
     * a setting the export left at its default is removed here too. The exported session id is swapped for the
     * local session of the same user; session keys of a user not signed in here are dropped.
     */
    fun restore(
            backup: PreferencesBackupFile,
            current: Map<String, *>,
            restoreGlobal: Boolean,
            userIds: Set<String>,
            accounts: List<BackupAccount>,
    ): PreferencesChanges {
        val writes = linkedMapOf<String, Any>()
        val removals = linkedSetOf<String>()

        if (restoreGlobal) {
            current.keys.filter { classify(it, accounts) == Owner.Global && it !in backup.global }.forEach { removals += it }
            writes.putAll(backup.global)
        }
        for (userId in userIds) {
            val exported = backup.accounts[userId] ?: continue
            val local = accounts.firstOrNull { it.userId == userId }
            fun mapId(id: String): String? = when {
                !id.contains(exported.sessionId) -> id
                local == null -> null
                else -> id.replace(exported.sessionId, local.sessionId)
            }

            val accountWrites = linkedMapOf<String, Any>()
            exported.settings.forEach { (exportedKey, value) ->
                val key = mapId(exportedKey) ?: return@forEach
                // A set keyed by neither id is shared between accounts: merge this account's members into it.
                if (value is Set<*> && !exportedKey.contains(userId) && !exportedKey.contains(exported.sessionId)) {
                    val members = value.filterIsInstance<String>().mapNotNull { mapId(it) }
                    if (members.isEmpty()) return@forEach
                    @Suppress("UNCHECKED_CAST")
                    val base = (writes[key] ?: current[key]?.takeIf { key !in removals }) as? Set<String> ?: emptySet()
                    writes[key] = base + members
                } else {
                    accountWrites[key] = value
                }
            }
            if (local != null) {
                current.keys
                        .filter { key -> (classify(key, accounts) as? Owner.Account)?.account?.userId == userId && key !in accountWrites }
                        .forEach { removals += it }
            }
            writes.putAll(accountWrites)
        }
        removals.removeAll(writes.keys)
        return PreferencesChanges(writes, removals)
    }

    private fun encodeEntries(entries: Map<String, Any>): JSONObject {
        val json = JSONObject()
        entries.forEach { (key, value) ->
            val (type, encoded) = when (value) {
                is Boolean -> TYPE_BOOLEAN to value
                is Int -> TYPE_INT to value
                is Long -> TYPE_LONG to value
                is Float -> TYPE_FLOAT to value.toDouble()
                is String -> TYPE_STRING to value
                is Set<*> -> TYPE_STRING_SET to JSONArray(value.filterIsInstance<String>().sorted())
                else -> return@forEach
            }
            json.put(key, JSONObject().put("type", type).put("value", encoded))
        }
        return json
    }

    private fun decodeEntries(json: JSONObject?): Map<String, Any> {
        json ?: return emptyMap()
        val result = linkedMapOf<String, Any>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (isExcluded(key)) continue
            val entry = json.getJSONObject(key)
            result[key] = when (val type = entry.getString("type")) {
                TYPE_BOOLEAN -> entry.getBoolean("value")
                TYPE_INT -> entry.getInt("value")
                TYPE_LONG -> entry.getLong("value")
                TYPE_FLOAT -> entry.getDouble("value").toFloat()
                TYPE_STRING -> entry.getString("value")
                TYPE_STRING_SET -> entry.getJSONArray("value").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                else -> throw IllegalArgumentException("Unknown setting type $type")
            }
        }
        return result
    }
}
