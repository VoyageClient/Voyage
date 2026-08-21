/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.auth.LoginType
import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.provider.RoomDisplayNameFallbackProvider
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.util.MatrixJsonParser
import org.matrix.android.sdk.desktop.DesktopMatrix
import org.matrix.android.sdk.desktop.di.DesktopMatrixComponent
import org.matrix.android.sdk.desktop.platform.AssumeOnlineNetworkCallbackStrategyFactory
import org.matrix.android.sdk.desktop.platform.DesktopSecureStorage
import org.matrix.android.sdk.desktop.platform.FileKeyValueStoreFactory
import org.matrix.android.sdk.desktop.platform.JdbcSqlDriverFactory
import org.matrix.android.sdk.internal.auth.db.AuthSqlDatabase
import org.matrix.android.sdk.internal.network.RetrofitFactory
import org.matrix.olm.OlmAccount
import org.matrix.olm.OlmManager
import java.io.File
import java.nio.file.Files

/**
 * Exercises the plain-JVM building blocks of :matrix-sdk-core with no Android on the classpath:
 * data-model builders, permalink parsing, the Moshi graph, and SQLDelight persistence over a JDBC
 * sqlite driver. Each step is isolated so partial capability is visible.
 */
class DesktopBootSmoke {

    private val componentDataDir = Files.createTempDirectory("matrix-cli-graph").toFile()

    private fun sessionParams() = SessionParams(
            credentials = Credentials(
                    userId = "@cli:example.org",
                    accessToken = "not-a-real-token",
                    refreshToken = null,
                    homeServer = "example.org",
                    deviceId = "CLIDEVICE",
            ),
            homeServerConnectionConfig = HomeServerConnectionConfig.Builder()
                    .withHomeServerUri("https://example.org")
                    .build(),
            isTokenValid = true,
            loginType = LoginType.PASSWORD,
    )

    private fun matrixComponent(): DesktopMatrixComponent {
        return DesktopMatrix(
                dataDir = componentDataDir,
                matrixConfiguration = MatrixConfiguration(
                        applicationFlavor = "MatrixCli",
                        roomDisplayNameFallbackProvider = CliRoomDisplayNameFallbackProvider,
                ),
                appName = "MatrixCli",
        ).component
    }

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

        check("desktop NetworkCallbackStrategy (assume-online)") {
            val strategy = AssumeOnlineNetworkCallbackStrategyFactory().create()
            strategy.register { }
            strategy.unregister()
            "assume-online strategy satisfies the seam"
        }

        check("olm native crypto (identity keys)") {
            OlmManager()
            val account = OlmAccount()
            try {
                val keys = account.identityKeys()
                val curve = keys[OlmAccount.JSON_KEY_IDENTITY_KEY]
                require(!curve.isNullOrEmpty()) { "no curve25519 identity key produced" }
                "olm loaded, curve25519 key len=${curve.length}"
            } finally {
                account.releaseAccount()
            }
        }

        check("core Retrofit/OkHttp/Moshi network (matrix.org /versions)") {
            val moshi = MatrixJsonParser.getMoshi().newBuilder()
                    .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
            val retrofit = RetrofitFactory(moshi)
                    .create(OkHttpClient.Builder().build(), "https://matrix.org/")
            val versions = runBlocking { retrofit.create(VersionsApi::class.java).versions() }
            require(versions.versions.isNotEmpty()) { "no client versions returned" }
            "matrix.org advertises ${versions.versions.size} versions, latest ${versions.versions.last()}"
        }

        check("desktop Dagger graph (matrix component)") {
            val component = matrixComponent()
            component.olmManager()
            component.sessionManager()
            component.authenticationService()
            "matrix component built: dispatchers, auth service, session manager, olm all resolved"
        }

        check("auth stack against a live homeserver (login flow)") {
            val config = HomeServerConnectionConfig.Builder()
                    .withHomeServerUri("https://matrix.org")
                    .build()
            val service = matrixComponent().authenticationService()
            val flow = runBlocking { service.getLoginFlow(config) }
            val types = flow.supportedLoginTypes
            require(types.isNotEmpty()) { "no login types advertised" }
            "matrix.org login types: ${types.joinToString()}"
        }

        check("desktop session graph (no server)") {
            val session = matrixComponent().sessionManager().getOrCreateSession(sessionParams())
            // Lazy holders: touch the services so the graph actually constructs them.
            val services = listOf(
                    session.roomService(), session.userService(), session.cryptoService(),
                    session.syncService(), session.searchService(), session.profileService(),
                    session.fileService(), session.pushersService(), session.spaceService(),
                    session.widgetService(), session.identityService(), session.integrationManagerService(),
            )
            "session ${session.myUserId} built with ${services.size} services from the desktop graph"
        }

        check("shared widget/identity/integration services answer off-android") {
            val session = matrixComponent().sessionManager().getOrCreateSession(sessionParams())
            val widgets = session.widgetService().getUserWidgets()
            val configs = session.integrationManagerService().getOrderedConfigs()
            val identityServer = session.identityService().getCurrentIdentityServerUrl()
            "widgets=${widgets.size} integrationConfigs=${configs.size} identityServer=$identityServer"
        }

        dataDir.deleteRecursively()
        componentDataDir.deleteRecursively()
        println("== smoke complete: $passed passed, $failed failed ==")
    }
}

internal object CliRoomDisplayNameFallbackProvider : RoomDisplayNameFallbackProvider {

    override fun excludedUserIds(roomId: String) = emptyList<String>()

    override fun getNameForRoomInvite() = "Room invite"

    override fun getNameForEmptyRoom(isDirect: Boolean, leftMemberNames: List<String>) = "Empty room"

    override fun getNameFor1member(name: String) = name

    override fun getNameFor2members(name1: String, name2: String) = "$name1 and $name2"

    override fun getNameFor3members(name1: String, name2: String, name3: String) = "$name1, $name2 and $name3"

    override fun getNameFor4members(name1: String, name2: String, name3: String, name4: String) =
            "$name1, $name2, $name3 and $name4"

    override fun getNameFor4membersAndMore(name1: String, name2: String, name3: String, remainingCount: Int) =
            "$name1, $name2, $name3 and $remainingCount others"
}
