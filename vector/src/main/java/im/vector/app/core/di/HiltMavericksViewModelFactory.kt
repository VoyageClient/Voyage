/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.di

import android.app.Application
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.MavericksViewModel
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import dagger.hilt.EntryPoints
import javax.inject.Provider

/**
 * To connect Mavericks ViewModel creation with Hilt's dependency injection, add the following Factory and companion object to your MavericksViewModel.
 *
 * Example:
 *
 * class MyViewModel @AssistedInject constructor(...): MavericksViewModel<MyState>(...) {
 *
 *   @AssistedFactory
 *   interface Factory : AssistedViewModelFactory<MyViewModel, MyState> {
 *     ...
 *   }
 *
 *   companion object : MavericksViewModelFactory<MyViewModel, MyState> by hiltMavericksViewModelFactory()
 * }
 */

inline fun <reified VM : MavericksViewModel<S>, S : MavericksState> hiltMavericksViewModelFactory() = HiltMavericksViewModelFactory<VM, S>(VM::class.java)

class HiltMavericksViewModelFactory<VM : MavericksViewModel<S>, S : MavericksState>(
        private val viewModelClass: Class<out MavericksViewModel<S>>
) : MavericksViewModelFactory<VM, S> {

    override fun create(viewModelContext: ViewModelContext, state: S): VM {
        val app = viewModelContext.app<Application>()
        val viewModelFactoryMap = factoriesFor(app, viewModelClass.name)
        val viewModelFactory = viewModelFactoryMap[viewModelClass.name]?.get()

        @Suppress("UNCHECKED_CAST")
        val castedViewModelFactory = viewModelFactory as? MavericksAssistedViewModelFactory<VM, S>
        return castedViewModelFactory?.create(state) as VM
    }

    override fun initialState(viewModelContext: ViewModelContext): S? {
        return super.initialState(viewModelContext)
    }

    // The ViewModel factory multibinding is split across several sibling components so that Dalvik's
    // runtime verifier — which resolves every class referenced in a method it verifies — only pulls in
    // one group's ViewModels when that group's generated switch is first verified, instead of all ~113
    // at once (blows ICS's 8MB LinearAlloc). Routing by name loads no ViewModel classes; only the
    // selected group's component is built, verifying only its switch.
    private fun factoriesFor(app: Application, name: String): Map<String, Provider<MavericksAssistedViewModelFactory<*, *>>> {
        return when (mavericksViewModelGroupOf(name)) {
            MavericksViewModelGroup.HOME -> {
                val builder = EntryPoints.get(app, CreateHomeMavericksViewModelComponent::class.java).homeComponentBuilder()
                EntryPoints.get(builder.build(), HomeHiltMavericksEntryPoint::class.java).viewModelFactories
            }
            MavericksViewModelGroup.SETTINGS -> {
                val builder = EntryPoints.get(app, CreateSettingsMavericksViewModelComponent::class.java).settingsComponentBuilder()
                EntryPoints.get(builder.build(), SettingsHiltMavericksEntryPoint::class.java).viewModelFactories
            }
            MavericksViewModelGroup.SPACES -> {
                val builder = EntryPoints.get(app, CreateSpacesMavericksViewModelComponent::class.java).spacesComponentBuilder()
                EntryPoints.get(builder.build(), SpacesHiltMavericksEntryPoint::class.java).viewModelFactories
            }
            MavericksViewModelGroup.ROOM_PROFILE -> {
                val builder = EntryPoints.get(app, CreateRoomProfileMavericksViewModelComponent::class.java).roomProfileComponentBuilder()
                EntryPoints.get(builder.build(), RoomProfileHiltMavericksEntryPoint::class.java).viewModelFactories
            }
            MavericksViewModelGroup.MISC -> {
                val builder = EntryPoints.get(app, CreateMiscMavericksViewModelComponent::class.java).miscComponentBuilder()
                EntryPoints.get(builder.build(), MiscHiltMavericksEntryPoint::class.java).viewModelFactories
            }
        }
    }
}

enum class MavericksViewModelGroup { HOME, SETTINGS, SPACES, ROOM_PROFILE, MISC }

// Mirrors the partition used to generate the per-group modules in MavericksViewModelModule.kt.
// Anything not matched here (incl. debug-only ViewModels) falls into MISC.
fun mavericksViewModelGroupOf(name: String): MavericksViewModelGroup {
    val p = "im.vector.app.features."
    val homePrefixes = listOf("home.", "start.", "login.", "onboarding.", "auth.", "pin.", "workers.signout.")
    if (homePrefixes.any { name.startsWith(p + it) }) return MavericksViewModelGroup.HOME
    if (name == p + "spaces.SpaceListViewModel" ||
            name == p + "spaces.SpaceMenuViewModel" ||
            name == p + "room.RequireActiveMembershipViewModel") return MavericksViewModelGroup.HOME
    if (name.startsWith(p + "settings.")) return MavericksViewModelGroup.SETTINGS
    if (name.startsWith(p + "spaces.") || name.startsWith(p + "roomdirectory.")) return MavericksViewModelGroup.SPACES
    if (name.startsWith(p + "roomprofile.") || name.startsWith(p + "roommemberprofile.")) return MavericksViewModelGroup.ROOM_PROFILE
    return MavericksViewModelGroup.MISC
}
