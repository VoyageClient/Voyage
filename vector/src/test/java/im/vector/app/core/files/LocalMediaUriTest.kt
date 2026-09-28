/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.files

import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.matrix.android.sdk.api.session.room.send.SendState

class LocalMediaUriTest {

    private val localUrl = "file:///data/user/0/app/files/send_cache/abc"
    private val mxcUrl = "mxc://server/abc"

    @Test
    fun `a sending echo may load any url`() {
        SendState.UNSENT.allowsLocalMediaUrl(mxcUrl).shouldBeTrue()
        SendState.ENCRYPTING.allowsLocalMediaUrl(localUrl).shouldBeTrue()
        SendState.SENDING.allowsLocalMediaUrl(null).shouldBeTrue()
    }

    @Test
    fun `a sent echo still holding its local url may load it`() {
        SendState.SENT.allowsLocalMediaUrl(localUrl).shouldBeTrue()
        SendState.SENT.allowsLocalMediaUrl(mxcUrl, "content://picker/1").shouldBeTrue()
    }

    @Test
    fun `a sent echo already on mxc is not treated as local`() {
        SendState.SENT.allowsLocalMediaUrl(mxcUrl).shouldBeFalse()
        SendState.SENT.allowsLocalMediaUrl(null).shouldBeFalse()
    }

    @Test
    fun `synced and failed events never load non-mxc urls`() {
        SendState.SYNCED.allowsLocalMediaUrl(localUrl).shouldBeFalse()
        SendState.UNDELIVERED.allowsLocalMediaUrl(localUrl).shouldBeFalse()
        SendState.UNKNOWN.allowsLocalMediaUrl(localUrl).shouldBeFalse()
    }
}
