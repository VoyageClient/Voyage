/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

internal class SharedPreferencesKeyValueStore(
        private val preferences: SharedPreferences,
) : KeyValueStore {

    override fun getString(key: String, default: String?): String? = preferences.getString(key, default)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }

    override fun getLong(key: String, default: Long): Long = preferences.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        preferences.edit().putLong(key, value).apply()
    }

    override fun getStringSet(key: String): Set<String>? = preferences.getStringSet(key, null)

    override fun putStringSet(key: String, value: Set<String>) {
        preferences.edit().putStringSet(key, value).apply()
    }

    override fun contains(key: String): Boolean = preferences.contains(key)

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

internal class SharedPreferencesKeyValueStoreFactory(
        private val context: Context,
) : KeyValueStoreFactory {

    override fun create(name: String): KeyValueStore {
        return SharedPreferencesKeyValueStore(context.getSharedPreferences(name, Context.MODE_PRIVATE))
    }

    override fun defaultStore(): KeyValueStore {
        return SharedPreferencesKeyValueStore(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))
    }
}
