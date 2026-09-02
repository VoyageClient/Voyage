/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.auth.LoginType
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.test.fixtures.CredentialsFixture.aCredentials

private const val PRIMARY = "https://primary.org/"
private const val SECONDARY = "https://secondary.org/"
private const val TERTIARY = "https://tertiary.org/"

class HomeServerFallbackTrackerTest {

    private fun tracker(vararg urls: String) = HomeServerFallbackTracker(
            SessionParams(
                    credentials = aCredentials(),
                    homeServerConnectionConfig = HomeServerConnectionConfig.Builder()
                            .withHomeServerUri(urls.first())
                            .build()
                            .copy(fallbackHomeServerUriBases = urls.drop(1)),
                    isTokenValid = true,
                    loginType = LoginType.PASSWORD,
            )
    )

    @Test
    fun `a single URL has nothing to fail over to`() {
        val tracker = tracker(PRIMARY)

        tracker.hasMirrors() shouldBeEqualTo false
        tracker.candidates() shouldBeEqualTo listOf(PRIMARY)
        tracker.active() shouldBeEqualTo PRIMARY
    }

    @Test
    fun `candidates keep the configured order until a mirror is marked down`() {
        val tracker = tracker(PRIMARY, SECONDARY, TERTIARY)

        tracker.candidates() shouldBeEqualTo listOf(PRIMARY, SECONDARY, TERTIARY)

        tracker.markDown(PRIMARY)

        tracker.candidates() shouldBeEqualTo listOf(SECONDARY, TERTIARY, PRIMARY)
    }

    /**
     * A fallback must not outlive the outage: ordinary traffic, not only the foreground probe, has to
     * find its way back to the mirror the user ranked higher.
     */
    @Test
    fun `a recovered mirror is retried first again once its down mark is cleared`() {
        val tracker = tracker(PRIMARY, SECONDARY)

        tracker.markDown(PRIMARY)
        tracker.onReached(SECONDARY)
        tracker.candidates() shouldBeEqualTo listOf(SECONDARY, PRIMARY)

        tracker.onReached(PRIMARY)

        tracker.candidates() shouldBeEqualTo listOf(PRIMARY, SECONDARY)
        tracker.active() shouldBeEqualTo PRIMARY
    }

    @Test
    fun `a response in flight on a lower ranked mirror does not undo the switch back`() {
        val tracker = tracker(PRIMARY, SECONDARY)

        tracker.markDown(PRIMARY)
        tracker.onReached(SECONDARY)
        tracker.onReached(PRIMARY)
        tracker.onReached(SECONDARY)

        tracker.active() shouldBeEqualTo PRIMARY
    }

    @Test
    fun `a lower ranked mirror takes over while the higher ranked one is down`() {
        val tracker = tracker(PRIMARY, SECONDARY)

        tracker.onReached(PRIMARY)
        tracker.markDown(PRIMARY)
        tracker.onReached(SECONDARY)

        tracker.active() shouldBeEqualTo SECONDARY
    }

    @Test
    fun `editing the list resets the down marks and drops a mirror that is gone`() {
        val tracker = tracker(PRIMARY, SECONDARY)

        tracker.markDown(PRIMARY)
        tracker.onReached(SECONDARY)
        tracker.update(listOf(TERTIARY, PRIMARY))

        tracker.configured() shouldBeEqualTo listOf(TERTIARY, PRIMARY)
        tracker.candidates() shouldBeEqualTo listOf(TERTIARY, PRIMARY)
        tracker.active() shouldBeEqualTo TERTIARY
    }

    @Test
    fun `URLs are normalized to a trailing slash`() {
        val tracker = tracker(PRIMARY)

        tracker.update(listOf("https://primary.org", "https://secondary.org"))

        tracker.configured() shouldBeEqualTo listOf(PRIMARY, SECONDARY)
    }
}
