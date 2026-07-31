/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LruCacheTest {

    @Test
    fun `evicts least recently used entry and reports it to entryRemoved`() {
        val evictedKeys = mutableListOf<String>()
        val cache = object : LruCache<String, Int>(2) {
            override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Int?, newValue: Int?) {
                if (evicted) evictedKeys.add(key!!)
            }
        }
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a") // refresh "a", making "b" the eldest
        cache.put("c", 3)

        assertEquals(listOf("b"), evictedKeys)
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun `evictAll clears and notifies, getOrPut computes once`() {
        val evictedKeys = mutableListOf<String>()
        val cache = object : LruCache<String, Int>(5) {
            override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Int?, newValue: Int?) {
                evictedKeys.add(key!!)
            }
        }
        var computations = 0
        cache.getOrPut("x") { computations++; 42 }
        cache.getOrPut("x") { computations++; 43 }
        assertEquals(1, computations)
        assertEquals(42, cache.get("x"))

        cache.evictAll()
        assertEquals(listOf("x"), evictedKeys)
        assertEquals(0, cache.size())
    }
}
