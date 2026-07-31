/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesKeyValueStoreTest {

    private val factory = SharedPreferencesKeyValueStoreFactory(RuntimeEnvironment.getApplication())

    @Test
    fun `values round-trip through a named store`() {
        val store = factory.create("test_store")

        store.putString("string", "value")
        store.putBoolean("bool", true)
        store.putLong("long", 42L)
        store.putStringSet("set", setOf("a", "b"))

        assertEquals("value", store.getString("string"))
        assertTrue(store.getBoolean("bool", false))
        assertEquals(42L, store.getLong("long", 0L))
        assertEquals(setOf("a", "b"), store.getStringSet("set"))
        assertTrue(store.contains("string"))

        store.remove("string")
        assertNull(store.getString("string"))
        assertFalse(store.contains("string"))
    }

    @Test
    fun `same name returns the same data and different names are isolated`() {
        factory.create("store_a").putString("key", "value")

        assertEquals("value", factory.create("store_a").getString("key"))
        assertNull(factory.create("store_b").getString("key"))
    }

    @Test
    fun `default store is shared across factory calls`() {
        factory.defaultStore().putBoolean("shared_key", true)
        assertTrue(factory.defaultStore().getBoolean("shared_key", false))
    }
}
