/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli.di

import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.internal.di.MatrixScope
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.SessionComponentFactory
import javax.inject.Inject
import javax.inject.Provider

@MatrixScope
internal class DesktopSessionComponentFactory @Inject constructor(
        private val matrixComponent: Provider<DesktopMatrixComponent>,
) : SessionComponentFactory {

    override fun create(sessionParams: SessionParams): SessionComponent {
        return DaggerDesktopSessionComponent
                .factory()
                .create(matrixComponent.get(), sessionParams)
    }
}
