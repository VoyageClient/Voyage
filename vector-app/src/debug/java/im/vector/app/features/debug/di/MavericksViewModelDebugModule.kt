/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.debug.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.multibindings.IntoMap
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.MavericksViewModelKey
import im.vector.app.core.di.MiscMavericksViewModelComponent
import im.vector.app.features.debug.leak.DebugMemoryLeaksViewModel
import im.vector.app.features.debug.settings.DebugPrivateSettingsViewModel

@InstallIn(MiscMavericksViewModelComponent::class)
@Module
interface MavericksViewModelDebugModule {

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.debug.settings.DebugPrivateSettingsViewModel")
    fun debugPrivateSettingsViewModelFactory(factory: DebugPrivateSettingsViewModel.Factory): MavericksAssistedViewModelFactory<*, *>

    @Binds
    @IntoMap
    @MavericksViewModelKey("im.vector.app.features.debug.leak.DebugMemoryLeaksViewModel")
    fun debugMemoryLeaksViewModelFactory(factory: DebugMemoryLeaksViewModel.Factory): MavericksAssistedViewModelFactory<*, *>
}
