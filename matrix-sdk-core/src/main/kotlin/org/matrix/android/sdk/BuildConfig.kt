/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk

/** JVM stand-in for the AGP-generated BuildConfig of the android module. */
object BuildConfig {
    const val DEBUG = false

    @JvmField
    val OKHTTP_LOGGING_LEVEL: okhttp3.logging.HttpLoggingInterceptor.Level = okhttp3.logging.HttpLoggingInterceptor.Level.NONE
    const val LOG_PRIVATE_DATA = false
    const val SDK_VERSION = "jvm-core"
    const val GIT_SDK_REVISION = ""
}
