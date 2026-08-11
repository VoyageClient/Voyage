/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.util

import android.os.Build

/**
 * libjxl declares minSdk 21 and loads its .so from a static initialiser, so every call site must be
 * gated on this and must reach the jxl classes through a separate class the gate guards — a direct
 * reference from a method Dalvik executes would resolve the type on ICS.
 */
object JxlSupport {

    val isAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
}
