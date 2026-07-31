/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

/**
 * Platform seam for small persistent key-value data (queue mementos, lightweight settings,
 * encrypted database keys). Android backs this with SharedPreferences; a desktop implementation
 * can use a flat file. Not for large data sets — use a database for those.
 */
internal interface KeyValueStore {

    fun getString(key: String, default: String? = null): String?

    fun putString(key: String, value: String)

    fun getBoolean(key: String, default: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)

    fun getLong(key: String, default: Long): Long

    fun putLong(key: String, value: Long)

    fun getStringSet(key: String): Set<String>?

    fun putStringSet(key: String, value: Set<String>)

    fun contains(key: String): Boolean

    fun remove(key: String)
}

internal interface KeyValueStoreFactory {

    /** A store isolated under [name]; same name always returns the same data. */
    fun create(name: String): KeyValueStore

    /**
     * The platform's shared default store. On Android this is the app's default SharedPreferences,
     * which the SDK deliberately shares with app-side preference keys.
     */
    fun defaultStore(): KeyValueStore
}
