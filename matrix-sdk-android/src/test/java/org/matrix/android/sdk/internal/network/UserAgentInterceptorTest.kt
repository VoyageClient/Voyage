/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import okhttp3.Request
import okhttp3.Response
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.provider.RoomDisplayNameFallbackProvider

class UserAgentInterceptorTest {

    private fun interceptedRequest(userAgent: String): Request {
        val interceptor = UserAgentInterceptor(
                MatrixConfiguration(
                        userAgent = userAgent,
                        roomDisplayNameFallbackProvider = mockk<RoomDisplayNameFallbackProvider>(),
                )
        )
        val sent = slot<Request>()
        val chain = mockk<okhttp3.Interceptor.Chain> {
            every { request() } returns Request.Builder().url("https://example.org/").build()
            every { proceed(capture(sent)) } returns mockk<Response>()
        }

        interceptor.intercept(chain)
        return sent.captured
    }

    @Test
    fun `given a user agent, then it is sent as is`() {
        interceptedRequest("Voyage").header(HttpHeaders.UserAgent) shouldBeEqualTo "Voyage"
    }

    @Test
    fun `given a blank user agent, then no header is added`() {
        interceptedRequest("  ").header(HttpHeaders.UserAgent).shouldBeNull()
    }
}
