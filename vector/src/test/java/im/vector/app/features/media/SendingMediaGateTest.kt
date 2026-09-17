/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.os.Looper
import im.vector.app.features.home.room.detail.timeline.style.TimelineMessageLayout
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SendingMediaGateTest {

    private val renderer = mockk<ImageContentRenderer>(relaxed = true)
    private val gate = SendingMediaGate(RuntimeEnvironment.getApplication(), renderer)

    // Not a Bubble/ScBubble, so the transformation is the plain capped-corner one.
    private val layout = mockk<TimelineMessageLayout>(relaxed = true)

    private val data = ImageContentRenderer.Data(
            eventId = "\$event",
            filename = "cat.jpg",
            mimeType = "image/jpeg",
            url = "content://local/cat.jpg",
            elementToDecrypt = null,
            height = 100,
            maxHeight = 100,
            width = 100,
            maxWidth = 100,
    )

    @Test
    fun `the first ask holds the row`() {
        gate.canShow(data, ImageContentRenderer.Mode.THUMBNAIL, layout) shouldBeEqualTo false
        gate.isHolding("\$event") shouldBeEqualTo true
    }

    @Test
    fun `the hold cannot outlive its timeout`() {
        gate.canShow(data, ImageContentRenderer.Mode.THUMBNAIL, layout) shouldBeEqualTo false

        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5))

        gate.canShow(data, ImageContentRenderer.Mode.THUMBNAIL, layout) shouldBeEqualTo true
        gate.isHolding("\$event") shouldBeEqualTo false
    }

    @Test
    fun `once shown it is never held again`() {
        gate.canShow(data, ImageContentRenderer.Mode.THUMBNAIL, layout)
        ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5))
        gate.canShow(data, ImageContentRenderer.Mode.THUMBNAIL, layout) shouldBeEqualTo true

        gate.canShow(data, ImageContentRenderer.Mode.THUMBNAIL, layout) shouldBeEqualTo true
    }

    @Test
    fun `a build request missed while detached is replayed on attach`() {
        gate.onRequestBuild = null
        gate.canShow(data, ImageContentRenderer.Mode.THUMBNAIL, layout)

        // Let the hold's own timeout fire while still detached.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        gate.isHolding("\$event") shouldBeEqualTo false

        var builds = 0
        gate.onRequestBuild = { builds++ }

        builds shouldBeEqualTo 1
    }

    @Test
    fun `a settle while attached requests the build there and then`() {
        var builds = 0
        gate.onRequestBuild = { builds++ }
        gate.canShow(data, ImageContentRenderer.Mode.THUMBNAIL, layout)

        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))

        builds shouldBeEqualTo 1
        // And nothing is left owed, so re-attaching does not build again.
        gate.onRequestBuild = { builds++ }
        builds shouldBeEqualTo 1
    }

    @Test
    fun `attaching with nothing missed does not force a build`() {
        var builds = 0

        gate.onRequestBuild = { builds++ }

        builds shouldBeEqualTo 0
    }

    @Test
    fun `clearing forgets the holds`() {
        gate.canShow(data, ImageContentRenderer.Mode.THUMBNAIL, layout)

        gate.clearAll()

        gate.isHolding("\$event") shouldBeEqualTo false
    }
}
