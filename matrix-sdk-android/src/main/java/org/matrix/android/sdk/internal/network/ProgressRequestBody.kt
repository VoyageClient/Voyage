/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.extensions.tryOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal class ProgressRequestBody(
        private val delegate: RequestBody,
        private val listener: Listener,
        private val nanoTime: () -> Long = System::nanoTime,
) : RequestBody() {

    val uploadId = nextUploadId.incrementAndGet()
    private var attempt = 0

    override fun contentType(): MediaType? {
        return delegate.contentType()
    }

    val length = tryOrNull { delegate.contentLength() } ?: -1

    override fun contentLength() = length

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        // Body loggers serialize to memory before the actual network write.
        if (sink is Buffer) {
            delegate.writeTo(sink)
            return
        }
        attempt++
        val countingSink = CountingSink(sink)
        DebugLog.i { "UPLOADDBG body=$uploadId context=${listener.logContext} attempt=$attempt start total=$length" }
        listener.onStart(attempt > 1)
        try {
            val bufferedSink = countingSink.buffer()
            delegate.writeTo(bufferedSink)
            bufferedSink.flush()
            DebugLog.i { "UPLOADDBG body=$uploadId attempt=$attempt written=${countingSink.bytesWritten} awaiting-response" }
            listener.onComplete()
        } catch (failure: IOException) {
            DebugLog.w { "UPLOADDBG body=$uploadId attempt=$attempt interrupted written=${countingSink.bytesWritten} error=${failure.javaClass.simpleName}" }
            throw failure
        }
    }

    private inner class CountingSink(delegate: Sink) : ForwardingSink(delegate) {

        var bytesWritten = 0L
            private set
        private var lastNotifiedAtNs: Long? = null
        private var lastLoggedAtNs: Long? = null
        private var lastLoggedBucket = -1

        @Throws(IOException::class)
        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount
            val now = nanoTime()
            val isFinal = length in 1..bytesWritten
            // Bound main-thread work even when a fast connection writes thousands of segments.
            val lastNotified = lastNotifiedAtNs
            if (isFinal || lastNotified == null || now - lastNotified >= PROGRESS_UPDATE_THROTTLE_NS) {
                lastNotifiedAtNs = now
                listener.onProgress(bytesWritten, length)
            }
            if (DebugLog.enabled) {
                val bucket = if (length > 0) (bytesWritten.toDouble() * 10 / length).toInt() else -1
                val lastLogged = lastLoggedAtNs
                if (isFinal || lastLogged == null || bucket > lastLoggedBucket || now - lastLogged >= PROGRESS_LOG_INTERVAL_NS) {
                    lastLoggedAtNs = now
                    lastLoggedBucket = bucket
                    DebugLog.i { "UPLOADDBG body=$uploadId attempt=$attempt progress=$bytesWritten/$length" }
                }
            }
        }
    }

    companion object {
        private val nextUploadId = AtomicInteger()
        private const val PROGRESS_UPDATE_THROTTLE_NS = 100_000_000L
        private const val PROGRESS_LOG_INTERVAL_NS = 5_000_000_000L
    }

    interface Listener {
        val logContext: String get() = "upload"
        fun onStart(isRetry: Boolean) = Unit
        fun onComplete() = Unit
        fun onProgress(current: Long, total: Long)
    }
}
