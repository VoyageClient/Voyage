/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.test.fakes

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilitiesService
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional

class FakeHomeServerCapabilitiesService : HomeServerCapabilitiesService by mockk() {

    fun givenCapabilities(homeServerCapabilities: HomeServerCapabilities) {
        every { getHomeServerCapabilities() } returns homeServerCapabilities
    }

    fun givenCapabilitiesFlowReturns(homeServerCapabilities: HomeServerCapabilities): Flow<Optional<HomeServerCapabilities>> {
        return flowOf(homeServerCapabilities.toOptional()).also {
            every { getHomeServerCapabilitiesFlow() } returns it
        }
    }
}
