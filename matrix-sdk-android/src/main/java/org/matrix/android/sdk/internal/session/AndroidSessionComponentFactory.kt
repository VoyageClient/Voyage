/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session

import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.internal.di.MatrixComponent
import org.matrix.android.sdk.internal.di.MatrixScope
import javax.inject.Inject

@MatrixScope
internal class AndroidSessionComponentFactory @Inject constructor(
        private val matrixComponent: MatrixComponent,
) : SessionComponentFactory {

    override fun create(sessionParams: SessionParams): AndroidSessionComponent {
        return DaggerAndroidSessionComponent
                .factory()
                .create(matrixComponent, sessionParams)
    }
}
