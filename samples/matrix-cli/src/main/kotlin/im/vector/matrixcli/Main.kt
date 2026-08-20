/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli

import java.io.File

fun main() {
    println("matrix-cli: booting :matrix-sdk-core on plain JVM ${System.getProperty("java.version")}")
    val homeServer = System.getenv("MATRIX_HOMESERVER")
    val user = System.getenv("MATRIX_USER")
    val password = System.getenv("MATRIX_PASSWORD")
    if (homeServer != null && user != null && password != null) {
        DesktopSessionRun(
                homeServer = homeServer,
                user = user,
                password = password,
                sendRoomId = System.getenv("MATRIX_SEND_ROOM"),
                dataDir = File(System.getenv("MATRIX_DATA_DIR") ?: "matrix-cli-data"),
        ).run()
    } else {
        DesktopBootSmoke().run()
    }
}
