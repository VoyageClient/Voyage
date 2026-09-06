/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.command

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.features.autocomplete.AutocompleteClickListener
import im.vector.app.features.autocomplete.RecyclerViewPresenter
import im.vector.app.features.command.Command
import im.vector.app.features.settings.VectorPreferences

class AutocompleteCommandPresenter @AssistedInject constructor(
        @Assisted val isInThreadTimeline: Boolean,
        context: Context,
        private val controller: AutocompleteCommandController,
        private val vectorPreferences: VectorPreferences
) :
        RecyclerViewPresenter<AutocompleteCommand>(context), AutocompleteClickListener<AutocompleteCommand> {

    @AssistedFactory
    interface Factory {
        fun create(isFromThreadTimeline: Boolean): AutocompleteCommandPresenter
    }

    init {
        controller.listener = this
    }

    override fun instantiateAdapter(): RecyclerView.Adapter<*> {
        return controller.adapter
    }

    override fun instantiateRecyclerView(): RecyclerView = dividedRecyclerView(MAX_VISIBLE_COMMANDS)

    override fun onViewShown() = slideContentUpOnShow()

    override fun animateViewOut(onEnd: Runnable) = slideContentDownOnHide(onEnd)

    private var roomCommands: List<AutocompleteCommand> = emptyList()
    private var lastQuery: CharSequence? = null

    override fun onItemClick(t: AutocompleteCommand) {
        dispatchClick(t)
    }

    override fun onQuery(query: CharSequence?) {
        lastQuery = query
        refresh(query)
    }

    fun updateRoomCommands(commands: List<AutocompleteCommand>) {
        roomCommands = commands
        refresh(lastQuery)
    }

    private fun refresh(query: CharSequence?) {
        val builtInCommands = Command.values()
                .filter {
                    !it.isDevCommand || vectorPreferences.developerMode()
                }
                .filter {
                    if (vectorPreferences.areThreadMessagesEnabled() && isInThreadTimeline) {
                        it.isThreadCommand
                    } else {
                        true
                    }
                }
                .map {
                    AutocompleteCommand(
                            id = it.command,
                            command = it.command,
                            aliases = it.aliases.orEmpty().map(CharSequence::toString),
                            parameters = it.parameters,
                            description = context.getString(it.description),
                    )
                }
        val builtInNames = builtInCommands.flatMap { it.names() }.map { it.lowercase() }.toSet()
        val availableRoomCommands = disambiguateConflictingCommands(roomCommands, builtInNames)
        val data = (builtInCommands + availableRoomCommands).filter {
            query.isNullOrEmpty() || (listOf(it.command) + it.aliases).any { name ->
                name.startsWith("/$query", ignoreCase = true)
            }
        }
        // Keep the current rows on screen so they are what animates away, rather than collapsing first.
        if (data.isEmpty()) {
            requestDismiss()
            return
        }
        controller.setData(data)
    }

    fun clear() {
        controller.listener = null
    }

    private fun AutocompleteCommand.names(): List<String> = listOf(command) + aliases

    private fun disambiguateConflictingCommands(
            commands: List<AutocompleteCommand>,
            builtInNames: Set<String>,
    ): List<AutocompleteCommand> {
        return commands.map { command ->
            val conflictsWithBuiltIn = command.names().any { it.lowercase() in builtInNames }
            val conflictsWithBot = commands.any { other ->
                other.id != command.id && command.names().any { name -> other.names().any { it.equals(name, ignoreCase = true) } }
            }
            if (!conflictsWithBuiltIn && !conflictsWithBot) command else command.copy(
                    insertionCommand = command.sourceUserId?.let { "${command.command}@$it" } ?: command.command
            )
        }
    }

    companion object {
        private const val MAX_VISIBLE_COMMANDS = 3
    }
}
