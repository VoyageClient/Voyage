/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FdDiagnosticsTest {

    @Test
    fun `parses the soft open-files limit`() {
        val limits = """
            Limit                     Soft Limit           Hard Limit           Units
            Max processes             23456                23456                processes
            Max open files            32768                65536                files
            Max locked memory         65536                65536                bytes
        """.trimIndent()
        assertEquals(32768, FdDiagnostics.parseSoftLimit(limits))
        assertNull(FdDiagnostics.parseSoftLimit("Max processes 10 10 processes"))
    }

    @Test
    fun `groups targets by kind`() {
        assertEquals("socket", FdDiagnostics.kindOf("socket:[123456]"))
        assertEquals("pipe", FdDiagnostics.kindOf("pipe:[42]"))
        assertEquals("anon_inode:[eventfd]", FdDiagnostics.kindOf("anon_inode:[eventfd]"))
        assertEquals("/dev/binder", FdDiagnostics.kindOf("/dev/binder"))
        assertEquals("/data/user/0/app/cache/media/*", FdDiagnostics.kindOf("/data/user/0/app/cache/media/abc.jpg (deleted)"))
    }

    @Test
    fun `formats count, limit and largest kinds first`() {
        val targets = List(3) { "socket:[$it]" } + List(2) { "pipe:[$it]" } + "/dev/null"
        assertEquals(
                "Open file descriptors: 6 (limit 1024)\n  socket: 3\n  pipe: 2\n  /dev/null: 1\n",
                FdDiagnostics.format(6, 1024, targets)
        )
        assertEquals("Open file descriptors: unavailable (limit unknown)\n", FdDiagnostics.format(null, null, null))
    }

    @Test
    fun `folds kinds beyond the cap into other`() {
        val targets = (1..20).map { "/data/dir$it/file" }
        val report = FdDiagnostics.format(20, null, targets)
        assertTrue(report.endsWith("  other: 5\n"))
    }

    @Test
    fun `recognizes the masked window-add NPE`() {
        val masked = NullPointerException().apply {
            stackTrace = arrayOf(
                    StackTraceElement("android.view.ViewRootImpl", "dispatchDetachedFromWindow", "ViewRootImpl.java", 1),
                    StackTraceElement("android.view.ViewRootImpl", "doDie", "ViewRootImpl.java", 2),
                    StackTraceElement("android.view.WindowManagerGlobal", "addView", "WindowManagerGlobal.java", 3),
            )
        }
        assertTrue(FdDiagnostics.isMaskedWindowAddFailure(masked))
        assertFalse(FdDiagnostics.isMaskedWindowAddFailure(IllegalStateException().apply { stackTrace = masked.stackTrace }))
        assertFalse(FdDiagnostics.isMaskedWindowAddFailure(NullPointerException()))
    }
}
