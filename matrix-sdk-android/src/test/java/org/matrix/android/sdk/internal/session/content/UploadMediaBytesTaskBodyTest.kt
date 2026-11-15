/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.os.Looper
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker
import org.matrix.android.sdk.api.session.content.ContentUploadStateTracker.State
import org.matrix.android.sdk.internal.network.ProgressRequestBody
import org.matrix.android.sdk.internal.session.room.send.LocalEchoIdentifiers
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class UploadMediaBytesTaskBodyTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val dispatchers = MatrixCoroutineDispatchers(
            Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Main, Dispatchers.Unconfined, Dispatchers.Unconfined,
    )
    private val tracker = DefaultContentUploadStateTracker(dispatchers)
    private val uploader = mockk<FileUploader>()
    private val body = UploadMediaBytesTaskBody(
            uploader, mockk(relaxed = true), tracker, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), dispatchers, mockk(relaxed = true),
    )
    private val states = mutableListOf<State>()
    private var stopped = false
    private var attempt = 0
    private val context = object : BackgroundTaskContext {
        override val isStopped get() = stopped
        override val attemptCount get() = attempt
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun params() = UploadMediaBytesWorkerParams(
            sessionId = "session-example",
            localEchoIds = listOf(LocalEchoIdentifiers("!room:example.org", KEY)),
            contentUri = "mxc://example.org/upload-example",
            filename = "example.bin",
            mimeType = "application/octet-stream",
            isEncrypted = false,
            clearFilePath = folder.newFile().apply { writeBytes(ByteArray(65_536)) }.path,
            encryptedFilePath = null,
    )

    private fun track() {
        tracker.track(KEY, object : ContentUploadStateTracker.UpdateListener {
            override fun onUpdate(state: State) {
                states.add(state)
            }
        })
        idle()
    }

    @Test
    fun `worker retry preserves progress and reports retrying throughout the backoff`() = runBlocking {
        track()
        val params = params()
        coEvery { uploader.uploadReservedFile(any(), any(), any(), any(), any()) } answers {
            val listener = arg<ProgressRequestBody.Listener>(4)
            listener.onStart(false)
            listener.onProgress(24_576, 65_536)
            throw IOException("simulated connection loss")
        }
        assertEquals(BackgroundTaskOutcome.Retry, body.execute(params, context))
        idle()
        assertEquals(State.Uploading(24_576, 65_536, isRetrying = true), states.last())
        assertTrue(File(params.clearFilePath).exists())
        attempt = 1
        coEvery { uploader.uploadReservedFile(any(), any(), any(), any(), any()) } answers {
            val listener = arg<ProgressRequestBody.Listener>(4)
            listener.onStart(false)
            listener.onProgress(8_192, 65_536)
            idle()
            assertEquals(State.Uploading(24_576, 65_536, isRetrying = true), states.last())
            listener.onProgress(65_536, 65_536)
            listener.onComplete()
            idle()
            assertEquals(State.Uploading(65_536, 65_536, isAwaitingResponse = true), states.last())
        }
        assertEquals(BackgroundTaskOutcome.Success, body.execute(params, context))
        idle()
        assertEquals(State.Success, states.last())
        assertFalse(File(params.clearFilePath).exists())
    }

    @Test
    fun `callbacks from a stopped worker do not paint over a newer attempt`() = runBlocking {
        track()
        val params = params()
        coEvery { uploader.uploadReservedFile(any(), any(), any(), any(), any()) } answers {
            val listener = arg<ProgressRequestBody.Listener>(4)
            listener.onStart(false)
            listener.onProgress(24_576, 65_536)
            idle()
            val previous = states.last()
            stopped = true
            listener.onStart(true)
            listener.onProgress(8_192, 65_536)
            listener.onComplete()
            idle()
            assertEquals(previous, states.last())
            throw kotlinx.coroutines.CancellationException("worker stopped")
        }
        try {
            body.execute(params, context)
            throw AssertionError("expected cancellation")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertTrue(File(params.clearFilePath).exists())
        }
    }

    private companion object {
        const val KEY = "\$local.upload-example"
    }
}
