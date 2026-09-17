/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import com.bumptech.glide.integration.okhttp3.OkHttpStreamFetcher
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import im.vector.app.core.extensions.singletonEntryPoint
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.InputStream

class AuthenticatedGlideUrlLoaderFactory(private val context: Context) : ModelLoaderFactory<GlideUrl, InputStream> {

    private val defaultClient = OkHttpClient()

    override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<GlideUrl, InputStream> {
        return AuthenticatedGlideUrlLoader(context, defaultClient)
    }

    override fun teardown() = Unit
}

class AuthenticatedGlideUrlLoader(
        context: Context,
        private val defaultClient: OkHttpClient
) :
        ModelLoader<GlideUrl, InputStream> {

    private val activeSessionHolder = context.singletonEntryPoint().activeSessionHolder()

    /**
     * Every remote url, not just the authenticated ones. Glide asks each registered loader in turn and
     * falls through to the next when one *fails* — so leaving the stock loader in place meant every
     * rejected authenticated request was immediately sent again with no Authorization header, which can
     * only come back 401 and doubled the traffic of any media failure.
     */
    override fun handles(model: GlideUrl): Boolean = true

    override fun buildLoadData(model: GlideUrl, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream> {
        val session = activeSessionHolder.getSafeActiveSession()
        val stringUrl = model.toStringUrl()
        val authenticated = session?.contentUrlResolver()?.requiresAuthentication(stringUrl) == true
        val client = if (authenticated) session?.getAuthenticatedOkHttpClient() ?: defaultClient else defaultClient
        val fetcher = OkHttpStreamFetcher(Call.Factory { request -> client.newCall(request) }, model)
        // Authenticated media is only readable per-account; a url-only key would let another
        // account on this device replay this one's cached media from the shared Glide cache. A public
        // url keeps its own key, so those entries stay shared and survive a session change.
        val key = if (authenticated) ObjectKey("${session?.sessionId.orEmpty()}:$stringUrl") else model
        return ModelLoader.LoadData(key, fetcher)
    }
}
