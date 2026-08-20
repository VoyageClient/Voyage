/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.send

import android.content.Context
import androidx.work.WorkerParameters
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.session.AndroidSessionComponent
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import javax.inject.Inject

/**
 * Possible previous worker: None.
 * Possible next worker    : None.
 */
internal class RedactEventWorker(context: Context, params: WorkerParameters, sessionManager: SessionManager) :
        SessionSafeCoroutineWorker<RedactEventWorkerParams>(context, params, sessionManager, RedactEventWorkerParams::class.java) {

    @Inject lateinit var redactEventTaskBody: RedactEventTaskBody

    override fun injectWith(injector: AndroidSessionComponent) {
        injector.inject(this)
    }

    override fun body() = redactEventTaskBody
}
