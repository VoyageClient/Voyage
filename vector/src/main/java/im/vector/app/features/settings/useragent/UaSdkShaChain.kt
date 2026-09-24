/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent

/** The lookup path from an app release to the matrix-rust-sdk short sha it shipped with. */
data class UaSdkShaChain(val appRepo: String, val tag: String, val componentsRepo: String, val releasePrefix: String)

/** Only Element X Android and SchildiChat Next carry a rust-sdk sha; the rest have no chain. */
fun sdkShaChainFor(client: UaSpoofClient, appVersion: String): UaSdkShaChain? = when (client) {
    UaSpoofClient.ELEMENT_X_ANDROID ->
        UaSdkShaChain("element-hq/element-x-android", "v$appVersion", "matrix-org/matrix-rust-components-kotlin", "sdk-v")
    UaSpoofClient.SCHILDICHAT_NEXT ->
        UaSdkShaChain("SchildiChat/schildichat-android-next", "sc_v$appVersion", "SchildiChat/matrix-rust-components-kotlin", "sc-sdk-v")
    else -> null
}
