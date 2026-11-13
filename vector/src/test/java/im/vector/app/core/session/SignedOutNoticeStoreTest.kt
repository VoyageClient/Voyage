/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import im.vector.app.test.fixtures.CredentialsFixture.aCredentials
import im.vector.app.test.fixtures.SessionParamsFixture.aSessionParams
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SignedOutNoticeStoreTest {

    private val context = RuntimeEnvironment.getApplication()
    private var sessions = listOf(aSessionParams(aCredentials(userId = "@other:server", deviceId = "OTHER")))
    private val authenticationService = mockk<AuthenticationService> {
        every { getAllSessionParams() } answers { sessions }
    }

    private fun newStore() = SignedOutNoticeStore(context, authenticationService)

    @Test
    fun `notice survives a new store instance`() {
        newStore().set("@gone:server", "Gone", cachedAvatar = null)

        val notice = newStore().get()

        notice shouldBeEqualTo SignedOutNotice("@gone:server", "Gone")
    }

    @Test
    fun `remaining accounts known at sign-out keep the notice pending`() {
        val store = newStore()
        store.set("@gone:server", null, cachedAvatar = null)

        store.isPending().shouldBeTrue()
    }

    @Test
    fun `a new sign-in expires the notice`() {
        val store = newStore()
        store.set("@gone:server", null, cachedAvatar = null)

        sessions = sessions + aSessionParams(aCredentials(userId = "@gone:server", deviceId = "NEWDEVICE"))

        store.isPending().shouldBeFalse()
        newStore().get().shouldBeNull()
    }

    @Test
    fun `removing a remaining account keeps the notice pending`() {
        val store = newStore()
        store.set("@gone:server", null, cachedAvatar = null)

        sessions = emptyList()

        store.isPending().shouldBeTrue()
    }

    @Test
    fun `avatar is moved into the store and removed on clear`() {
        val store = newStore()
        val cached = File(context.filesDir, "cached.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        store.set("@gone:server", null, cachedAvatar = cached)

        cached.exists().shouldBeFalse()
        store.avatarFile.readBytes().toList() shouldBeEqualTo listOf<Byte>(1, 2, 3)

        store.clear()

        store.avatarFile.exists().shouldBeFalse()
        store.isPending().shouldBeFalse()
    }
}
