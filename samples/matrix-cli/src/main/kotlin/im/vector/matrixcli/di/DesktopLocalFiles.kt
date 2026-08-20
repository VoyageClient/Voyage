/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli.di

import java.io.File
import java.net.URI

/**
 * The SDK passes attachment locations around as opaque "uri" strings that only the platform knows
 * how to open. On desktop they are plain paths, and `file:` URIs since that is what this sample
 * hands back for files it produced itself.
 */
internal fun String.toLocalFile(): File {
    if (!startsWith("file:")) return File(this)
    return runCatching { File(URI(this)) }.getOrElse { File(removePrefix("file://")) }
}

internal fun File.toLocalUri(): String = toURI().toString()
