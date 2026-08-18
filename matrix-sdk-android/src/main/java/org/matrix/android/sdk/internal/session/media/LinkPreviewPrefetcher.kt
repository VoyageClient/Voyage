/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

/**
 * Reads the previews of the links in a message before it is sent. Behind an interface because the send
 * path is shared with the desktop target, while generating a preview is Android-only (it decodes images
 * and parses pages).
 */
internal interface LinkPreviewPrefetcher {

    suspend fun prefetch(roomId: String, text: CharSequence, encrypt: Boolean)
}
