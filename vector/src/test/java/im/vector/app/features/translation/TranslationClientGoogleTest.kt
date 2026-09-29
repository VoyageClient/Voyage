/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

import im.vector.app.core.resources.StringProvider
import im.vector.app.core.vpn.VpnGateInterceptor
import im.vector.app.features.translation.ondevice.NllbTranslator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test

/** Drives the real OkHttp stack; the VPN-gate interceptor stands in for the network. */
class TranslationClientGoogleTest {

    private val requests = mutableListOf<Request>()
    private var webResponse: Pair<Int, String> = 200 to WEB_OK

    private val settings = mockk<TranslationSettings> {
        every { engine } returns TranslationEngine.GOOGLE
        every { backupEngine } returns null
        every { apiKey(any()) } returns ""
    }

    private val network = mockk<VpnGateInterceptor> {
        every { intercept(any()) } answers {
            val request = firstArg<Interceptor.Chain>().request()
            requests += request
            val (code, body) = when (request.url().host()) {
                "translate.google.com" -> webResponse
                "clients5.google.com" -> 200 to """[["Hello from the dictionary","fr"]]"""
                else -> error("unexpected host ${request.url()}")
            }
            Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("")
                    .body(ResponseBody.create(MediaType.parse("text/plain"), body))
                    .build()
        }
    }

    private val client = TranslationClient(settings, mockk<StringProvider>(relaxed = true), mockk<NllbTranslator>(relaxed = true), network)

    private fun translate(text: String = "Bonjour") = runBlocking { client.translate(text, TranslationLanguages.AUTO, "en") }

    private fun hosts() = requests.map { it.url().host() }

    @Test
    fun `web endpoint is used when it answers`() {
        translate() shouldBeEqualTo TranslationResult.Success("Hello", "fr")
        hosts() shouldBeEqualTo listOf("translate.google.com")
    }

    @Test
    fun `429 from the web endpoint falls back to the dictionary endpoint`() {
        webResponse = 429 to "<html>Sorry...</html>"
        translate() shouldBeEqualTo TranslationResult.Success("Hello from the dictionary", "fr")
        hosts() shouldBeEqualTo listOf("translate.google.com", "clients5.google.com")
    }

    @Test
    fun `other web failures do not fall back`() {
        webResponse = 500 to ""
        translate() shouldBeInstanceOf TranslationResult.Failure::class
        hosts() shouldBeEqualTo listOf("translate.google.com")
    }

    @Test
    fun `text over the site's limit goes straight to the dictionary endpoint`() {
        translate("a".repeat(GoogleTranslateWire.WEB_MAX_LENGTH + 1)) shouldBeInstanceOf TranslationResult.Success::class
        hosts() shouldBeEqualTo listOf("clients5.google.com")
    }

    @Test
    fun `both endpoints send the browser user agent`() {
        webResponse = 429 to ""
        translate()
        requests.map { it.header("User-Agent").orEmpty().startsWith("Mozilla/5.0") } shouldBeEqualTo listOf(true, true)
    }

    private companion object {
        const val WEB_OK = ")]}'\n\n[[\"wrb.fr\",\"MkEWBc\",\"[[null,null,\\\"fr\\\"],[[[null,null,null,null,null," +
                "[[\\\"Hello\\\",null,null,null,null,null,\\\"Bonjour\\\",1]]]],\\\"en\\\"],\\\"fr\\\"]\",null,null,null,\"generic\"]]"
    }
}
