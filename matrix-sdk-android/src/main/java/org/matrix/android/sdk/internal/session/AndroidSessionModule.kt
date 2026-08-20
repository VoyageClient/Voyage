/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session

import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import org.matrix.android.sdk.api.session.SessionLifecycleObserver
import org.matrix.android.sdk.api.session.events.EventService
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.WorkManagerTaskScheduler
import org.matrix.android.sdk.internal.session.call.CallEventProcessor
import org.matrix.android.sdk.internal.session.events.DefaultEventService
import org.matrix.android.sdk.internal.session.identity.DefaultIdentityService
import org.matrix.android.sdk.internal.session.integrationmanager.IntegrationManager
import org.matrix.android.sdk.internal.session.widgets.DefaultWidgetURLFormatter
import org.matrix.android.sdk.internal.session.workmanager.DefaultWorkManagerConfig
import org.matrix.android.sdk.internal.session.workmanager.WorkManagerConfig

/**
 * The parts of a session graph that are android-only: WorkManager running the background tasks, and
 * the observers and processors whose impls still need androidx lifecycle or the call stack.
 */
@Module
internal abstract class AndroidSessionModule {

    @Binds
    abstract fun bindBackgroundTaskScheduler(scheduler: WorkManagerTaskScheduler): BackgroundTaskScheduler

    @Binds
    abstract fun bindWorkManagerConfig(config: DefaultWorkManagerConfig): WorkManagerConfig

    @Binds
    @IntoSet
    abstract fun bindCallEventProcessor(processor: CallEventProcessor): EventInsertLiveProcessor

    @Binds
    @IntoSet
    abstract fun bindIntegrationManager(manager: IntegrationManager): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindWidgetUrlFormatter(formatter: DefaultWidgetURLFormatter): SessionLifecycleObserver

    @Binds
    @IntoSet
    abstract fun bindIdentityService(service: DefaultIdentityService): SessionLifecycleObserver

    @Binds
    abstract fun bindEventService(service: DefaultEventService): EventService
}
