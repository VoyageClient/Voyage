/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.error

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.failure.Failure

class UnexpectedServerPageTest {

    @Test
    fun `non-Matrix error page is unexpected`() {
        val failure = Failure.OtherServerError("<html>Sorry, you have been blocked</html>", 403, "https://example.org/register")

        failure.asUnexpectedServerPage() shouldBeEqualTo failure
    }

    @Test
    fun `404 keeps meaning not a homeserver`() {
        Failure.OtherServerError("<html>Not Found</html>", 404).asUnexpectedServerPage().shouldBeNull()
    }

    @Test
    fun `empty body has nothing to show`() {
        Failure.OtherServerError(" ", 502).asUnexpectedServerPage().shouldBeNull()
    }

    @Test
    fun `other failures are not pages`() {
        Failure.NetworkConnection().asUnexpectedServerPage().shouldBeNull()
    }
}
