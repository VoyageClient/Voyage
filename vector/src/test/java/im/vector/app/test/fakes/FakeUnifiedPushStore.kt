/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.test.fakes

import im.vector.app.core.pushers.UnifiedPushStore
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify

class FakeUnifiedPushStore {

    val instance = mockk<UnifiedPushStore>()

    fun givenStoreUpEndpoint(upInstance: String, endpoint: String?) {
        justRun { instance.storeUpEndpoint(upInstance, endpoint) }
    }

    fun verifyStoreUpEndpoint(upInstance: String, endpoint: String?) {
        verify { instance.storeUpEndpoint(upInstance, endpoint) }
    }

    fun givenStorePushGateway(upInstance: String, gateway: String?) {
        justRun { instance.storePushGateway(upInstance, gateway) }
    }

    fun verifyStorePushGateway(upInstance: String, gateway: String?) {
        verify { instance.storePushGateway(upInstance, gateway) }
    }
}
