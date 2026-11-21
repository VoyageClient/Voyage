/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.debug

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import retrofit2.Invocation
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object NetworkDiagnostics : EventListener.Factory, Interceptor {
    private val nextId = AtomicLong()
    private val totalTx = AtomicLong()
    private val totalRx = AtomicLong()
    private val endpoints = ConcurrentHashMap<String, Totals>()
    private val listeners = WeakHashMap<Call, Listener>()
    private val resources = WeakHashMap<Call, Pair<String, Long>>()

    override fun create(call: Call): EventListener = create(call, EventListener.NONE)

    fun create(call: Call, delegate: EventListener): EventListener =
            if (DebugLog.enabled) {
                val method = call.request().tag(Invocation::class.java)?.method()
                val source = method?.let { "${it.declaringClass.simpleName}.${it.name}" } ?: "http.${endpoint(call.request().url())}"
                Listener(call, delegate, DiagnosticContext.current() ?: DiagnosticContext.newOrigin(source))
                        .also { synchronized(listeners) { listeners[call] = it } }
            } else {
                delegate
            }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!DebugLog.enabled) return chain.proceed(chain.request())
        val listener = synchronized(listeners) { listeners[chain.call()] } ?: return chain.proceed(chain.request())
        val response = DiagnosticContext.run(listener.origin) { chain.proceed(chain.request()) }
        listener.responseReady(chain.call(), response)
        return response
    }

    fun checkpoint(label: String, roomId: String, eventId: String? = null) {
        DebugLog.i {
            "NETDBG checkpoint=$label room=${key(roomId)} event=${eventId?.let(::key)} " +
                    "totalTxCompleted=${totalTx.get()} totalRx=${totalRx.get()} ${DiagnosticContext.describe()}"
        }
        snapshot(label, force = label == "reply-tap" || label.startsWith("navigate-landed") || label.startsWith("navigate-timeout"))
    }

    fun snapshot(label: String, force: Boolean = false) {
        if (DebugLog.enabled) AppTrafficDiagnostics.sample(label, totalTx.get(), totalRx.get(), force)
    }

    fun resetTrafficBaseline() = AppTrafficDiagnostics.reset()

    suspend fun <T> trace(label: String, roomId: String, block: suspend () -> T): T = DiagnosticContext.withOrigin(label) {
        if (!DebugLog.enabled) return@withOrigin block()
        val operation = nextId.incrementAndGet()
        val startedAt = System.nanoTime()
        checkpoint("$label-start op=$operation", roomId)
        try {
            block().also {
                checkpoint("$label-done op=$operation elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}", roomId)
            }
        } catch (failure: Throwable) {
            DebugLog.w {
                "NETDBG operation=$operation label=$label room=${key(roomId)} failed=${failure.javaClass.simpleName} " +
                        "cause=${failure.cause?.javaClass?.simpleName} elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} ${DiagnosticContext.describe()}"
            }
            throw failure
        }
    }

    internal fun endpoint(url: HttpUrl): String {
        val segments = url.pathSegments()
        if (segments.firstOrNull() != "_matrix") return "external"
        if ("media" in segments) return "media"
        val rooms = segments.indexOf("rooms")
        if (rooms >= 0) return when (segments.getOrNull(rooms + 2)) {
            "context" -> "context"
            "messages" -> "messages"
            "members" -> "members"
            "state" -> "state"
            "event" -> "event"
            "relations", "threads" -> "relations"
            else -> "room-other"
        }
        if (segments.lastOrNull() == "sync") return "sync"
        return "matrix-other"
    }

    internal fun key(value: String): String = Integer.toHexString(value.hashCode())

    private class Totals {
        val tx = AtomicLong()
        val rx = AtomicLong()
    }

    // OkHttp reports response bytes below transparent gzip decoding, including interrupted reads.
    // These are body bytes, excluding headers, TLS and retransmissions. Failed uploads have no byte callback in 3.12.
    private class Listener(call: Call, delegate: EventListener, val origin: DiagnosticContext.Origin?) : ForwardingNetworkListener(delegate) {
        private val id = nextId.incrementAndGet()
        private val startedAt = System.nanoTime()
        private val finished = AtomicBoolean()
        private var stage = "queued"
        private var attempt = 0
        private var connects = 0
        private var protocol: Protocol? = null
        private var status: Int? = null
        private var tx = 0L
        private var rx = 0L
        private var uploadIncomplete = false
        private var previousUploadIncomplete = false
        private var endpoint = endpoint(call.request().url())
        private var endpointTotals = endpoints.getOrPut(endpoint) { Totals() }
        private var resource: String? = null

        private fun trace(message: () -> String) {
            DebugLog.i { "NETDBG call=$id +${(System.nanoTime() - startedAt) / 1_000_000}ms ${message()} ${DiagnosticContext.describe(origin)}" }
        }

        fun responseReady(call: Call, response: Response) {
            status = response.code()
            if (response.networkResponse() == null) stage = if (response.cacheResponse() != null) "cache" else "intercepted"
            if (response.networkResponse() == null) trace { "ready cache=${response.cacheResponse() != null} network=false status=${response.code()}" }
            // OkHttp 3.12 omits callEnd for cache hits and empty response bodies.
            if (response.networkResponse() == null || response.networkResponse()?.code() == 304 ||
                    response.body()?.contentLength() == 0L || response.body() == null) {
                finish(call, null)
            }
        }

        override fun callStart(call: Call) {
            super.callStart(call)
            trace {
                val url = call.request().url()
                val segments = url.pathSegments()
                val room = segments.indexOf("rooms").takeIf { it >= 0 }?.let { segments.getOrNull(it + 1) }
                "start method=${call.request().method()} endpoint=$endpoint host=${url.host()} room=${room?.let(::key)} " +
                        "mediaTrace=${call.request().tag(Long::class.javaObjectType)}"
            }
        }

        override fun dnsStart(call: Call, domainName: String) {
            stage = "dns"
            super.dnsStart(call, domainName)
        }

        override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) {
            stage = "connect"
            connects++
            super.connectStart(call, address, proxy)
        }

        override fun secureConnectStart(call: Call) {
            stage = "tls"
            super.secureConnectStart(call)
        }

        override fun connectFailed(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?, failure: IOException) {
            trace { "connect-failed stage=$stage connects=$connects error=${failure.javaClass.simpleName}" }
            super.connectFailed(call, address, proxy, protocol, failure)
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            stage = "connected"
            protocol = connection.protocol()
            super.connectionAcquired(call, connection)
        }

        override fun requestHeadersStart(call: Call) {
            stage = "request-headers"
            previousUploadIncomplete = previousUploadIncomplete || uploadIncomplete
            attempt++
            super.requestHeadersStart(call)
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            endpoint = endpoint(request.url())
            endpointTotals = endpoints.getOrPut(endpoint) { Totals() }
            releaseResource(call)
            if (request.method() == "GET" || request.method() == "HEAD") {
                // Hash headers too: credentials and byte ranges distinguish resources without appearing in logs.
                val digest = MessageDigest.getInstance("SHA-256")
                        .digest("${request.method()} ${request.url()}\n${request.headers()}".toByteArray(Charsets.UTF_8))
                resource = ByteString.of(*digest).hex()
                val others = synchronized(resources) {
                    resources.values.filter { it.first == resource }.map { it.second }
                            .also { resources[call] = resource!! to id }
                }
                if (others.isNotEmpty()) trace { "duplicate resource=$resource overlappingCalls=$others" }
            }
            trace { "request attempt=$attempt endpoint=$endpoint host=${request.url().host()} method=${request.method()} protocol=$protocol connects=$connects resource=$resource" }
            super.requestHeadersEnd(call, request)
        }

        override fun requestBodyStart(call: Call) {
            stage = "request-body"
            uploadIncomplete = true
            super.requestBodyStart(call)
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            uploadIncomplete = false
            tx += byteCount
            totalTx.addAndGet(byteCount)
            endpointTotals.tx.addAndGet(byteCount)
            trace { "request-body attempt=$attempt bytes=$byteCount endpointTxCompleted=${endpointTotals.tx.get()}" }
            super.requestBodyEnd(call, byteCount)
        }

        override fun responseHeadersStart(call: Call) {
            stage = "response-headers"
            super.responseHeadersStart(call)
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            status = response.code()
            trace {
                "response attempt=$attempt status=${response.code()} declared=${response.header("Content-Length")?.toLongOrNull() ?: -1} " +
                        "encoding=${response.header("Content-Encoding") ?: "identity"}"
            }
            super.responseHeadersEnd(call, response)
        }

        override fun responseBodyStart(call: Call) {
            stage = "response-body"
            super.responseBodyStart(call)
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            rx += byteCount
            totalRx.addAndGet(byteCount)
            endpointTotals.rx.addAndGet(byteCount)
            trace {
                "response-body attempt=$attempt endpoint=$endpoint bytes=$byteCount " +
                        "endpointRx=${endpointTotals.rx.get()} totalRx=${totalRx.get()} canceled=${call.isCanceled()}"
            }
            releaseResource(call)
            super.responseBodyEnd(call, byteCount)
        }

        override fun callEnd(call: Call) {
            finish(call, null)
            super.callEnd(call)
        }

        override fun callFailed(call: Call, failure: IOException) {
            finish(call, failure)
            super.callFailed(call, failure)
        }

        private fun finish(call: Call, failure: IOException?) {
            if (!finished.compareAndSet(false, true)) return
            releaseResource(call)
            synchronized(listeners) { listeners.remove(call) }
            trace {
                "end outcome=${if (failure == null) "closed" else "failed"} stage=$stage endpoint=$endpoint status=$status attempts=$attempt connects=$connects " +
                        "txCompleted=$tx txIncomplete=${uploadIncomplete || previousUploadIncomplete} rx=$rx canceled=${call.isCanceled()} " +
                        "error=${failure?.javaClass?.simpleName} totalTxCompleted=${totalTx.get()} totalRx=${totalRx.get()}"
            }
            DiagnosticContext.run(origin) { snapshot("request-end") }
        }

        private fun releaseResource(call: Call) {
            if (resource == null) return
            synchronized(resources) { resources.remove(call) }
            resource = null
        }
    }
}

private open class ForwardingNetworkListener(private val delegate: EventListener) : EventListener() {
    override fun callStart(call: Call) = delegate.callStart(call)
    override fun dnsStart(call: Call, domainName: String) = delegate.dnsStart(call, domainName)
    override fun dnsEnd(call: Call, domainName: String, addresses: List<InetAddress>) = delegate.dnsEnd(call, domainName, addresses)
    override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) = delegate.connectStart(call, address, proxy)
    override fun secureConnectStart(call: Call) = delegate.secureConnectStart(call)
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = delegate.secureConnectEnd(call, handshake)
    override fun connectEnd(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = delegate.connectEnd(call, address, proxy, protocol)
    override fun connectFailed(call: Call, address: InetSocketAddress, proxy: Proxy, protocol: Protocol?, failure: IOException) =
            delegate.connectFailed(call, address, proxy, protocol, failure)
    override fun connectionAcquired(call: Call, connection: Connection) = delegate.connectionAcquired(call, connection)
    override fun connectionReleased(call: Call, connection: Connection) = delegate.connectionReleased(call, connection)
    override fun requestHeadersStart(call: Call) = delegate.requestHeadersStart(call)
    override fun requestHeadersEnd(call: Call, request: Request) = delegate.requestHeadersEnd(call, request)
    override fun requestBodyStart(call: Call) = delegate.requestBodyStart(call)
    override fun requestBodyEnd(call: Call, byteCount: Long) = delegate.requestBodyEnd(call, byteCount)
    override fun responseHeadersStart(call: Call) = delegate.responseHeadersStart(call)
    override fun responseHeadersEnd(call: Call, response: Response) = delegate.responseHeadersEnd(call, response)
    override fun responseBodyStart(call: Call) = delegate.responseBodyStart(call)
    override fun responseBodyEnd(call: Call, byteCount: Long) = delegate.responseBodyEnd(call, byteCount)
    override fun callEnd(call: Call) = delegate.callEnd(call)
    override fun callFailed(call: Call, failure: IOException) = delegate.callFailed(call, failure)
}
