/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.test.fakes

import im.vector.app.features.notifications.NotifiableEvent
import im.vector.app.features.notifications.NotifiableMessageEvent
import im.vector.app.features.notifications.OutdatedEventDetector
import io.mockk.every
import io.mockk.mockk

class FakeOutdatedEventDetector {
    private val outdatedIds = mutableSetOf<String>()
    val instance = mockk<OutdatedEventDetector> {
        every { readEventIds(any()) } answers {
            firstArg<List<NotifiableMessageEvent>>().map { it.eventId }.filterTo(HashSet()) { it in outdatedIds }
        }
    }

    fun givenEventIsOutOfDate(notifiableEvent: NotifiableEvent) {
        outdatedIds.add(notifiableEvent.eventId)
    }

    fun givenEventIsInDate(notifiableEvent: NotifiableEvent) {
        outdatedIds.remove(notifiableEvent.eventId)
    }
}
