/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import java.io.File

/**
 * Reads a platform content URI (as a string) into a local temp file. Android backs this with
 * ContentResolver; a desktop impl can treat the string as a file path.
 */
internal interface ContentUriResolver {

    suspend fun copyToTempFile(uriString: String): File
}
