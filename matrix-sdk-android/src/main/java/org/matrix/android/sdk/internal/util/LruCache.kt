/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.util

/**
 * Platform-neutral replacement for the android.util/androidx.collection LruCache subset the SDK
 * uses (get/put/remove/evictAll + the entryRemoved hook, with android's callback semantics),
 * backed by an access-ordered LinkedHashMap.
 */
internal open class LruCache<K : Any, V : Any>(private val maxSize: Int) {

    init {
        require(maxSize > 0) { "maxSize <= 0" }
    }

    private val map = LinkedHashMap<K, V>(0, 0.75f, true)

    @Synchronized
    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V): V? {
        val previous: V?
        var evictedKey: K? = null
        var evictedValue: V? = null
        synchronized(this) {
            previous = map.put(key, value)
            if (map.size > maxSize) {
                val eldest = map.entries.first()
                evictedKey = eldest.key
                evictedValue = eldest.value
                map.remove(eldest.key)
            }
        }
        previous?.let { entryRemoved(false, key, it, value) }
        evictedValue?.let { entryRemoved(true, evictedKey, it, null) }
        return previous
    }

    fun remove(key: K): V? {
        val previous = synchronized(this) { map.remove(key) }
        previous?.let { entryRemoved(false, key, it, null) }
        return previous
    }

    fun evictAll() {
        val entries = synchronized(this) {
            map.toList().also { map.clear() }
        }
        entries.forEach { (key, value) -> entryRemoved(true, key, value, null) }
    }

    @Synchronized
    fun size(): Int = map.size

    protected open fun entryRemoved(evicted: Boolean, key: K?, oldValue: V?, newValue: V?) {}
}

internal inline fun <K : Any, V : Any> LruCache<K, V>.getOrPut(key: K, defaultValue: () -> V): V {
    return get(key) ?: defaultValue().also { put(key, it) }
}
