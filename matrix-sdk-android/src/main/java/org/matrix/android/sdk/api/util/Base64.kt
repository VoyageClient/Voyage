/*
 * Copyright (c) 2022 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.api.util

import timber.log.Timber
import java.util.Base64

// java.util.Base64 (desugared below API 26). The MIME decoder matches the leniency of the previous
// android.util.Base64 DEFAULT decoding: it skips line breaks and accepts missing padding.

fun ByteArray.toBase64NoPadding(): String {
    return Base64.getEncoder().withoutPadding().encodeToString(this)
}

fun String.fromBase64(): ByteArray {
    return Base64.getMimeDecoder().decode(this)
}

/**
 * Decode the base 64. Return null in case of bad format. Should be used when parsing received data from external source
 */
internal fun String.fromBase64Safe(): ByteArray? {
    return try {
        Base64.getMimeDecoder().decode(this)
    } catch (throwable: Throwable) {
        Timber.e(throwable, "Unable to decode base64 string")
        null
    }
}
