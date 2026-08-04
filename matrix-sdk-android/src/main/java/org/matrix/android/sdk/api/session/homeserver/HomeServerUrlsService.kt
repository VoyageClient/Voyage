/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.homeserver

/**
 * Manages the client-server API URLs of the account's homeserver. Entries after the first are mirrors of the
 * same homeserver, used when the one before them cannot be reached.
 */
interface HomeServerUrlsService {

    /**
     * The configured URLs, primary first. Never empty.
     */
    fun getHomeServerUrls(): List<String>

    /**
     * Replace the configured URLs. The first entry becomes the primary one.
     */
    suspend fun setHomeServerUrls(urls: List<String>)

    /**
     * The URL currently answering. Only differs from the first configured one after a fallback.
     */
    fun getActiveHomeServerUrl(): String

    /**
     * Check whether a URL ranked above the one in use is reachable again and switch back to it if so.
     * Returns the URL in use afterwards.
     */
    suspend fun refreshActiveHomeServerUrl(): String
}
