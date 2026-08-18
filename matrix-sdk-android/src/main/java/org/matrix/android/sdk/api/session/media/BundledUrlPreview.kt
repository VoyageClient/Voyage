/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.media

/**
 * One entry of the `m.url_previews` array of a message content (MSC4095).
 */
data class BundledUrlPreview(
        /**
         * The URL of the body this preview was generated for.
         */
        val matchedUrl: String,
        /**
         * The bundled preview data, or null when the entry only whitelists [matchedUrl] for a
         * server side preview.
         */
        val previewUrlData: PreviewUrlData?
)
