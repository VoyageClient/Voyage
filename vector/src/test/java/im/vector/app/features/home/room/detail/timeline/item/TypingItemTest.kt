/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.item

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import im.vector.app.core.ui.views.TypingMessageView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TypingItemTest {

    private val params = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private var visibility = View.INVISIBLE
    private val view = mockk<TypingMessageView>(relaxed = true).also { view ->
        val assigned = slot<ViewGroup.LayoutParams>()
        every { view.layoutParams } answers { params }
        every { view.layoutParams = capture(assigned) } answers { params.height = assigned.captured.height }
        every { view.visibility } answers { visibility }
        val newVisibility = slot<Int>()
        every { view.visibility = capture(newVisibility) } answers { visibility = newVisibility.captured }
        every { view.resources } returns mockk {
            every { getDimensionPixelSize(im.vector.lib.ui.styles.R.dimen.typing_idle_height) } returns IDLE_HEIGHT_PX
        }
    }

    private companion object {
        const val IDLE_HEIGHT_PX = 16
    }
    private val holder = mockk<TypingItem.TypingHolder>().also { every { it.typingView } returns view }

    private fun bind(users: List<SenderInfo>) {
        TypingItem_().avatarRenderer(mockk(relaxed = true)).users(users).bind(holder)
    }

    @Test
    fun `an idle typing row shrinks to its spacer height`() {
        bind(emptyList())

        params.height shouldBeEqualTo IDLE_HEIGHT_PX
    }

    @Test
    fun `the row regains its height when someone starts typing`() {
        bind(emptyList())

        bind(listOf(SenderInfo("@alice:example.org", "Alice", isUniqueDisplayName = true, avatarUrl = null)))

        params.height shouldBeEqualTo ViewGroup.LayoutParams.WRAP_CONTENT
        visibility shouldBeEqualTo View.VISIBLE
    }
}
