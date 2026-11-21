/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.DiagnosticContext
import org.matrix.android.sdk.api.debug.NetworkDiagnostics
import retrofit2.Retrofit
import retrofit2.http.GET
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class NetworkDiagnosticsTest {
    interface TestApi {
        @GET("/_matrix/client/v3/rooms/!room:example.org/context/synthetic-event")
        suspend fun context(): ResponseBody
    }
    private val messages = CopyOnWriteArrayList<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages.add(message)
        }
    }
    private val client = OkHttpClient.Builder().eventListenerFactory(NetworkDiagnostics).addInterceptor(NetworkDiagnostics).build()

    @Before
    fun setUp() {
        DebugLog.enabled = true
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        DebugLog.enabled = false
    }

    @Test
    fun `suspend Retrofit requests carry the action origin to OkHttp threads`() = runBlocking {
        serve(response("synthetic response".toByteArray())) { base ->
            val api = Retrofit.Builder().baseUrl("$base/").client(client).build().create(TestApi::class.java)
            runBlocking {
                DiagnosticContext.withOrigin("reply-tap") {
                    val root = DiagnosticContext.current()!!.root
                    NetworkDiagnostics.trace("context.fetch", "!room:example.org") {
                        withContext(Dispatchers.Default) { api.context().use { it.string() } }
                    }
                    assertTrue(messages.any { "NETDBG call=" in it && "root=$root " in it && "source=context.fetch" in it })
                }
            }
        }
    }

    @Test
    fun `overlapping identical requests are reported and released after close`() {
        serve(response(ByteArray(8192)), response(ByteArray(8192)), response(ByteArray(8192))) { base ->
            val request = Request.Builder().url(base).build()
            client.newCall(request).execute().use { first ->
                client.newCall(request).execute().use { it.body()!!.bytes() }
                first.body()!!.bytes()
            }
            client.newCall(request).execute().use { it.body()!!.bytes() }
        }
        assertEquals(1, messages.count { " duplicate resource=" in it })
    }

    @Test
    fun `different credentials and ranges are not classified as duplicate downloads`() {
        serve(response(ByteArray(8192)), response(ByteArray(8192)), response(ByteArray(8192))) { base ->
            val request = Request.Builder().url(base).header("Authorization", "Bearer synthetic-a").build()
            client.newCall(request).execute().use { first ->
                client.newCall(request.newBuilder().header("Authorization", "Bearer synthetic-b").build()).execute().use { it.body()!!.bytes() }
                client.newCall(request.newBuilder().header("Range", "bytes=8192-").build()).execute().use { it.body()!!.bytes() }
                first.body()!!.bytes()
            }
        }
        assertFalse(messages.any { " duplicate resource=" in it || "synthetic-a" in it || "synthetic-b" in it })
    }

    @Test
    fun `gzip counts compressed bytes rather than expanded JSON`() {
        val json = "{\"text\":\"${"synthetic message ".repeat(4096)}\"}"
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json.toByteArray()) }
        val compressed = output.toByteArray()
        serve(response(compressed, "Content-Encoding: gzip\r\n")) { base ->
            val request = Request.Builder().url("$base/_matrix/client/v3/rooms/!room:example.org/context/\$event?access_token=synthetic-token").build()
            client.newCall(request).execute().use { assertEquals(json, it.body()!!.string()) }
        }
        assertTrue(messages.any { "response-body attempt=1 endpoint=context bytes=${compressed.size} " in it })
        assertTrue(messages.any { "declared=${compressed.size} encoding=gzip" in it })
        assertFalse(messages.any { "synthetic-token" in it || "synthetic message" in it || "!room:example.org" in it })
    }

    @Test
    fun `redirects count each response once and retain the call id`() {
        val redirect = "HTTP/1.1 302 Found\r\nLocation: /_matrix/client/v3/sync\r\nContent-Length: 3\r\nConnection: close\r\n\r\none".toByteArray()
        serve(redirect, response("two".toByteArray())) { base ->
            client.newCall(Request.Builder().url("$base/_matrix/client/v3/sync").build()).execute().use {
                assertEquals("two", it.body()!!.string())
            }
        }
        assertEquals(2, messages.count { " response-body attempt=" in it })
        assertTrue(messages.any { "end outcome=closed" in it && "attempts=2" in it && "rx=6 " in it })
        assertEquals(1, messages.filter { "NETDBG call=" in it }.map { it.substringBefore(" +") }.distinct().size)
    }

    @Test
    fun `truncated response logs bytes received before failure`() {
        val truncated = "HTTP/1.1 200 OK\r\nContent-Length: 5000\r\nConnection: close\r\n\r\n".toByteArray() + ByteArray(4096)
        serve(truncated) { base ->
            try {
                client.newCall(Request.Builder().url("$base/_matrix/client/v3/rooms/!room:example.org/messages").build()).execute().use {
                    it.body()!!.bytes()
                }
                throw AssertionError("Expected an interrupted response")
            } catch (expected: IOException) {
                assertTrue(messages.any { "end outcome=failed" in it && "rx=4096 " in it })
            }
        }
    }

    @Test
    fun `canceling a response preserves the bytes already read`() {
        serve(response(ByteArray(65536))) { base ->
            val call = client.newCall(Request.Builder().url(base).build())
            call.execute().use { response ->
                response.body()!!.source().readByte()
                call.cancel()
                try {
                    response.body()!!.bytes()
                    throw AssertionError("Expected cancellation")
                } catch (expected: IOException) {
                    assertTrue(messages.any { "end outcome=failed" in it && "canceled=true" in it && "rx=0 " !in it })
                }
            }
        }
    }

    @Test
    fun `cache hits do not count response bytes again`() {
        val directory = File.createTempFile("network-diagnostics", "cache").apply {
            check(delete())
            check(mkdir())
        }
        val cache = Cache(directory, 1024 * 1024)
        try {
            val cachedClient = client.newBuilder().cache(cache).build()
            serve(response(ByteArray(8192), "Cache-Control: max-age=60\r\n")) { base ->
                val request = Request.Builder().url(base).build()
                cachedClient.newCall(request).execute().use { it.body()!!.bytes() }
                cachedClient.newCall(request).execute().use {
                    assertTrue(it.cacheResponse() != null)
                    assertTrue(it.networkResponse() == null)
                    it.body()!!.bytes()
                }
            }
            assertEquals(1, messages.count { " response-body attempt=" in it })
            assertTrue(messages.joinToString("\n"), messages.any { "end outcome=closed" in it && "attempts=0" in it && "rx=0 " in it })
        } finally {
            cache.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cache revalidation counts only the network response body`() {
        val directory = File.createTempFile("network-diagnostics", "cache").apply {
            check(delete())
            check(mkdir())
        }
        val cache = Cache(directory, 1024 * 1024)
        try {
            val cachedClient = client.newBuilder().cache(cache).build()
            val notModified = "HTTP/1.1 304 Not Modified\r\nConnection: close\r\n\r\n".toByteArray()
            serve(response(ByteArray(8192), "Cache-Control: no-cache\r\nETag: synthetic-version\r\n"), notModified) { base ->
                val request = Request.Builder().url(base).build()
                cachedClient.newCall(request).execute().use { it.body()!!.bytes() }
                cachedClient.newCall(request).execute().use {
                    assertEquals(304, it.networkResponse()!!.code())
                    assertEquals(8192, it.body()!!.bytes().size)
                }
            }
            assertEquals(2, messages.count { "end outcome=closed" in it })
            assertTrue(messages.any { "end outcome=closed" in it && "rx=0 " in it })
        } finally {
            cache.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `completed upload counts body bytes`() {
        serve(response(ByteArray(0))) { base ->
            val request = Request.Builder().url(base).post(RequestBody.create(null, ByteArray(8192))).build()
            client.newCall(request).execute().use { it.body()!!.bytes() }
        }
        assertTrue(messages.joinToString("\n"), messages.any { "end outcome=closed" in it && "txCompleted=8192 txIncomplete=false" in it })
    }

    @Test
    fun `existing media listener still receives callbacks`() {
        var received = 0L
        var ended = false
        val delegate = object : EventListener() {
            override fun responseBodyEnd(call: Call, byteCount: Long) {
                received += byteCount
            }
            override fun callEnd(call: Call) {
                ended = true
            }
        }
        val delegatedClient = client.newBuilder().eventListenerFactory { NetworkDiagnostics.create(it, delegate) }.build()
        serve(response(ByteArray(8192))) { base ->
            delegatedClient.newCall(Request.Builder().url(base).build()).execute().use { it.body()!!.bytes() }
        }
        assertEquals(8192L, received)
        assertTrue(ended)
        assertTrue(messages.any { "rx=8192 " in it })
    }

    @Test
    fun `disabled logging preserves delegate without emitting diagnostics`() {
        DebugLog.enabled = false
        val call = client.newCall(Request.Builder().url("http://example.org/").build())
        val delegate = object : EventListener() {}
        assertTrue(NetworkDiagnostics.create(call, delegate) === delegate)
        serve(response(ByteArray(0))) { base ->
            client.newCall(Request.Builder().url(base).build()).execute().use { it.body()!!.bytes() }
        }
        assertTrue(messages.isEmpty())
    }

    private fun response(body: ByteArray, headers: String = ""): ByteArray =
            "HTTP/1.1 200 OK\r\n${headers}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body

    private fun serve(vararg replies: ByteArray, block: (String) -> Unit) {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val executor = Executors.newSingleThreadExecutor()
        val served = executor.submit {
            replies.forEach { reply ->
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val headers = ByteArrayOutputStream()
                    var tail = 0
                    while (tail != 0x0d0a0d0a) {
                        val byte = input.read()
                        check(byte >= 0) { "Request ended before headers" }
                        headers.write(byte)
                        tail = (tail shl 8) or byte
                    }
                    val length = headers.toString("UTF-8").lineSequence()
                            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                    repeat(length) { check(input.read() >= 0) { "Request ended before body" } }
                    socket.getOutputStream().apply {
                        write(reply)
                        flush()
                    }
                }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}")
            served.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }
}
