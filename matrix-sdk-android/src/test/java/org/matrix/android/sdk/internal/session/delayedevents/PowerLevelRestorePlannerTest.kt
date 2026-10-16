/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.delayedevents

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.internal.session.delayedevents.PowerLevelRestorePlanner.Action
import org.matrix.android.sdk.internal.session.delayedevents.PowerLevelRestorePlanner.Pending
import org.matrix.android.sdk.internal.session.delayedevents.PowerLevelRestorePlanner.Restore

class PowerLevelRestorePlannerTest {

    private val mod = "@mod:example.org" to 100
    private val alice = "@alice:example.org"
    private val bob = "@bob:example.org"

    @Test
    fun `first restore is scheduled on top of the current users`() {
        val users = mapOf(mod, alice to -1)

        val actions = PowerLevelRestorePlanner.plan(users, users, emptyList(), Restore(null, 10, mapOf(alice to null)))

        assertEquals(listOf(Action.Schedule(null, 10, mapOf(mod))), actions)
    }

    @Test
    fun `muting someone else re-bases an earlier restore so it keeps them muted`() {
        val pending = listOf(Pending("a", 10, mapOf(mod)))

        val actions = PowerLevelRestorePlanner.plan(
                oldUsers = mapOf(mod, alice to -1),
                newUsers = mapOf(mod, alice to -1, bob to -1),
                pending = pending,
        )

        assertEquals(listOf(Action.Schedule("a", 10, mapOf(mod, bob to -1))), actions)
    }

    @Test
    fun `a later restore includes the deltas of every restore firing before it`() {
        val users = mapOf(mod, alice to -1, bob to -1)
        val pending = listOf(Pending("a", 10, mapOf(mod, bob to -1)))

        val actions = PowerLevelRestorePlanner.plan(users, users, pending, Restore(null, 20, mapOf(bob to null)))

        assertEquals(listOf(Action.Schedule(null, 20, mapOf(mod))), actions)
    }

    @Test
    fun `an earlier restore added later is folded into the restores that fire after it`() {
        val users = mapOf(mod, alice to -1, bob to -1)
        val pending = listOf(Pending("b", 20, mapOf(mod, alice to -1)))

        val actions = PowerLevelRestorePlanner.plan(users, users, pending, Restore(null, 10, mapOf(alice to null)))

        assertEquals(
                listOf(
                        Action.Schedule(null, 10, mapOf(mod, bob to -1)),
                        Action.Schedule("b", 20, mapOf(mod)),
                ),
                actions,
        )
    }

    @Test
    fun `changing a user's level by hand drops their pending restore`() {
        val pending = listOf(Pending("a", 10, mapOf(mod)))

        val actions = PowerLevelRestorePlanner.plan(mapOf(mod, alice to -1), mapOf(mod), pending)

        assertEquals(listOf(Action.Cancel("a")), actions)
    }

    @Test
    fun `a new restore for the same user supersedes the old one`() {
        val users = mapOf(mod, alice to -1)
        val pending = listOf(Pending("a", 10, mapOf(mod)))

        val actions = PowerLevelRestorePlanner.plan(users, users, pending, Restore(null, 30, mapOf(alice to 50)))

        assertEquals(
                listOf(Action.Cancel("a"), Action.Schedule(null, 30, mapOf(mod, alice to 50))),
                actions,
        )
    }

    @Test
    fun `dropping one user keeps the other restores composing`() {
        val users = mapOf(mod, alice to -1, bob to -1)
        val pending = listOf(Pending("a", 10, mapOf(mod, bob to -1)), Pending("b", 20, mapOf(mod)))

        val actions = PowerLevelRestorePlanner.plan(users, users, pending, dropped = setOf(alice))

        assertEquals(listOf(Action.Cancel("a"), Action.Schedule("b", 20, mapOf(mod, alice to -1))), actions)
    }

    @Test
    fun `a change that leaves the users map alone touches nothing`() {
        val users = mapOf(mod, alice to -1)

        val actions = PowerLevelRestorePlanner.plan(users, users, listOf(Pending("a", 10, mapOf(mod))))

        assertEquals(emptyList<Action>(), actions)
    }
}
