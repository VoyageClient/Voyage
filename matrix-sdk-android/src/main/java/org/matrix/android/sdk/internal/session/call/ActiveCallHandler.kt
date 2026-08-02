/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

@SessionScope
internal class ActiveCallHandler @Inject constructor() {

    // update {} publishes a new list each time so the StateFlow always emits (an in-place mutation
    // of the same reference would not).
    private val activeCalls = MutableStateFlow<List<MxCall>>(emptyList())

    fun addCall(call: MxCall) {
        activeCalls.update { it + call }
    }

    fun removeCall(callId: String) {
        activeCalls.update { calls -> calls.filterNot { it.callId == callId } }
    }

    fun getCallWithId(callId: String): MxCall? {
        return activeCalls.value.find { it.callId == callId }
    }

    fun getActiveCallsFlow(): StateFlow<List<MxCall>> = activeCalls
}
