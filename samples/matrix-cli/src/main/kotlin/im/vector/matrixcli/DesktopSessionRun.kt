/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.desktop.DesktopMatrix
import java.io.File

/**
 * Logs in, syncs and (optionally) sends, entirely off Android. Reads the account from the
 * environment so no credentials live in the repo:
 *
 *   MATRIX_HOMESERVER  https://example.org
 *   MATRIX_USER        @someone:example.org (or the localpart)
 *   MATRIX_PASSWORD    the password
 *   MATRIX_SEND_ROOM   optional room id to send a test message to
 *   MATRIX_DATA_DIR    optional, defaults to ./matrix-cli-data
 */
class DesktopSessionRun(
        private val homeServer: String,
        private val user: String,
        private val password: String,
        private val sendRoomId: String?,
        dataDir: File,
) {

    private val matrix = DesktopMatrix(
            dataDir = dataDir,
            matrixConfiguration = MatrixConfiguration(
                    applicationFlavor = "MatrixCli",
                    roomDisplayNameFallbackProvider = CliRoomDisplayNameFallbackProvider,
            ),
            userAgent = { "MatrixCli/0.1" },
    )

    fun run() = runBlocking {
        println("== desktop session run against $homeServer ==")
        val config = HomeServerConnectionConfig.Builder().withHomeServerUri(homeServer).build()

        val authenticationService = matrix.authenticationService()
        val flow = authenticationService.getLoginFlow(config)
        println("  login types: ${flow.supportedLoginTypes.joinToString()}")

        val session = authenticationService.getLoginWizard().login(user, password, "matrix-cli")
        println("  logged in as ${session.myUserId} (device ${session.sessionParams.deviceId})")

        session.open()
        session.syncService().startSync(fromForeground = true)
        println("  syncing…")
        val synced = withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) {
            session.syncService().syncFlow().first()
            true
        }
        if (synced == null) {
            println("  [FAIL] no sync response within ${SYNC_TIMEOUT_MILLIS / 1000}s")
            return@runBlocking stop(session)
        }

        val summaries = session.roomService().getRoomSummaries(roomSummaryQueryParams())
        println("  ${summaries.size} rooms:")
        summaries.take(MAX_ROOMS_PRINTED).forEach { println("    ${it.roomId}  ${it.displayName}") }

        sendRoomId?.let { roomId ->
            val room = session.getRoom(roomId)
            if (room == null) {
                println("  [FAIL] $roomId is not a joined room")
            } else {
                room.sendService().sendTextMessage("Hello from matrix-cli, running off Android.")
                println("  sent a message to $roomId")
            }
        }

        stop(session)
    }

    private fun stop(session: Session) {
        session.syncService().stopSync()
        session.close()
        println("== desktop session run complete ==")
    }

    companion object {
        private const val SYNC_TIMEOUT_MILLIS = 120_000L
        private const val MAX_ROOMS_PRINTED = 20
    }
}
