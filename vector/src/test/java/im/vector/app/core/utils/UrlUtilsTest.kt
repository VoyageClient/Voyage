/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun `MSC labels open the configured site when the link points at the same proposal`() {
        listOf(
                "${GITHUB}1234",
                "http://github.com/matrix-org/matrix-spec-proposals/pull/1234/",
                "${GITHUB}1234?redirect=evil.org",
                "https://msc.re/1234",
                "${SABLE}1234#section",
        ).forEach { url ->
            mscLinkTarget(url, "MSC1234", SABLE) shouldBeEqualTo "${SABLE}1234"
            mscLinkTarget(url, "msc1234", GITHUB) shouldBeEqualTo "${GITHUB}1234"
        }
        mscLinkTarget("${GITHUB}123456", "MSC123456", GITHUB) shouldBeEqualTo "${GITHUB}123456"
    }

    @Test
    fun `MSC labels need the usual checks when the link goes anywhere else`() {
        listOf(
                "${GITHUB}4321",
                "https://msc.re/12345",
                "https://example.org/1234",
                "https://github.com.evil.org/matrix-org/matrix-spec-proposals/pull/1234",
                "https://github.com@evil.org/matrix-org/matrix-spec-proposals/pull/1234",
                "https://msc.re@evil.org/1234",
                "https://msc.re.evil.org/1234",
                "https://github.com/other/matrix-spec-proposals/pull/1234",
                "${GITHUB}1234/extra",
                "${GITHUB}1234" + Char(0x202E),
        ).forEach { mscLinkTarget(it, "MSC1234", GITHUB) shouldBeEqualTo null }
        listOf("Read MSC1234", "MSC1234 ", "MSC 1234", "MSC1234567").forEach { label ->
            mscLinkTarget("${GITHUB}1234", label, GITHUB) shouldBeEqualTo null
        }
    }

    @Test
    fun `bare msc_re links skip the redirect to GitHub`() {
        mscLinkTarget("https://msc.re/1234", "https://msc.re/1234", SABLE) shouldBeEqualTo "${GITHUB}1234"
        mscLinkTarget("http://msc.re/1234", "msc.re/1234", SABLE) shouldBeEqualTo "${GITHUB}1234"
    }

    @Test
    fun `bare sable and GitHub links open where they point`() {
        mscLinkTarget("${SABLE}1234", "${SABLE}1234", GITHUB) shouldBeEqualTo "${SABLE}1234"
        mscLinkTarget("${GITHUB}1234", "${GITHUB}1234", SABLE) shouldBeEqualTo "${GITHUB}1234"
    }

    @Test
    fun `proposal URL labels need the usual checks when the link differs`() {
        mscLinkTarget("${SABLE}1234", "https://msc.re/1234", GITHUB) shouldBeEqualTo null
        mscLinkTarget("https://msc.re/4321", "https://msc.re/1234", GITHUB) shouldBeEqualTo null
        mscLinkTarget("https://example.org", "https://msc.re/1234", GITHUB) shouldBeEqualTo null
        mscLinkTarget("https://msc.re/1234", "click here", GITHUB) shouldBeEqualTo null
    }

    @Test
    fun `GitHub shorthand labels are expected for any github_com destination`() {
        val repoUrl = "https://github.com/matrix-org/matrix-spec-proposals"
        "$repoUrl/pull/1234".isExpectedGitHubLink("matrix-org/matrix-spec-proposals#1234") shouldBeEqualTo true
        repoUrl.isExpectedGitHubLink("matrix-org/matrix-spec-proposals") shouldBeEqualTo true
        "https://github.com/matrix-org/other/issues/1".isExpectedGitHubLink("matrix-org/matrix-spec-proposals#1234") shouldBeEqualTo true
        repoUrl.isExpectedGitHubLink("see matrix-org/matrix-spec-proposals") shouldBeEqualTo false
        repoUrl.isExpectedGitHubLink("click here") shouldBeEqualTo false
        listOf(
                "https://github.com.evil.org/matrix-org/matrix-spec-proposals",
                "https://github.com@evil.org/matrix-org/matrix-spec-proposals",
                "https://evil.org/github.com/matrix-org/matrix-spec-proposals",
                "ftp://github.com/matrix-org/matrix-spec-proposals",
        ).forEach { it.isExpectedGitHubLink("matrix-org/matrix-spec-proposals") shouldBeEqualTo false }
    }

    @Test
    fun `given a web url, when checking it is tappable, then it is`() {
        "https://matrix.to/#/@alice:example.org".isTappableLink() shouldBeEqualTo true
        "https://example.org".isTappableLink() shouldBeEqualTo true
    }

    @Test
    fun `given a matrix uri, when checking it is tappable, then it is`() {
        // Opaque, so not a URL: the click handling must not key off isValidUrl for these.
        "matrix:u/alice:example.org".isValidUrl() shouldBeEqualTo false
        "matrix:u/alice:example.org".isTappableLink() shouldBeEqualTo true
        "MATRIX:r/room:example.org/e/\$abcdef".isTappableLink() shouldBeEqualTo true
    }

    @Test
    fun `given plain text, when checking it is tappable, then it is not`() {
        "not a link".isTappableLink() shouldBeEqualTo false
        "".isTappableLink() shouldBeEqualTo false
    }

    private companion object {
        const val GITHUB = "https://github.com/matrix-org/matrix-spec-proposals/pull/"
        const val SABLE = "https://msc.sable.moe/"
    }
}
