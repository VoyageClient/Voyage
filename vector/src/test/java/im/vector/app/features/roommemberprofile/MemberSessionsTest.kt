/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.api.session.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.api.session.crypto.model.UnsignedDeviceInfo

class MemberSessionsTest {

    private fun device(id: String, name: String?, crossSigned: Boolean = false, local: Boolean = false) = CryptoDeviceInfo(
            deviceId = id,
            userId = "@alice:example.org",
            unsigned = UnsignedDeviceInfo(name),
            trustLevel = DeviceTrustLevel(crossSigned, local),
    )

    private val phone = device("PHONE", "Phone", crossSigned = true)
    private val laptop = device("LAPTOP", "laptop")
    private val tablet = device("TABLET", "Tablet", local = true)
    private val all = listOf(tablet, phone, laptop)

    @Test
    fun `an unverified user's sessions are sorted by name`() {
        val sessions = orderMemberSessions(all, isMine = false, isUserVerified = false)

        sessions.sessions.map { it.deviceId } shouldBeEqualTo listOf("LAPTOP", "PHONE", "TABLET")
    }

    @Test
    fun `a verified user's unverified sessions go first`() {
        val sessions = orderMemberSessions(listOf(phone, tablet, laptop), isMine = false, isUserVerified = true)

        sessions.sessions.map { it.deviceId } shouldBeEqualTo listOf("LAPTOP", "PHONE", "TABLET")
        sessions.hasUnverified shouldBe true
    }

    @Test
    fun `own sessions only count as verified when cross-signed`() {
        val sessions = orderMemberSessions(all, isMine = true, isUserVerified = true)

        sessions.sessions.map { it.deviceId } shouldBeEqualTo listOf("LAPTOP", "TABLET", "PHONE")
    }

    @Test
    fun `all verified sessions report no unverified`() {
        orderMemberSessions(listOf(phone, tablet), isMine = false, isUserVerified = true).hasUnverified shouldBe false
    }

    @Test
    fun `a single dehydrated device is hidden but reported`() {
        val offline = device("OFFLINE", "Dehydrated device", crossSigned = true).copy(dehydrated = true)
        val sessions = orderMemberSessions(all + offline, isMine = false, isUserVerified = false)

        sessions.sessions.map { it.deviceId } shouldBeEqualTo listOf("LAPTOP", "PHONE", "TABLET")
        sessions.hasDehydratedDevice shouldBe true
    }

    @Test
    fun `several dehydrated devices all stay visible`() {
        val first = device("OFF1", "Dehydrated").copy(dehydrated = true)
        val second = device("OFF2", "Dehydrated").copy(dehydrated = true)
        val sessions = orderMemberSessions(listOf(phone, first, second), isMine = false, isUserVerified = false)

        sessions.sessions.size shouldBeEqualTo 3
        sessions.hasDehydratedDevice shouldBe false
    }

    @Test
    fun `duplicate names get the device id and blank names fall back to it`() {
        val second = device("PHONE2", " Phone ")
        val unnamed = device("NONAME", "  ")
        val sessions = orderMemberSessions(listOf(phone, second, unnamed), isMine = false, isUserVerified = false)

        phone.sessionDisplayName(sessions.ambiguousNames) shouldBeEqualTo "Phone (PHONE)"
        second.sessionDisplayName(sessions.ambiguousNames) shouldBeEqualTo "Phone (PHONE2)"
        unnamed.sessionDisplayName(sessions.ambiguousNames) shouldBeEqualTo "NONAME"
        laptop.sessionDisplayName(sessions.ambiguousNames) shouldBeEqualTo "laptop"
    }

    @Test
    fun `a name that repeats the device id is not shown twice`() {
        val idNamed = device("ABCDEF", "ABCDEF")
        val twin = device("ABCDEF2", "ABCDEF")
        val sessions = orderMemberSessions(listOf(idNamed, twin), isMine = false, isUserVerified = false)

        idNamed.distinctDisplayName() shouldBeEqualTo null
        idNamed.sessionCopyText() shouldBeEqualTo "ABCDEF"
        twin.sessionCopyText() shouldBeEqualTo "ABCDEF (ABCDEF2)"
        idNamed.sessionDisplayName(sessions.ambiguousNames) shouldBeEqualTo "ABCDEF"
        twin.sessionDisplayName(sessions.ambiguousNames) shouldBeEqualTo "ABCDEF (ABCDEF2)"
    }
}
