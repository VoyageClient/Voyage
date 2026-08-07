/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.MatrixUrls.toMxcParts

/**
 * MSC2246 uploads bytes to `/upload/{serverName}/{mediaId}`, so the reserved URI has to split cleanly.
 */
class MatrixUrlsTest {

    @Test
    fun `splits an mxc uri into server name and media id`() {
        "mxc://example.org/AbCdEf123".toMxcParts() shouldBeEqualTo ("example.org" to "AbCdEf123")
    }

    @Test
    fun `keeps a media id containing slashes intact`() {
        "mxc://example.org/a/b/c".toMxcParts() shouldBeEqualTo ("example.org" to "a/b/c")
    }

    @Test
    fun `handles a server name with a port`() {
        "mxc://example.org:8448/AbCdEf".toMxcParts() shouldBeEqualTo ("example.org:8448" to "AbCdEf")
    }

    @Test
    fun `rejects a non-mxc url`() {
        "https://example.org/AbCdEf".toMxcParts().shouldBeNull()
    }

    @Test
    fun `rejects an mxc uri with no media id`() {
        "mxc://example.org".toMxcParts().shouldBeNull()
        "mxc://example.org/".toMxcParts().shouldBeNull()
    }

    @Test
    fun `rejects an mxc uri with no server name`() {
        "mxc:///AbCdEf".toMxcParts().shouldBeNull()
    }
}
