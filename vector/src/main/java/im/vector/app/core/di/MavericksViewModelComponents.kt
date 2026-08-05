/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.di

import dagger.hilt.DefineComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider

@DefineComponent(parent = SingletonComponent::class)
interface HomeMavericksViewModelComponent

@DefineComponent.Builder
interface HomeMavericksViewModelComponentBuilder {
    fun build(): HomeMavericksViewModelComponent
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CreateHomeMavericksViewModelComponent {
    fun homeComponentBuilder(): HomeMavericksViewModelComponentBuilder
}

@EntryPoint
@InstallIn(HomeMavericksViewModelComponent::class)
interface HomeHiltMavericksEntryPoint {
    val viewModelFactories: Map<String, @JvmSuppressWildcards Provider<MavericksAssistedViewModelFactory<*, *>>>
}

@DefineComponent(parent = SingletonComponent::class)
interface SettingsMavericksViewModelComponent

@DefineComponent.Builder
interface SettingsMavericksViewModelComponentBuilder {
    fun build(): SettingsMavericksViewModelComponent
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CreateSettingsMavericksViewModelComponent {
    fun settingsComponentBuilder(): SettingsMavericksViewModelComponentBuilder
}

@EntryPoint
@InstallIn(SettingsMavericksViewModelComponent::class)
interface SettingsHiltMavericksEntryPoint {
    val viewModelFactories: Map<String, @JvmSuppressWildcards Provider<MavericksAssistedViewModelFactory<*, *>>>
}

@DefineComponent(parent = SingletonComponent::class)
interface SpacesMavericksViewModelComponent

@DefineComponent.Builder
interface SpacesMavericksViewModelComponentBuilder {
    fun build(): SpacesMavericksViewModelComponent
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CreateSpacesMavericksViewModelComponent {
    fun spacesComponentBuilder(): SpacesMavericksViewModelComponentBuilder
}

@EntryPoint
@InstallIn(SpacesMavericksViewModelComponent::class)
interface SpacesHiltMavericksEntryPoint {
    val viewModelFactories: Map<String, @JvmSuppressWildcards Provider<MavericksAssistedViewModelFactory<*, *>>>
}

@DefineComponent(parent = SingletonComponent::class)
interface RoomProfileMavericksViewModelComponent

@DefineComponent.Builder
interface RoomProfileMavericksViewModelComponentBuilder {
    fun build(): RoomProfileMavericksViewModelComponent
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CreateRoomProfileMavericksViewModelComponent {
    fun roomProfileComponentBuilder(): RoomProfileMavericksViewModelComponentBuilder
}

@EntryPoint
@InstallIn(RoomProfileMavericksViewModelComponent::class)
interface RoomProfileHiltMavericksEntryPoint {
    val viewModelFactories: Map<String, @JvmSuppressWildcards Provider<MavericksAssistedViewModelFactory<*, *>>>
}

@DefineComponent(parent = SingletonComponent::class)
interface MiscMavericksViewModelComponent

@DefineComponent.Builder
interface MiscMavericksViewModelComponentBuilder {
    fun build(): MiscMavericksViewModelComponent
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CreateMiscMavericksViewModelComponent {
    fun miscComponentBuilder(): MiscMavericksViewModelComponentBuilder
}

@EntryPoint
@InstallIn(MiscMavericksViewModelComponent::class)
interface MiscHiltMavericksEntryPoint {
    val viewModelFactories: Map<String, @JvmSuppressWildcards Provider<MavericksAssistedViewModelFactory<*, *>>>
}
