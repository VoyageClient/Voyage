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
package org.matrix.android.sdk.internal.network

/** Platform seam: Android reads app/device info from Context+Build (see AndroidComputeUserAgentUseCase). */
interface ComputeUserAgentUseCase {

    /**
     * Create an user agent with the application version.
     * Ex: Element/1.5.0 (Xiaomi Mi 9T; Android 11; RKQ1.200826.002; Flavour GooglePlay; MatrixAndroidSdk2 1.5.0)
     *
     * @param flavorDescription the flavor description
     */
    fun execute(flavorDescription: String): String

    companion object {
        const val FALLBACK_APP_VERSION = "0.0.0"
    }
}
