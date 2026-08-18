/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.internal.di.Authenticated
import org.matrix.android.sdk.internal.di.UnauthenticatedWithCertificate
import org.matrix.android.sdk.internal.network.httpclient.addAuthenticationHeader
import org.matrix.android.sdk.internal.network.token.AccessTokenProvider
import javax.inject.Inject

/**
 * Asks the homeserver to preview a page, for the rooms where the user chose that over fetching it on
 * the device. The thumbnail it returns is fetched back so it can be reuploaded as our own media, which
 * MSC4095 asks for: the one the homeserver hands out is only cached for as long as it feels like.
 */
internal class HomeServerUrlPreviewFetcher @Inject constructor(
        private val getRawPreviewUrlTask: GetRawPreviewUrlTask,
        private val contentUrlResolver: ContentUrlResolver,
        @UnauthenticatedWithCertificate private val okHttpClient: OkHttpClient,
        @Authenticated private val accessTokenProvider: AccessTokenProvider,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) {

    suspend fun fetch(url: String): FetchedPreview? {
        val raw = getRawPreviewUrlTask.execute(GetRawPreviewUrlTask.Params(url, null))
        val fields = raw.filterKeys { key ->
            (key.startsWith("og:") || key.startsWith("matrix:image:")) && key != "og:image" && key != BundledUrlPreviews.IMAGE_SIZE
        }
        val image = (raw["og:image"] as? String)?.takeIf { it.isMxcUrl() }?.let { download(it) }
        if (fields.isEmpty() && image == null) return null
        return FetchedPreview(fields = fields, image = image)
    }

    private suspend fun download(mxcUrl: String): FetchedImage? {
        val resolved = contentUrlResolver.resolveForDownload(mxcUrl) as? ContentUrlResolver.ResolvedMethod.GET ?: return null
        val request = Request.Builder()
                .url(resolved.url)
                .apply {
                    if (contentUrlResolver.requiresAuthentication(resolved.url)) {
                        addAuthenticationHeader(accessTokenProvider.getToken())
                    }
                }
                .build()
        return withContext(coroutineDispatchers.io) {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.readThumbnail() else null
            }
        }
    }
}
