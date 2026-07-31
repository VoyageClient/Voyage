/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotContain
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.contentscanner.ContentScannerService
import org.matrix.android.sdk.internal.session.media.IsAuthenticatedMediaSupported
import org.robolectric.RobolectricTestRunner

private const val MXC_URL = "mxc://example.org/media"

@RunWith(RobolectricTestRunner::class)
class DefaultContentUrlResolverTest {

    private val scannerService = mockk<ContentScannerService> {
        every { isScannerEnabled() } returns false
    }

    private val urlResolver = DefaultContentUrlResolver(
            homeServerConnectionConfig = HomeServerConnectionConfig.Builder().withHomeServerUri("https://example.org").build(),
            scannerService = scannerService,
            isAuthenticatedMediaSupported = object : IsAuthenticatedMediaSupported {
                override fun invoke() = true
            },
    )

    private fun resolve(animated: Boolean) = urlResolver.resolveThumbnail(
            MXC_URL, 250, 250, ContentUrlResolver.ThumbnailMethod.SCALE, animated
    )

    @Test
    fun `an animated thumbnail is asked for explicitly`() {
        resolve(animated = true)!! shouldContain "animated=true"
    }

    @Test
    fun `a still thumbnail is the default`() {
        resolve(animated = false)!! shouldNotContain "animated"
    }

    @Test
    fun `the two variants are distinct urls, so they are cached separately`() {
        resolve(animated = true) shouldBeEqualTo resolve(animated = false) + "&animated=true"
    }

    @Test
    fun `the variants stay distinct on a homeserver without authenticated media`() {
        val legacyResolver = DefaultContentUrlResolver(
                homeServerConnectionConfig = HomeServerConnectionConfig.Builder().withHomeServerUri("https://example.org").build(),
                scannerService = scannerService,
                isAuthenticatedMediaSupported = object : IsAuthenticatedMediaSupported {
                    override fun invoke() = false
                },
        )

        val still = legacyResolver.resolveThumbnail(MXC_URL, 250, 250, ContentUrlResolver.ThumbnailMethod.SCALE)
        val animated = legacyResolver.resolveThumbnail(MXC_URL, 250, 250, ContentUrlResolver.ThumbnailMethod.SCALE, animated = true)

        still!! shouldNotContain "animated"
        animated shouldBeEqualTo still + "&animated=true"
    }

    @Test
    fun `a non-mxc url resolves to nothing, whether animated or not`() {
        urlResolver.resolveThumbnail("https://example.org/image.gif", 250, 250, ContentUrlResolver.ThumbnailMethod.SCALE, true).shouldBeNull()
        urlResolver.resolveThumbnail("https://example.org/image.gif", 250, 250, ContentUrlResolver.ThumbnailMethod.SCALE, false).shouldBeNull()
    }
}
