/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

object AttachmentViewerDebug {
    interface Logger {
        val enabled: Boolean
        fun info(message: String)
        fun warn(failure: Throwable?, message: String)
    }

    @Volatile var logger: Logger? = null

    val enabled: Boolean get() = logger?.enabled == true

    inline fun i(message: () -> String) {
        val sink = logger ?: return
        if (sink.enabled) sink.info("VIDEODBG ${message()}")
    }

    inline fun w(failure: Throwable? = null, message: () -> String) {
        val sink = logger ?: return
        if (sink.enabled) sink.warn(failure, "VIDEODBG ${message()}")
    }
}
