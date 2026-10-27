/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.view.View
import android.view.ViewParent
import android.widget.RelativeLayout
import im.vector.app.R
import im.vector.app.core.platform.CheckableView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BaseEventItemHighlightTest {
    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }
    private val model = EventItem("target")
    private val holder = model.createNewHolder(RelativeLayout(context)).apply {
        view = RelativeLayout(context).apply {
            addView(View(context).apply { id = R.id.messageStartGuideline })
            addView(View(context).apply { id = R.id.messageRowTint })
            addView(CheckableView(context).apply { id = R.id.messageSelectedBackground })
        }
        model.bind(this)
    }

    @Test
    fun `a visible row flashes immediately and its later model does not replay the flash`() {
        assertTrue(model.showHighlight(holder, 2L))
        val background = holder.checkableBackground.background
        assertNotNull(background)
        model.highlighted = true
        model.highlightNonce = 2L
        model.bind(holder)
        assertSame(background, holder.checkableBackground.background)
        assertFalse(model.showHighlight(holder, 2L))
    }

    @Test
    fun `a stale model cannot clear or restart the direct flash before its first draw`() {
        model.showHighlight(holder, 2L)
        val background = holder.checkableBackground.background
        model.bind(holder)
        assertSame(background, holder.checkableBackground.background)
        model.highlighted = true
        model.highlightNonce = 1L
        model.bind(holder)
        assertSame(background, holder.checkableBackground.background)
        assertTrue(model.showHighlight(holder, 3L))
        assertNotSame(background, holder.checkableBackground.background)
    }

    @Test
    fun `recycling clears the highlight without replaying it for the same jump`() {
        model.showHighlight(holder, 2L)
        model.unbind(holder)
        assertNull(holder.checkableBackground.background)
        model.highlighted = true
        model.highlightNonce = 2L
        model.bind(holder)
        assertNull(holder.checkableBackground.background)
        assertTrue(model.showHighlight(holder, 3L))
    }

    @Test
    fun `a reused holder never flashes the previous event`() {
        model.showHighlight(holder, 2L)
        val other = EventItem("other")
        other.bind(holder)
        assertNull(holder.checkableBackground.background)
        assertFalse(model.showHighlight(holder, 3L))
        assertTrue(other.showHighlight(holder, 3L))
    }

    private class EventItem(private val eventId: String) : BaseEventItem<BaseEventItem.BaseHolder>(0) {
        override fun getEventIds() = listOf(eventId)
        override fun getViewStubId() = 0
        public override fun createNewHolder(parent: ViewParent) = object : BaseHolder(0) {}
    }
}
