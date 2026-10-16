/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.delayedevents

/**
 * A delayed power-levels event carries a whole snapshot, and Synapse only cancels it when someone *else*
 * changes power levels. So every restore we hold in a room is kept as "current users + the deltas of every
 * restore firing at or before it", and re-based on each of our own changes. Each restore's delta is then
 * recoverable as the difference from the one firing before it.
 */
internal object PowerLevelRestorePlanner {

    data class Pending(val delayId: String, val sendTs: Long, val users: Map<String, Int>)

    /** A level of null means the user falls back to users_default. */
    data class Restore(val delayId: String?, val sendTs: Long, val delta: Map<String, Int?>)

    sealed interface Action {
        data class Cancel(val delayId: String) : Action
        data class Schedule(val replacing: String?, val sendTs: Long, val users: Map<String, Int>) : Action
    }

    fun extractRestores(base: Map<String, Int>, pending: List<Pending>): List<Restore> {
        var previous = base
        return pending.sortedBy { it.sendTs }.map { item ->
            Restore(item.delayId, item.sendTs, diff(previous, item.users)).also { previous = item.users }
        }
    }

    /**
     * [oldUsers] and [newUsers] are the users map before and after our own change. A user whose level that
     * change touched, or who is in [dropped], is no longer restored, and [added] supersedes any other restore
     * of the users it covers.
     */
    fun plan(
            oldUsers: Map<String, Int>,
            newUsers: Map<String, Int>,
            pending: List<Pending>,
            added: Restore? = null,
            dropped: Set<String> = emptySet(),
    ): List<Action> {
        val overridden = (oldUsers.keys + newUsers.keys).filterTo(HashSet()) { oldUsers[it] != newUsers[it] }
        val superseded = added?.delta?.keys.orEmpty()
        val restores = extractRestores(oldUsers, pending)
                .map { it.copy(delta = it.delta - overridden - superseded - dropped) }
                .plus(listOfNotNull(added))
                .sortedBy { it.sendTs }
        val pendingUsers = pending.associate { it.delayId to it.users }
        val cumulative = newUsers.toMutableMap()
        return restores.mapNotNull { restore ->
            if (restore.delta.isEmpty()) {
                return@mapNotNull restore.delayId?.let { Action.Cancel(it) }
            }
            restore.delta.forEach { (userId, level) ->
                if (level == null) cumulative.remove(userId) else cumulative[userId] = level
            }
            val target = cumulative.toMap()
            if (restore.delayId != null && pendingUsers[restore.delayId] == target) {
                null
            } else {
                Action.Schedule(restore.delayId, restore.sendTs, target)
            }
        }
    }

    private fun diff(from: Map<String, Int>, to: Map<String, Int>): Map<String, Int?> =
            (from.keys + to.keys).filter { from[it] != to[it] }.associateWith { to[it] }
}
