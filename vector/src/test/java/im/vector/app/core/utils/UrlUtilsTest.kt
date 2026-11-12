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
    fun `MSC links must match the displayed proposal and exact destination`() {
        val proposalUrl = "https://github.com/matrix-org/matrix-spec-proposals/pull/1234"
        proposalUrl.isExpectedMscLink("MSC1234") shouldBeEqualTo true
        proposalUrl.isExpectedMscLink("msc1234") shouldBeEqualTo true
        proposalUrl.isExpectedMscLink("MSC4321") shouldBeEqualTo false
        proposalUrl.isExpectedMscLink("Read MSC1234") shouldBeEqualTo false
        proposalUrl.isExpectedMscLink("MSC1234 ") shouldBeEqualTo false
        "https://github.com/matrix-org/matrix-spec-proposals/pull/123456".isExpectedMscLink("MSC123456") shouldBeEqualTo true
        "https://github.com/matrix-org/matrix-spec-proposals/pull/1234567".isExpectedMscLink("MSC1234567") shouldBeEqualTo false
        listOf(
                "http://github.com/matrix-org/matrix-spec-proposals/pull/1234",
                "https://github.com.evil.org/matrix-org/matrix-spec-proposals/pull/1234",
                "https://github.com@evil.org/matrix-org/matrix-spec-proposals/pull/1234",
                "https://github.com/other/matrix-spec-proposals/pull/1234",
                "$proposalUrl?redirect=evil.org",
                "$proposalUrl/extra",
                "$proposalUrl\u202E"
        ).forEach { it.isExpectedMscLink("MSC1234") shouldBeEqualTo false }
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
}
