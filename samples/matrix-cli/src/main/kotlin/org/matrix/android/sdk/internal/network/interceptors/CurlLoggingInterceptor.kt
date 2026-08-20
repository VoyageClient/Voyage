/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network.interceptors

import okhttp3.Interceptor
import okhttp3.Response
import org.matrix.android.sdk.internal.di.MatrixScope
import javax.inject.Inject

// The core jar leaves this out (it is per-variant on android), so the desktop consumer supplies it.
@MatrixScope
internal class CurlLoggingInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
