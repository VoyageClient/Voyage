/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.debug.di

import android.content.Context
import android.content.Intent
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import im.vector.app.core.debug.DebugNavigator
import im.vector.app.core.debug.DebugReceiver
import im.vector.app.core.debug.LeakDetector
import im.vector.app.features.debug.DebugMenuActivity
import im.vector.app.leakcanary.LeakCanaryLeakDetector
import im.vector.app.receivers.VectorDebugReceiver
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
abstract class DebugModule {

    companion object {

        @Provides
        fun providesDebugNavigator() = object : DebugNavigator {
            override fun openDebugMenu(context: Context) {
                context.startActivity(Intent(context, DebugMenuActivity::class.java))
            }
        }
    }

    @Binds
    abstract fun bindsDebugReceiver(receiver: VectorDebugReceiver): DebugReceiver

    // Must be a singleton: LeakCanaryLeakDetector tracks whether AppWatcher was installed in an
    // instance field, and disabling early-returns when it thinks it never installed. A per-injection
    // instance means the toggle in the debug menu never reaches AppWatcher and analysis stays on.
    @Binds
    @Singleton
    abstract fun bindsLeakDetector(leakDetector: LeakCanaryLeakDetector): LeakDetector
}
