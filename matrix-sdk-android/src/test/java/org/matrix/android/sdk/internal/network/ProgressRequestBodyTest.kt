/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.Sink
import okio.Timeout
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProgressRequestBodyTest {
    private var now = 0L
    private val starts = mutableListOf<Boolean>()
    private val progress = mutableListOf<Long>()
    private var completions = 0
    private val listener = object : ProgressRequestBody.Listener {
        override fun onStart(isRetry: Boolean) {
            starts.add(isRetry)
        }
        override fun onProgress(current: Long, total: Long) {
            progress.add(current)
        }
        override fun onComplete() {
            completions++
        }
    }

    private fun body(length: Long = 65_536L) = ProgressRequestBody(object : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            repeat(8) { sink.write(ByteArray(8_192)) }
        }
    }, listener) { now }

    private fun sink(failAfter: Long = Long.MAX_VALUE, advanceClock: Boolean = true) = object : Sink {
        private var written = 0L
        override fun write(source: Buffer, byteCount: Long) {
            if (written >= failAfter) throw IOException("simulated connection loss")
            source.skip(byteCount)
            written += byteCount
            if (advanceClock) now += 101_000_000L
        }
        override fun timeout() = Timeout.NONE
        override fun flush() = Unit
        override fun close() = Unit
    }.buffer()

    @Test
    fun `replaying an interrupted request identifies the retry before its first segment`() {
        val body = body()
        try {
            body.writeTo(sink(failAfter = 24_576))
            throw AssertionError("expected a broken connection")
        } catch (expected: IOException) {
            assertEquals(listOf(8_192L, 16_384L, 24_576L), progress)
        }
        assertEquals(0, completions)
        progress.clear()
        body.writeTo(sink())
        assertEquals(listOf(false, true), starts)
        assertEquals(8_192L, progress.first())
        assertEquals(65_536L, progress.last())
        assertEquals(1, completions)
    }

    @Test
    fun `body logging does not emit progress or consume an upload attempt`() {
        val body = body()
        body.writeTo(Buffer())
        assertTrue(progress.isEmpty())
        assertTrue(starts.isEmpty())
        assertEquals(0, completions)
        body.writeTo(sink())
        assertEquals(listOf(false), starts)
    }

    @Test
    fun `fast writes emit the first and last counts without flooding the listener`() {
        body().writeTo(sink(advanceClock = false))
        assertEquals(listOf(8_192L, 65_536L), progress)
        assertEquals(1, completions)
    }

    @Test
    fun `an HTTP retry identifies both actual writes while body logging emits no callbacks`() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit {
            repeat(2) { attempt ->
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val source = socket.getInputStream().source().buffer()
                    source.readUtf8LineStrict()
                    var length = 0L
                    while (true) {
                        val header = source.readUtf8LineStrict()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toLong()
                    }
                    source.skip(length)
                    val status = if (attempt == 0) "503 Service Unavailable" else "200 OK"
                    socket.getOutputStream().write(
                            "HTTP/1.1 $status\r\nContent-Length: 0\r\nRetry-After: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                }
            }
        }
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).addInterceptor { chain ->
            chain.request().body()!!.writeTo(Buffer())
            chain.proceed(chain.request())
        }.build()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/upload").put(body()).build()
            client.newCall(request).execute().use { assertTrue(it.isSuccessful) }
            received.get(10, TimeUnit.SECONDS)
            assertEquals(listOf(false, true), starts)
            assertEquals(listOf(8_192L, 65_536L, 8_192L, 65_536L), progress)
            assertEquals(2, completions)
        } finally {
            server.close()
            executor.shutdownNow()
            client.connectionPool().evictAll()
            client.dispatcher().executorService().shutdownNow()
        }
    }

    @Test
    fun `unknown lengths still finish and report bounded callback frequency`() {
        body(length = -1).writeTo(sink(advanceClock = false))
        assertEquals(listOf(8_192L), progress)
        assertEquals(1, completions)
        assertFalse(starts.single())
    }
}
