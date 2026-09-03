/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.dehydration

internal object DehydratedDeviceConstants {

    const val ALGORITHM_STABLE = "m.dehydration.v2"
    const val ALGORITHM_UNSTABLE = "org.matrix.msc3814.v2"

    const val SECRET_NAME_STABLE = "m.dehydrated_device"
    const val SECRET_NAME_UNSTABLE = "org.matrix.msc3814"

    const val DEFAULT_DISPLAY_NAME = "Dehydrated device"

    /** The endpoints are still unstable, so that is the algorithm servers and other clients expect. */
    const val ALGORITHM_TO_WRITE = ALGORITHM_UNSTABLE

    val SUPPORTED_ALGORITHMS = setOf(ALGORITHM_STABLE, ALGORITHM_UNSTABLE)

    /** Stable first: the key is read from the first name that exists, and written to all of them. */
    val SECRET_NAMES = listOf(SECRET_NAME_STABLE, SECRET_NAME_UNSTABLE)

    const val KEY_LENGTH = 32
}
