/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import android.os.SystemClock
import com.bumptech.glide.Priority
import com.bumptech.glide.integration.okhttp3.OkHttpStreamFetcher
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import im.vector.app.core.extensions.singletonEntryPoint
import im.vector.app.features.media.MediaDebug
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.matrix.android.sdk.api.debug.DebugLog
import java.io.IOException
import java.io.InputStream
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

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
        // Authenticated media is only readable per-account; a url-only key would let another
        // account on this device replay this one's cached media from the shared Glide cache. A public
        // url keeps its own key, so those entries stay shared and survive a session change.
        val key = if (authenticated) ObjectKey("${session?.sessionId.orEmpty()}:$stringUrl") else model
        if (!DebugLog.enabled) {
            return ModelLoader.LoadData(key, OkHttpStreamFetcher(Call.Factory { request -> client.newCall(request) }, model))
        }
        val id = HttpTrace.nextId.incrementAndGet()
        val tracedClient = HttpTrace.client(client)
        val fetcher = OkHttpStreamFetcher(
                Call.Factory { request -> tracedClient.newCall(request.newBuilder().tag(Long::class.javaObjectType, id).build()) },
                model
        )
        return ModelLoader.LoadData(key, LoggingHttpFetcher(fetcher, id, stringUrl, "auth=$authenticated session=${session != null} ${width}x$height"))
    }
}

/** Separates time a call spends in OkHttp's dispatcher queue from time the server takes to answer. */
private object HttpTrace {
    val nextId = AtomicLong()
    private val traced = WeakHashMap<OkHttpClient, OkHttpClient>()

    fun client(base: OkHttpClient): OkHttpClient = synchronized(traced) {
        traced.getOrPut(base) {
            val dispatcher = base.dispatcher()
            base.newBuilder()
                    .eventListenerFactory { call -> Listener(call.request().tag(Long::class.javaObjectType), dispatcher) }
                    .build()
        }
    }

    private class Listener(private val id: Long?, private val dispatcher: Dispatcher) : EventListener() {
        private val startedAt = SystemClock.uptimeMillis()

        private fun trace(event: String) {
            DebugLog.i { "MEDIADBG http #$id +${SystemClock.uptimeMillis() - startedAt}ms $event" }
        }

        override fun callStart(call: Call) {
            val host = call.request().url().host()
            val hostRunning = dispatcher.runningCalls().count { it.request().url().host() == host }
            val hostQueued = dispatcher.queuedCalls().count { it.request().url().host() == host }
            trace("enqueued, ahead of it: host running=$hostRunning queued=$hostQueued " +
                    "(all running=${dispatcher.runningCallsCount()} queued=${dispatcher.queuedCallsCount()}, " +
                    "limits perHost=${dispatcher.maxRequestsPerHost} total=${dispatcher.maxRequests})")
        }

        override fun connectionAcquired(call: Call, connection: Connection) = trace("left the queue, connection ${connection.protocol()}")

        override fun requestHeadersEnd(call: Call, request: Request) = trace("request sent")

        override fun responseHeadersStart(call: Call) = trace("server started answering")

        override fun responseHeadersEnd(call: Call, response: Response) = trace("response ${response.code()}")

        override fun responseBodyEnd(call: Call, byteCount: Long) = trace("body done ${byteCount}b")

        override fun callFailed(call: Call, ioe: IOException) = trace("call failed $ioe")
    }
}

private class LoggingHttpFetcher(
        private val delegate: DataFetcher<InputStream>,
        private val id: Long,
        private val url: String,
        private val describe: String,
) : DataFetcher<InputStream> by delegate {

    private var startedAt = 0L

    override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
        startedAt = SystemClock.uptimeMillis()
        DebugLog.i { "MEDIADBG http #$id start $describe priority=$priority url=$url" }
        delegate.loadData(priority, object : DataFetcher.DataCallback<InputStream> {
            override fun onDataReady(data: InputStream?) {
                DebugLog.i { "MEDIADBG http #$id ready ${SystemClock.uptimeMillis() - startedAt}ms hasData=${data != null} url=$url" }
                callback.onDataReady(data)
            }

            override fun onLoadFailed(e: Exception) {
                DebugLog.w { "MEDIADBG http #$id FAILED ${SystemClock.uptimeMillis() - startedAt}ms ${MediaDebug.failure(e)} url=$url" }
                callback.onLoadFailed(e)
            }
        })
    }

    override fun cancel() {
        DebugLog.i { "MEDIADBG http #$id cancel after ${SystemClock.uptimeMillis() - startedAt}ms url=$url" }
        delegate.cancel()
    }
}
