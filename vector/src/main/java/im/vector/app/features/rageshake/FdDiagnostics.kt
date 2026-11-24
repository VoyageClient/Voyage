/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File

object FdDiagnostics {
    private const val MAX_KINDS = 15
    private val INODE_SUFFIX = Regex(":\\[\\d+]$")
    private val SOFT_LIMIT = Regex("^Max open files\\s+(\\d+)", RegexOption.MULTILINE)

    // Listing /proc/self/fd and writing the crash file each need a free descriptor; at EMFILE there is none.
    @Volatile private var reserve: ParcelFileDescriptor? = null

    fun reserveDescriptor() {
        if (reserve != null) return
        reserve = runCatching { ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    }

    fun releaseReserve() {
        reserve?.let { runCatching { it.close() } }
        reserve = null
    }

    fun describe(): String {
        val fdDir = File("/proc/self/fd")
        val names = runCatching { fdDir.list() }.getOrNull()
        val limit = runCatching { parseSoftLimit(File("/proc/self/limits").readText()) }.getOrNull()
        // No public readlink before API 21, so older devices get the count without the breakdown.
        val targets = if (names != null && Build.VERSION.SDK_INT >= 21) {
            names.mapNotNull { runCatching { Os.readlink(File(fdDir, it).path) }.getOrNull() }
        } else {
            null
        }
        return format(names?.size, limit, targets)
    }

    internal fun format(count: Int?, limit: Int?, targets: List<String>?): String {
        val b = StringBuilder("Open file descriptors: ")
        b.append(count?.toString() ?: "unavailable")
        b.append(" (limit ").append(limit?.toString() ?: "unknown").append(")\n")
        if (targets.isNullOrEmpty()) return b.toString()
        val kinds = targets.groupingBy(::kindOf).eachCount().entries.sortedByDescending { it.value }
        kinds.take(MAX_KINDS).forEach { b.append("  ").append(it.key).append(": ").append(it.value).append('\n') }
        val rest = kinds.drop(MAX_KINDS).sumOf { it.value }
        if (rest > 0) b.append("  other: ").append(rest).append('\n')
        return b.toString()
    }

    internal fun kindOf(target: String): String {
        val t = target.removeSuffix(" (deleted)").replace(INODE_SUFFIX, "")
        if (!t.startsWith("/") || t.startsWith("/dev/")) return t
        return t.substringBeforeLast('/', "") + "/*"
    }

    internal fun parseSoftLimit(limits: String): Int? = SOFT_LIMIT.find(limits)?.groupValues?.get(1)?.toIntOrNull()

    // addView's cleanup after a failed setView() NPEs on the unbuilt input pipeline, replacing the original exception.
    internal fun isMaskedWindowAddFailure(throwable: Throwable): Boolean {
        if (throwable !is NullPointerException) return false
        val frames = throwable.stackTrace
        return frames.any { it.className == "android.view.ViewRootImpl" && it.methodName == "dispatchDetachedFromWindow" } &&
                frames.any { it.className == "android.view.WindowManagerGlobal" && it.methodName == "addView" }
    }
}
