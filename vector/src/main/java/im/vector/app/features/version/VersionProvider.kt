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

    fun getVersion(): String {
        // gitRevisionDate is git's %ci ("2026-08-30 21:03:11 +0200"); the date alone reads as a build stamp.
        val buildStamp = buildMeta.gitRevisionDate.take(10).filter { it.isDigit() }
                .takeIf { it.length == 8 }
                ?: versionCodeProvider.getVersionCode().toString()
        val result = "${buildMeta.versionName} [$buildStamp]"

        val details = listOfNotNull(
                buildMeta.flavorShortDescription.takeIf { it.isNotBlank() },
                buildMeta.gitRevision.takeIf { it.isNotBlank() },
        )

        return if (details.isEmpty()) result else result + details.joinToString("-", prefix = " (", postfix = ")")
    }
}
