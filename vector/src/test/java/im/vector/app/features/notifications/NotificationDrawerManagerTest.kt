/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.notifications

import android.os.HandlerThread
import im.vector.app.ActiveSessionDataSource
import im.vector.app.test.fixtures.aNotifiableMessageEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationDrawerManagerTest {
    private val displayer = mockk<NotificationDisplayer>(relaxed = true) {
        every { canShowNotifications() } returns true
    }
    private val processor = mockk<NotifiableEventProcessor> {
        every { process(any(), any(), any(), any(), any()) } answers {
            firstArg<List<NotifiableEvent>>().map { ProcessedEvent(ProcessedEvent.Type.KEEP, it) }
        }
    }
    private val persistence = mockk<NotificationEventPersistence>(relaxed = true) {
        every { loadEvents(any()) } answers {
            firstArg<(List<NotifiableEvent>) -> NotificationEventQueue>()(emptyList())
        }
    }
    private val manager = NotificationDrawerManager(
            context = RuntimeEnvironment.getApplication(),
            notificationDisplayer = displayer,
            vectorPreferences = mockk(relaxed = true) { every { areNotificationEnabledForDevice() } returns true },
            activeSessionDataSource = ActiveSessionDataSource(),
            notifiableEventProcessor = processor,
            notificationRenderer = mockk(relaxed = true),
            notificationEventPersistence = persistence,
            filteredEventDetector = mockk(relaxed = true),
            buildMeta = mockk(relaxed = true),
    )
    private val thread = ReflectionHelpers.getField<HandlerThread>(manager, "handlerThread")

    @After
    fun tearDown() {
        thread.quit()
        thread.join(1000)
    }

    @Test
    fun `a new refresh preserves account cleanup and shared events for the new account`() {
        shadowOf(thread.looper).pause()
        val old = aNotifiableMessageEvent(eventId = "shared-event", roomId = "!shared:example.org")
        val fresh = old.copy()
        manager.updateEvents { it.add(old) }

        PushRuleTriggerListener(mockk(), manager).stop()
        manager.updateEvents { it.add(fresh) }
        shadowOf(thread.looper).idleFor(1, TimeUnit.SECONDS)

        verify(exactly = 1) { displayer.cancelAllNotifications() }
        val events = slot<List<NotifiableEvent>>()
        verify(exactly = 1) { processor.process(capture(events), any(), any(), any(), any()) }
        events.captured shouldBeEqualTo listOf(fresh)
    }
    @Test
    fun `disabled notifications clear the queue before processing events`() {
        shadowOf(thread.looper).pause()
        every { displayer.canShowNotifications() } returns false
        manager.updateEvents { it.add(aNotifiableMessageEvent(eventId = "blocked-event", roomId = "!room:example.org")) }
        shadowOf(thread.looper).idleFor(1, TimeUnit.SECONDS)

        verify(exactly = 0) { processor.process(any(), any(), any(), any(), any()) }
        val queue = slot<NotificationEventQueue>()
        verify { persistence.persistEvents(capture(queue)) }
        queue.captured.isEmpty() shouldBeEqualTo true
    }
}
