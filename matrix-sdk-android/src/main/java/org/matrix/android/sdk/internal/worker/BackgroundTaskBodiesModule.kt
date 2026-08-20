/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.worker

import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.multibindings.IntoMap
import org.matrix.android.sdk.internal.crypto.crosssigning.UpdateTrustTaskBody
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.session.pushers.AddPusherTaskBody
import org.matrix.android.sdk.internal.session.room.aggregation.livelocation.DeactivateLiveLocationShareTaskBody
import org.matrix.android.sdk.internal.session.room.send.MultipleEventSendingDispatcherTaskBody
import org.matrix.android.sdk.internal.session.room.send.SendEventTaskBody
import org.matrix.android.sdk.internal.session.sync.handler.UpdateUserTaskBody

@MapKey
internal annotation class BackgroundTaskKey(val value: BackgroundTaskType)

/**
 * The task bodies a [org.matrix.android.sdk.internal.platform.CoroutineBackgroundTaskScheduler] can
 * run. Android does not install this module — its workers inject their body directly — and the
 * upload and sync bodies are missing here because their dependencies are still android-only.
 */
@Module
internal abstract class BackgroundTaskBodiesModule {

    @Binds
    @IntoMap
    @BackgroundTaskKey(BackgroundTaskType.ADD_PUSHER)
    abstract fun bindAddPusher(body: AddPusherTaskBody): BackgroundTaskBody<*>

    @Binds
    @IntoMap
    @BackgroundTaskKey(BackgroundTaskType.UPDATE_TRUST)
    abstract fun bindUpdateTrust(body: UpdateTrustTaskBody): BackgroundTaskBody<*>

    @Binds
    @IntoMap
    @BackgroundTaskKey(BackgroundTaskType.UPDATE_USER)
    abstract fun bindUpdateUser(body: UpdateUserTaskBody): BackgroundTaskBody<*>

    @Binds
    @IntoMap
    @BackgroundTaskKey(BackgroundTaskType.DEACTIVATE_LIVE_LOCATION)
    abstract fun bindDeactivateLiveLocation(body: DeactivateLiveLocationShareTaskBody): BackgroundTaskBody<*>

    @Binds
    @IntoMap
    @BackgroundTaskKey(BackgroundTaskType.SEND_EVENT)
    abstract fun bindSendEvent(body: SendEventTaskBody): BackgroundTaskBody<*>

    @Binds
    @IntoMap
    @BackgroundTaskKey(BackgroundTaskType.MULTIPLE_EVENT_DISPATCHER)
    abstract fun bindMultipleEventDispatcher(body: MultipleEventSendingDispatcherTaskBody): BackgroundTaskBody<*>
}
