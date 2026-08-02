/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli

fun main() {
    println("matrix-cli: booting :matrix-sdk-core on plain JVM ${System.getProperty("java.version")}")
    DesktopBootSmoke().run()
}
