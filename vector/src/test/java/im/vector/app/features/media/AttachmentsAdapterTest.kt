/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import androidx.recyclerview.widget.RecyclerView
import im.vector.lib.attachmentviewer.AttachmentInfo
import im.vector.lib.attachmentviewer.AttachmentSourceProvider
import im.vector.lib.attachmentviewer.AttachmentsAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AttachmentsAdapterTest {

    @Test
    fun `opening a room list reads page identities without building every media descriptor`() {
        val source = provider(List(500) { video("video-$it") })
        val adapter = AttachmentsAdapter()

        adapter.attachmentSourceProvider = source

        assertEquals(500, adapter.itemCount)
        verify(exactly = 0) { source.getAttachmentInfoAt(any()) }
    }

    @Test
    fun `expanding a provisional list keeps the playing video without rebinding it`() {
        val adapter = AttachmentsAdapter()
        val video = video("video-a", "initial metadata")
        adapter.attachmentSourceProvider = provider(listOf(video))
        val id = adapter.getItemId(0)
        val observer = Changes()
        adapter.registerAdapterDataObserver(observer)

        adapter.attachmentSourceProvider = provider(listOf(video("video-b"), video.copy(data = "room metadata")))

        assertEquals(2, adapter.itemCount)
        assertEquals(id, adapter.getItemId(1))
        assertEquals(0, observer.changed)
    }

    @Test
    fun `replacing the video content rebinds the page even if its uid stays the same`() {
        val adapter = AttachmentsAdapter()
        val video = video("video-a")
        adapter.attachmentSourceProvider = provider(listOf(video))
        val observer = Changes()
        adapter.registerAdapterDataObserver(observer)

        adapter.attachmentSourceProvider = provider(listOf(video.copy(resourceKey = "replacement")))

        assertEquals(1, observer.changed)
    }

    @Test
    fun `different page uids keep distinct stable ids even when their hashes collide`() {
        val adapter = AttachmentsAdapter()
        adapter.attachmentSourceProvider = provider(listOf(video("FB"), video("Ea")))

        assertNotEquals(adapter.getItemId(0), adapter.getItemId(1))
    }

    @Test
    fun `changing media type keeps the page identity and updates its view type`() {
        val adapter = AttachmentsAdapter()
        adapter.attachmentSourceProvider = provider(listOf(video("media-a")))
        val id = adapter.getItemId(0)
        val viewType = adapter.getItemViewType(0)
        val observer = Changes()
        adapter.registerAdapterDataObserver(observer)

        adapter.attachmentSourceProvider = provider(listOf(AttachmentInfo.Image("media-a", "mxc://example.org/image", null)))

        assertEquals(id, adapter.getItemId(0))
        assertNotEquals(viewType, adapter.getItemViewType(0))
        assertEquals(1, observer.changed)
    }

    private fun video(uid: String, metadata: String = "metadata") = AttachmentInfo.Video(
            uid = uid,
            url = "mxc://example.org/$uid",
            data = metadata,
            thumbnail = null,
            resourceKey = uid,
    )

    private fun provider(attachments: List<AttachmentInfo>): AttachmentSourceProvider = mockk {
        every { getItemCount() } returns attachments.size
        every { getAttachmentIdAt(any()) } answers { attachments[firstArg()].uid }
        every { getAttachmentInfoAt(any()) } answers { attachments[firstArg()] }
    }

    private class Changes : RecyclerView.AdapterDataObserver() {
        var changed = 0

        override fun onChanged() {
            changed++
        }

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
            changed += itemCount
        }
    }
}
