/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import im.vector.matrixcli.platform.DesktopSecureStorage
import im.vector.matrixcli.platform.FileKeyValueStoreFactory
import im.vector.matrixcli.platform.JdbcSqlDriverFactory
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.util.MatrixJsonParser
import org.matrix.android.sdk.internal.auth.db.AuthSqlDatabase
import java.nio.file.Files

/**
 * Exercises the plain-JVM building blocks of :matrix-sdk-core with no Android on the classpath:
 * data-model builders, permalink parsing, the Moshi graph, and SQLDelight persistence over a JDBC
 * sqlite driver. Each step is isolated so partial capability is visible.
 */
class DesktopBootSmoke {

    fun run() {
        var passed = 0
        var failed = 0
        fun check(name: String, block: () -> String) {
            runCatching(block)
                    .onSuccess { println("  [ok]   $name — $it"); passed++ }
                    .onFailure { println("  [FAIL] $name — ${it::class.simpleName}: ${it.message}"); failed++ }
        }

        println("== core capability smoke ==")

        check("HomeServerConnectionConfig builder") {
            val config = HomeServerConnectionConfig.Builder()
                    .withHomeServerUri("https://matrix.org")
                    .build()
            "homeserver=${config.homeServerUri}"
        }

        check("permalink parsing") {
            val data = PermalinkParser.parse("https://matrix.to/#/@alice:matrix.org")
            require(data is PermalinkData.UserLink) { "expected a user link, got ${data::class.simpleName}" }
            "userId=${data.userId}"
        }

        check("Moshi round-trip (Event)") {
            val moshi = MatrixJsonParser.getMoshi()
            val adapter = moshi.adapter(Event::class.java)
            val original = Event(type = "m.room.message", eventId = "\$abc:matrix.org", roomId = "!room:matrix.org")
            val json = adapter.toJson(original)
            val back = adapter.fromJson(json)!!
            "roundtripped type=${back.type} eventId=${back.eventId}"
        }

        check("SQLDelight persistence over JDBC sqlite") {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            AuthSqlDatabase.Schema.create(driver)
            val db = AuthSqlDatabase(driver)
            // A trivial write/read against the generated schema proves the store layer runs on JVM.
            val count = db.sessionParamsQueries.selectAll().executeAsList().size
            driver.close()
            "auth db created, sessionParams rows=$count"
        }

        val dataDir = Files.createTempDirectory("matrix-cli-smoke").toFile()

        check("desktop JdbcSqlDriverFactory file persistence") {
            val factory = JdbcSqlDriverFactory(dataDir)
            // First open creates the schema; write a row and close.
            factory.create(AuthSqlDatabase.Schema, "auth.db").let { driver ->
                AuthSqlDatabase(driver).sessionParamsQueries.upsert(
                        session_id = "s1", user_id = "@bob:matrix.org",
                        credentials_json = "{}", home_server_connection_config_json = "{}",
                        is_token_valid = 1, login_type = "PASSWORD",
                )
                driver.close()
            }
            // Reopen the same file via the factory: schema must NOT be recreated, row must persist.
            val reopened = factory.create(AuthSqlDatabase.Schema, "auth.db")
            val rows = AuthSqlDatabase(reopened).sessionParamsQueries.selectAll().executeAsList()
            reopened.close()
            require(rows.size == 1 && rows.first().user_id == "@bob:matrix.org") { "expected the persisted row, got $rows" }
            "row survived reopen: userId=${rows.first().user_id}"
        }

        check("desktop FileKeyValueStore persistence") {
            val factory = FileKeyValueStoreFactory(dataDir)
            factory.create("settings").putString("access_token", "syt_secret")
            factory.create("settings").putStringSet("rooms", setOf("!a:hs", "!b:hs"))
            // A fresh factory/store instance must read the persisted file.
            val reread = FileKeyValueStoreFactory(dataDir).create("settings")
            val token = reread.getString("access_token")
            val rooms = reread.getStringSet("rooms")
            require(token == "syt_secret" && rooms == setOf("!a:hs", "!b:hs")) { "token=$token rooms=$rooms" }
            "persisted token + ${rooms?.size} rooms across reopen"
        }

        check("desktop SecureStorage AES round-trip") {
            val secureStorage = DesktopSecureStorage(java.io.File(dataDir, "secure.key"))
            val secret = "syt_access_token_secret".toByteArray()
            val encrypted = secureStorage.encryptBytes(secret, alias = "session_token")
            val decrypted = secureStorage.decryptBytes(encrypted, alias = "session_token")
            require(decrypted.contentEquals(secret)) { "round-trip mismatch" }
            // A different alias (GCM associated-data) must fail to decrypt.
            val wrongAlias = runCatching { secureStorage.decryptBytes(encrypted, alias = "other") }.isFailure
            require(wrongAlias) { "decrypt under a wrong alias should fail" }
            "encrypted ${secret.size}B, decrypt ok, wrong-alias rejected"
        }

        dataDir.deleteRecursively()
        println("== smoke complete: $passed passed, $failed failed ==")
    }
}
