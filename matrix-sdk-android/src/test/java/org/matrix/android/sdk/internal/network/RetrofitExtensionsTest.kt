/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.failure.Failure

class RetrofitExtensionsTest {

    @Test
    fun `non-Matrix error keeps its body and the request url`() {
        val url = "https://matrix.example.org/_matrix/client/v3/register"
        val page = "<html>Sorry, you have been blocked</html>"
        val response = Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("")
                .body(ResponseBody.create(MediaType.parse("text/html"), page))
                .build()

        response.toFailure(null) shouldBeEqualTo Failure.OtherServerError(page, 403, url)
    }
}
