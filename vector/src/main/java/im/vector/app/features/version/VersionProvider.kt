/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.version

import im.vector.app.core.resources.BuildMeta
import im.vector.app.core.resources.VersionCodeProvider
import javax.inject.Inject

class VersionProvider @Inject constructor(
        private val versionCodeProvider: VersionCodeProvider,
        private val buildMeta: BuildMeta,
) {

    fun getVersion(longFormat: Boolean): String {
        val result = "${buildMeta.versionName} [${versionCodeProvider.getVersionCode()}]"

        val details = listOfNotNull(
                buildMeta.flavorShortDescription.takeIf { it.isNotBlank() },
                buildMeta.gitRevision.takeIf { it.isNotBlank() },
                buildMeta.gitRevisionDate.takeIf { longFormat && it.isNotBlank() },
        )

        return if (details.isEmpty()) result else result + details.joinToString("-", prefix = " (", postfix = ")")
    }
}
