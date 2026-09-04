/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.profile.ProfileKeys
import org.matrix.android.sdk.api.session.profile.UserBio

class ProfileFieldParsingTest {

    @Test
    fun `bio reads the Commet formatted body`() {
        val html = "A GNU/Linux user btw<br/>Mastodon: https://furry.engineer/@azukuni"
        val bio = mapOf<String, Any>(
                ProfileKeys.BIOGRAPHY_COMMET to mapOf(
                        "format" to "org.matrix.custom.html",
                        "formatted_body" to html,
                )
        ).profileBio()
        bio shouldBeEqualTo UserBio(body = html, formattedBody = html)
    }

    @Test
    fun `bio keeps a Commet plain body alongside the formatted one`() {
        val bio = mapOf<String, Any>(
                ProfileKeys.BIOGRAPHY_COMMET to mapOf(
                        "body" to "plain",
                        "format" to "org.matrix.custom.html",
                        "formatted_body" to "<b>plain</b>",
                )
        ).profileBio()
        bio shouldBeEqualTo UserBio(body = "plain", formattedBody = "<b>plain</b>")
    }

    @Test
    fun `bio ignores a formatted body under an unknown format`() {
        mapOf<String, Any>(
                ProfileKeys.BIOGRAPHY_COMMET to mapOf("format" to "text/markdown", "formatted_body" to "**hi**")
        ).profileBio().shouldBeNull()
    }

    @Test
    fun `bio prefers the MSC4440 field over the Commet one`() {
        val bio = mapOf<String, Any>(
                ProfileKeys.BIOGRAPHY to mapOf("m.text" to listOf(mapOf("body" to "extensible"))),
                ProfileKeys.BIOGRAPHY_COMMET to mapOf("body" to "commet"),
        ).profileBio()
        bio shouldBeEqualTo UserBio(body = "extensible")
    }

    @Test
    fun `banner reads the Commet key`() {
        val mxc = "mxc://pawb.social/ZghoIUuuZpCfxUXWsjOiaTYu"
        mapOf<String, Any>(ProfileKeys.BANNER_URL_UNSTABLE to mxc).profileBannerUrl() shouldBeEqualTo mxc
    }

    @Test
    fun `color reads the Sable and Commet fallbacks`() {
        mapOf<String, Any>(ProfileKeys.COLOR_SABLE_ON_DARK to "#ff00ff").profileColorPreference()?.forTheme(light = false) shouldBeEqualTo "#FF00FF"
        mapOf<String, Any>(ProfileKeys.COLOR_COMMET to mapOf("color" to "#ff00ff")).profileColorPreference()?.forTheme(light = true) shouldBeEqualTo "#FF00FF"
    }
    @Test
    fun `bio falls back to the Sable string`() {
        mapOf<String, Any>(ProfileKeys.BIOGRAPHY_SABLE to "Hewwo :3").profileBio() shouldBeEqualTo UserBio(body = "Hewwo :3")
    }

    @Test
    fun `bio prefers the Sable string over the Commet object`() {
        val bio = mapOf<String, Any>(
                ProfileKeys.BIOGRAPHY_COMMET to mapOf("format" to "org.matrix.custom.html", "formatted_body" to "<b>Hewwo</b>"),
                ProfileKeys.BIOGRAPHY_SABLE to "Hewwo :3",
        ).profileBio()
        bio shouldBeEqualTo UserBio(body = "Hewwo :3")
    }

    @Test
    fun `bio falls back to Commet when the Sable string is blank`() {
        val bio = mapOf<String, Any>(
                ProfileKeys.BIOGRAPHY_SABLE to " ",
                ProfileKeys.BIOGRAPHY_COMMET to mapOf("format" to "org.matrix.custom.html", "formatted_body" to "<b>Hewwo</b>"),
        ).profileBio()
        bio shouldBeEqualTo UserBio(body = "<b>Hewwo</b>", formattedBody = "<b>Hewwo</b>")
    }

    @Test
    fun `color ignores a Commet scheme with a null color`() {
        mapOf<String, Any>(
                ProfileKeys.COLOR_COMMET to mapOf("color" to null, "brightness" to null)
        ).profileColorPreference().shouldBeNull()
    }
}
