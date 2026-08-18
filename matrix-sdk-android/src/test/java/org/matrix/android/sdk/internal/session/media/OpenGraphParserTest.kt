/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldEndWith
import org.amshove.kluent.shouldNotContain
import org.jsoup.Jsoup
import org.junit.Test

private const val BASE_URL = "https://example.org/page"

/**
 * The cases Synapse's own `tests/test_preview.py` covers, so that a preview we generate on the device
 * matches what the homeserver would have returned.
 */
class OpenGraphParserTest {

    private fun parse(html: String) = OpenGraphParser.parse(Jsoup.parse(html, BASE_URL))

    @Test
    fun `open graph tags are read`() {
        val og = parse(
                """
                <html><head>
                <meta property="og:title" content="Foo" />
                <meta property="og:description" content="Some text." />
                <meta property="og:site_name" content="Example" />
                <meta property="og:image" content="https://example.org/image.png" />
                </head><body></body></html>
                """
        )

        og["og:title"] shouldBeEqualTo "Foo"
        og["og:description"] shouldBeEqualTo "Some text."
        og["og:site_name"] shouldBeEqualTo "Example"
        og["og:image"] shouldBeEqualTo "https://example.org/image.png"
    }

    @Test
    fun `article and profile tags are read alongside open graph ones`() {
        val og = parse(
                """
                <html><head>
                <meta property="og:title" content="Foo" />
                <meta property="article:published_time" content="2026-01-01" />
                <meta property="profile:username" content="alice" />
                </head><body></body></html>
                """
        )

        og["article:published_time"] shouldBeEqualTo "2026-01-01"
        og["profile:username"] shouldBeEqualTo "alice"
    }

    @Test
    fun `twitter card tags fill the gaps of open graph ones`() {
        val og = parse(
                """
                <html><head>
                <meta property="og:title" content="From open graph" />
                <meta name="twitter:title" content="From twitter" />
                <meta name="twitter:description" content="A description." />
                <meta name="twitter:site" content="@example" />
                <meta name="twitter:card" content="summary" />
                <meta name="twitter:creator" content="@alice" />
                </head><body></body></html>
                """
        )

        og["og:title"] shouldBeEqualTo "From open graph"
        og["og:description"] shouldBeEqualTo "A description."
        og["og:site_name"] shouldBeEqualTo "@example"
        og.keys shouldNotContain "og:card"
        og.keys shouldNotContain "og:creator"
    }

    @Test
    fun `a page which declares an absurd number of tags is not previewed`() {
        val tags = (1..60).joinToString("\n") { """<meta property="og:tag$it" content="value" />""" }

        parse("<html><head>$tags</head><body></body></html>").shouldBeEmptyPreview()
    }

    @Test
    fun `an empty tag is ignored`() {
        val og = parse("""<html><head><title>Fallback</title><meta property="og:title" content="" /></head><body></body></html>""")

        og["og:title"] shouldBeEqualTo "Fallback"
    }

    @Test
    fun `the title falls back to the title tag, then to a heading`() {
        parse("<html><head><title>The title</title></head><body><h1>A heading</h1></body></html>")["og:title"] shouldBeEqualTo "The title"
        parse("<html><head></head><body><h1>A heading</h1></body></html>")["og:title"] shouldBeEqualTo "A heading"
        parse("<html><head></head><body><h3>Deep heading</h3></body></html>")["og:title"] shouldBeEqualTo "Deep heading"
    }

    @Test
    fun `the description falls back to the meta description`() {
        val og = parse(
                """
                <html><head><meta name="Description" content="Meta description." /></head>
                <body><p>Body text.</p></body></html>
                """
        )

        og["og:description"] shouldBeEqualTo "Meta description."
    }

    @Test
    fun `the description falls back to the text of the page`() {
        val og = parse("<html><head><title>Foo</title></head><body><p>Simple body text.</p></body></html>")

        og["og:description"] shouldBeEqualTo "Simple body text."
    }

    @Test
    fun `the parts of a page which are not its text are left out of the description`() {
        val og = parse(
                """
                <html><head><title>Foo</title></head><body>
                <nav>Navigation</nav>
                <script>console.log("script")</script>
                <style>body { color: red }</style>
                <footer>Footer</footer>
                <div role="menu">Menu</div>
                <p>The actual text.</p>
                </body></html>
                """
        )

        og["og:description"] shouldBeEqualTo "The actual text."
    }

    @Test
    fun `a long description is truncated on a word boundary`() {
        val og = parse("<html><head><title>Foo</title></head><body><p>${"word ".repeat(300)}</p></body></html>")

        val description = og["og:description"]!!
        (description.length <= 501) shouldBeEqualTo true
        description shouldEndWith "…"
        description shouldContain "word word"
    }

    @Test
    fun `an image is looked for when the page declares none`() {
        val og = parse(
                """
                <html><head><title>Foo</title></head><body>
                <img src="/small.png" width="8" height="8" />
                <img src="/big.png" width="800" height="600" />
                <img src="/medium.png" width="100" height="100" />
                </body></html>
                """
        )

        og["og:image"] shouldBeEqualTo "https://example.org/big.png"
    }

    @Test
    fun `an image marked up as the page's own wins`() {
        val og = parse(
                """
                <html><head><title>Foo</title><meta itemprop="image" content="/itemprop.png" /></head>
                <body><img src="/big.png" width="800" height="600" /></body></html>
                """
        )

        og["og:image"] shouldBeEqualTo "https://example.org/itemprop.png"
    }

    @Test
    fun `any image will do when none declares its size`() {
        val og = parse("""<html><head><title>Foo</title></head><body><img src="/only.png" /></body></html>""")

        og["og:image"] shouldBeEqualTo "https://example.org/only.png"
    }

    @Test
    fun `the favicon is the last resort`() {
        val og = parse("""<html><head><title>Foo</title><link rel="shortcut icon" href="/favicon.ico" /></head><body></body></html>""")

        og["og:image"] shouldBeEqualTo "https://example.org/favicon.ico"
    }

    @Test
    fun `a relative image url is resolved against the page`() {
        val og = parse("""<html><head><meta property="og:image" content="../images/pic.png" /></head><body></body></html>""")

        og["og:image"] shouldBeEqualTo "https://example.org/images/pic.png"
    }

    @Test
    fun `an overlong value is pruned`() {
        val og = parse("""<html><head><meta property="og:title" content="${"a".repeat(1001)}" /></head><body></body></html>""")

        og["og:title"].shouldBeNull()
    }

    @Test
    fun `a page with nothing to say is not previewed`() {
        parse("<html><head></head><body></body></html>").shouldBeEmptyPreview()
    }

    @Test
    fun `html entities are decoded`() {
        val og = parse("""<html><head><meta property="og:title" content="Ben &amp; Jerry&#39;s" /></head><body></body></html>""")

        og["og:title"] shouldBeEqualTo "Ben & Jerry's"
    }

    private fun Map<String, String>.shouldBeEmptyPreview() {
        keys.filterNot { it == "og:url" } shouldBeEqualTo emptyList()
    }
}
