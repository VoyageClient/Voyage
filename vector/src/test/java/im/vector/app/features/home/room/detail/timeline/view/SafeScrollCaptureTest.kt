/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.view

import android.app.Activity
import android.graphics.Point
import android.graphics.Rect
import android.os.CancellationSignal
import android.view.ScrollCaptureCallback
import android.view.ScrollCaptureSession
import android.view.ScrollCaptureTarget
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.function.Consumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeScrollCaptureTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val adapter = RowAdapter()
    private val list = TimelineRecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity, RecyclerView.VERTICAL, true)
        adapter = this@SafeScrollCaptureTest.adapter
        itemAnimator = null
        activity.setContentView(this)
        measure(MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(400, MeasureSpec.EXACTLY))
        layout(0, 0, 400, 400)
    }
    private val session = mockk<ScrollCaptureSession>(relaxed = true)
    private val signal = CancellationSignal()

    @Test
    fun `ends the capture instead of crashing when the platform helper loses its anchor`() {
        val delegate = FakeDelegate { throw IllegalArgumentException("parameter must be a descendant of this view") }
        var completed: Rect? = null

        SafeScrollCapture.Callback(list, delegate).onScrollCaptureImageRequest(session, signal, Rect(0, 0, 400, 100)) { completed = it }

        completed shouldBeEqualTo Rect()
    }

    @Test
    fun `applies queued adapter updates before the platform helper picks its anchor`() {
        adapter.count = 2
        adapter.notifyItemRangeRemoved(2, 8)
        list.hasPendingAdapterUpdates().shouldBeTrue()
        var pendingDuringRequest = true
        val delegate = FakeDelegate { pendingDuringRequest = list.hasPendingAdapterUpdates() }

        SafeScrollCapture.Callback(list, delegate).onScrollCaptureImageRequest(session, signal, Rect(0, 0, 400, 100)) {}

        pendingDuringRequest.shouldBeFalse()
        list.childCount shouldBeEqualTo 2
    }

    @Test
    fun `wraps only the target of the timeline itself`() {
        val ownTarget = ScrollCaptureTarget(list, Rect(0, 0, 400, 400), Point(), FakeDelegate {})
        val nestedTarget = ScrollCaptureTarget(View(activity), Rect(0, 0, 10, 10), Point(), FakeDelegate {})
        val found = mutableListOf<ScrollCaptureTarget>()
        val wrapped = SafeScrollCapture.wrapTargets(list) { found += it }

        wrapped.accept(ownTarget)
        wrapped.accept(nestedTarget)

        found[0].callback shouldBeInstanceOf SafeScrollCapture.Callback::class
        found[0].containingView shouldBe list
        found[1] shouldBe nestedTarget
    }

    private class FakeDelegate(private val onImageRequest: () -> Unit) : ScrollCaptureCallback {
        override fun onScrollCaptureSearch(signal: CancellationSignal, onReady: Consumer<Rect>) = Unit
        override fun onScrollCaptureStart(session: ScrollCaptureSession, signal: CancellationSignal, onReady: Runnable) = Unit
        override fun onScrollCaptureImageRequest(session: ScrollCaptureSession, signal: CancellationSignal, captureArea: Rect, onComplete: Consumer<Rect>) =
                onImageRequest()

        override fun onScrollCaptureEnd(onReady: Runnable) = Unit
    }

    private class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var count = 10

        override fun getItemCount() = count
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                View(parent.context).apply { layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100) }
        ) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }
}
