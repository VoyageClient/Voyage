/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.test.fakes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.matrix.android.sdk.api.session.accountdata.SessionAccountDataService
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional

class FakeSessionAccountDataService : SessionAccountDataService by mockk(relaxed = true) {

    fun givenGetUserAccountDataEventReturns(type: String, content: Content?) {
        every { getUserAccountDataEvent(type) } returns content?.let { UserAccountDataEvent(type, it) }
    }

    fun givenGetUserAccountDataEventFlowReturns(type: String, content: Content?): Flow<Optional<UserAccountDataEvent>> {
        return flowOf(content?.let { UserAccountDataEvent(type, it) }.toOptional())
                .also {
                    every { getUserAccountDataEventFlow(type) } returns it
                }
    }

    fun givenUpdateUserAccountDataEventSucceeds() {
        coEvery { updateUserAccountData(any(), any()) } just runs
    }

    fun givenUpdateUserAccountDataEventFailsWithError(error: Exception) {
        coEvery { updateUserAccountData(any(), any()) } throws error
    }

    fun verifyUpdateUserAccountDataEventSucceeds(type: String, content: Content, inverse: Boolean = false) {
        coVerify(inverse = inverse) { updateUserAccountData(type, content) }
    }

    fun givenGetUserAccountDataEventsStartWith(type: String, userAccountDataEventList: List<UserAccountDataEvent>) {
        every { getUserAccountDataEventsStartWith(type) } returns userAccountDataEventList
    }
}
