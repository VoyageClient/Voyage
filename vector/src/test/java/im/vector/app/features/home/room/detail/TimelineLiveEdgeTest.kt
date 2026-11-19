/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.SimpleEpoxyController
import im.vector.app.R
import im.vector.app.features.home.room.detail.timeline.item.ItemWithEvents
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineLiveEdgeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val recycler = RecyclerView(context)
    private val layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, true)

    @Test
    fun `the newest message counts as read while at least half its final line shows`() {
        show(spacer(), message("\$newest", lines = 30), message("\$older", lines = 2))
        TimelineLiveEdge.isAtLiveEdge(recycler, layoutManager) shouldBeEqualTo true

        val gap = recycler.height - finalLine().centerY()
        scrollTowardOlder(gap - 2)
        TimelineLiveEdge.isAtLiveEdge(recycler, layoutManager) shouldBeEqualTo true

        scrollTowardOlder(4)
        TimelineLiveEdge.isAtLiveEdge(recycler, layoutManager) shouldBeEqualTo false
    }

    @Test
    fun `reading the start of a tall newest message is not the live edge`() {
        show(spacer(), message("\$newest", lines = 60), message("\$older", lines = 2))

        scrollTowardOlder(recycler.height)

        layoutManager.findFirstVisibleItemPosition() shouldBeEqualTo 1
        TimelineLiveEdge.isAtLiveEdge(recycler, layoutManager) shouldBeEqualTo false
    }

    private fun show(vararg models: EpoxyModel<*>) {
        recycler.layoutManager = layoutManager
        recycler.adapter = SimpleEpoxyController().apply { setModels(models.toList()) }.adapter
        recycler.measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        recycler.layout(0, 0, WIDTH, HEIGHT)
    }

    private fun scrollTowardOlder(px: Int) {
        recycler.scrollBy(0, -px)
    }

    private fun finalLine(): Rect {
        val text = recycler.findViewById<TextView>(R.id.messageTextView)
        val last = text.layout.lineCount - 1
        return Rect(0, text.layout.getLineTop(last), text.width, text.layout.getLineBottom(last))
                .also { recycler.offsetDescendantRectToMyCoords(text, it) }
    }

    private fun spacer() = object : EpoxyModel<View>() {
        override fun getDefaultLayout() = 0
        override fun buildView(parent: ViewGroup) = View(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40)
        }
    }.id("typing")

    private fun message(eventId: String, lines: Int) = MessageModel(eventId, lines).id(eventId)

    private class MessageModel(private val eventId: String, private val lines: Int) : EpoxyModel<View>(), ItemWithEvents {
        override fun getDefaultLayout() = 0
        override fun getEventIds() = listOf(eventId)
        override fun buildView(parent: ViewGroup) = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(parent.context).apply {
                id = R.id.messageTextView
                setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f)
                text = (1..lines).joinToString("\n") { "line $it" }
            })
            // Reactions and receipts below the body.
            addView(View(parent.context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60))
        }
    }

    companion object {
        private const val WIDTH = 400
        private const val HEIGHT = 300
    }
}
