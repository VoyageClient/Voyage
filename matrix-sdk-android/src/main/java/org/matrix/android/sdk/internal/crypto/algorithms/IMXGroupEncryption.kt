/*
 * Copyright (c) 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.crypto.algorithms

internal interface IMXGroupEncryption {

    /**
     * In Megolm, each recipient maintains a record of the ratchet value which allows
     * them to decrypt any messages sent in the session after the corresponding point
     * in the conversation. If this value is compromised, an attacker can similarly
     * decrypt past messages which were encrypted by a key derived from the
     * compromised or subsequent ratchet values. This gives 'partial' forward
     * secrecy.
     *
     * To mitigate this issue, the application should offer the user the option to
     * discard historical conversations, by winding forward any stored ratchet values,
     * or discarding sessions altogether.
     */
    suspend fun discardSessionKey()

    suspend fun preshareKey(userIds: List<String>)
}
