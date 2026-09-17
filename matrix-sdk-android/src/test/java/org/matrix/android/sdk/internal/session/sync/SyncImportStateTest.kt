/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class SyncImportStateTest {

    private val state = SyncImportState()

    @Test
    fun `nothing is importing to begin with`() {
        state.isImporting shouldBeEqualTo false
    }

    @Test
    fun `importing is visible for the duration of the block`() = runTest {
        state.importing {
            state.isImporting shouldBeEqualTo true
        }

        state.isImporting shouldBeEqualTo false
    }

    @Test
    fun `the flag clears even when the import fails`() = runTest {
        runCatching { state.importing { error("import blew up") } }

        state.isImporting shouldBeEqualTo false
    }

    /** Nested imports must not clear the flag when the inner one returns. */
    @Test
    fun `nesting keeps the flag until the outermost import ends`() = runTest {
        state.importing {
            state.importing { state.isImporting shouldBeEqualTo true }
            state.isImporting shouldBeEqualTo true
        }

        state.isImporting shouldBeEqualTo false
    }

    @Test
    fun `the block's value is passed through`() = runTest {
        state.importing { 42 } shouldBeEqualTo 42
    }
}
