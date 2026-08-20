/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk

// The core jar leaves BuildConfig out (it is AGP-generated on android), so the consumer supplies it.
object BuildConfig {
    const val DEBUG = false
    const val LOG_PRIVATE_DATA = false
    const val SDK_VERSION = "matrix-cli"
    const val GIT_SDK_REVISION = ""

    @JvmField
    val OKHTTP_LOGGING_LEVEL: okhttp3.logging.HttpLoggingInterceptor.Level = okhttp3.logging.HttpLoggingInterceptor.Level.NONE
}
