/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundDetectionObserverTest {

    private val observer = DefaultBackgroundDetectionObserver()

    @Test
    fun `starts in background and follows app-state transitions`() {
        assertTrue(observer.isInBackground)
        observer.onAppForeground()
        assertFalse(observer.isInBackground)
        observer.onAppBackground()
        assertTrue(observer.isInBackground)
    }

    @Test
    fun `registered listeners get transitions and unregistered ones do not`() {
        val events = mutableListOf<String>()
        val listener = object : BackgroundDetectionObserver.Listener {
            override fun onMoveToForeground() {
                events.add("foreground")
            }

            override fun onMoveToBackground() {
                events.add("background")
            }
        }
        observer.register(listener)
        observer.onAppForeground()
        observer.onAppBackground()
        observer.unregister(listener)
        observer.onAppForeground()

        assertEquals(listOf("foreground", "background"), events)
    }
}
