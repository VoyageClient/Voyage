/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.util

import android.content.res.AssetFileDescriptor
import android.database.Cursor

// Cursor implements java.io.Closeable only on API 16+, AssetFileDescriptor on 19+; below that
// kotlin.io.use's cast to Closeable throws ClassCastException. These overloads call close() directly
// so the existing `x.use { }` call sites keep working down to API 14.
inline fun <R> Cursor.use(block: (Cursor) -> R): R = try { block(this) } finally { close() }

inline fun <R> AssetFileDescriptor.use(block: (AssetFileDescriptor) -> R): R = try { block(this) } finally { close() }
