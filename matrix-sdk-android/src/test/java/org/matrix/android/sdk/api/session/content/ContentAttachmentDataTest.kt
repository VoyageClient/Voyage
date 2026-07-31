/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentAttachmentDataTest {

    private val attachment = ContentAttachmentData(
            size = 42,
            name = "picture.png",
            queryUri = "content://media/external/images/media/1",
            mimeType = "image/png",
            type = ContentAttachmentData.Type.IMAGE,
    )

    /**
     * The JSON shape must stay identical to when queryUri was android.net.Uri (which serialized as
     * the plain string): pending uploads persisted before the type change must keep loading.
     */
    @Test
    fun `json round-trips and queryUri keeps the legacy plain-string shape`() {
        val json = attachment.toJsonString()
        assertTrue(json, json.contains(""""queryUri":"content://media/external/images/media/1""""))
        assertEquals(attachment, ContentAttachmentData.fromJsonString(json))
    }
}
