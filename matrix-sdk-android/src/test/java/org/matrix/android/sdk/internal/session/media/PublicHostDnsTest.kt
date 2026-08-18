/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import okhttp3.Dns
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class PublicHostDnsTest {

    private fun address(literal: String): InetAddress = InetAddress.getByName(literal)

    private fun dnsReturning(vararg literals: String) = PublicHostDns(Dns { literals.map { address(it) } })

    @Test
    fun `a public address is routable`() {
        address("8.8.8.8").isPubliclyRoutable().shouldBeTrue()
        address("2606:4700:4700::1111").isPubliclyRoutable().shouldBeTrue()
    }

    @Test
    fun `the phone's own network is not`() {
        listOf(
                "127.0.0.1", // loopback
                "0.0.0.0", // any local
                "10.0.0.1", // private
                "172.16.0.1", // private
                "192.168.1.1", // private
                "169.254.1.1", // link local
                "224.0.0.1", // multicast
                "100.64.0.1", // carrier grade NAT
                "::1", // loopback
                "fe80::1", // link local
                "fc00::1", // unique local
                "fd12:3456::1" // unique local
        ).forEach { literal ->
            withClue(literal) { address(literal).isPubliclyRoutable().shouldBeFalse() }
        }
    }

    @Test
    fun `a name which resolves to a public address is allowed through`() {
        dnsReturning("8.8.8.8").lookup("example.org") shouldBeEqualTo listOf(address("8.8.8.8"))
    }

    @Test
    fun `a public name pointing at a private address is refused`() {
        try {
            dnsReturning("192.168.1.1").lookup("sneaky.example.org")
            throw AssertionError("Expected the lookup to fail")
        } catch (failure: UnknownHostException) {
            failure.message!!.contains("sneaky.example.org").shouldBeTrue()
        }
    }

    @Test
    fun `only the public addresses of a name are kept`() {
        dnsReturning("192.168.1.1", "8.8.8.8").lookup("mixed.example.org") shouldBeEqualTo listOf(address("8.8.8.8"))
    }

    private fun withClue(clue: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: AssertionError) {
            throw AssertionError("$clue: ${failure.message}", failure)
        }
    }
}
