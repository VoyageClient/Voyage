/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import java.io.File

/**
 * Reads a content URI (as a string) into a local temp file via ContentResolver.
 */
internal interface ContentUriResolver {

    suspend fun copyToTempFile(uriString: String): File
}
