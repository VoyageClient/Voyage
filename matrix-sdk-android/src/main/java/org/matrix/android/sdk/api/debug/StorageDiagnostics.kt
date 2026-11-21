/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.debug

import java.io.File

object StorageDiagnostics {
    // Report filesystem free space, excluding caches Android might reclaim.
    @Suppress("UsableSpace")
    fun state(file: File, expectedBytes: Long? = null): String = try {
        "resource=${OperationDiagnostics.id(file.absolutePath)} exists=${file.exists()} readable=${file.canRead()} " +
                "bytes=${file.takeIf { it.isFile }?.length()} expectedBytes=$expectedBytes freeBytes=${(file.parentFile ?: file).usableSpace}"
    } catch (failure: SecurityException) {
        "stateUnavailable=permission-denied"
    }

    fun delete(file: File, reason: String, recursive: Boolean = false): Boolean {
        if (!DebugLog.enabled) return if (recursive) file.deleteRecursively() else file.delete()
        val existed = runCatching { file.exists() }.getOrDefault(false)
        return OperationDiagnostics.run("STORAGEDBG", "delete", "reason=$reason resource=${OperationDiagnostics.id(file.absolutePath)} recursive=$recursive") {
            (if (recursive) file.deleteRecursively() else file.delete()).also { deleted ->
                DebugLog.i { "STORAGEDBG deletion reason=$reason result=${if (deleted) "deleted" else if (!existed) "already-absent" else "failed"} ${state(file)}" }
            }
        }
    }
}
