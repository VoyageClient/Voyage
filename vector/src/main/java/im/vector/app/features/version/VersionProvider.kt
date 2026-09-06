/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.version

import im.vector.app.core.resources.BuildMeta
import javax.inject.Inject
import org.matrix.android.sdk.BuildConfig as MatrixSdkBuildConfig

class VersionProvider @Inject constructor(
        private val buildMeta: BuildMeta,
) {

    fun getVersion(): String {
        val result = "${MatrixSdkBuildConfig.SDK_VERSION} [${buildMeta.versionName}]"

        val details = listOfNotNull(
                buildMeta.flavorShortDescription.takeIf { it.isNotBlank() },
                buildMeta.gitRevision.takeIf { it.isNotBlank() },
        )

        return if (details.isEmpty()) result else result + details.joinToString("-", prefix = " (", postfix = ")")
    }
}
