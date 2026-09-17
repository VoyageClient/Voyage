/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.android.sdk.api.session.profile.ColorPreference

class ProfileColorStoreTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun storeFor() = ProfileColorStore(temporaryFolder.root.resolve("files"))

    private val file get() = temporaryFolder.root.resolve("files").resolve("profile_colors.z")

    @Test
    fun `given a stored color, when read back by a new store, then it survives the restart`() {
        storeFor().put("@alice:example.org", ColorPreference(onLight = "#112233", onDark = "#AABBCC"))

        storeFor().get("@alice:example.org") shouldBeEqualTo ColorPreference(onLight = "#112233", onDark = "#AABBCC")
    }

    @Test
    fun `given a color set for one theme only, when read back, then the other stays absent`() {
        storeFor().put("@alice:example.org", ColorPreference(onDark = "#AABBCC"))

        storeFor().get("@alice:example.org") shouldBeEqualTo ColorPreference(onLight = null, onDark = "#AABBCC")
    }

    @Test
    fun `given a user who cleared their color, when stored as empty, then the old value stops being restored`() {
        val store = storeFor()
        store.put("@alice:example.org", ColorPreference.fromHex("#112233"))

        store.put("@alice:example.org", null)

        storeFor().get("@alice:example.org").shouldBeNull()
    }

    @Test
    fun `given more colors than the limit, when storing, then the least recently used are evicted`() {
        val store = storeFor()
        repeat(1100) { store.put("@user$it:example.org", ColorPreference.fromHex("#112233")) }

        store.get("@user0:example.org").shouldBeNull()
        store.get("@user1099:example.org") shouldBeEqualTo ColorPreference.fromHex("#112233")
    }

    @Test
    fun `given an entry read recently, when the limit is passed, then it outlives older untouched ones`() {
        val store = storeFor()
        repeat(1024) { store.put("@user$it:example.org", ColorPreference.fromHex("#112233")) }

        store.get("@user0:example.org")
        store.put("@newcomer:example.org", ColorPreference.fromHex("#445566"))

        store.get("@user0:example.org") shouldBeEqualTo ColorPreference.fromHex("#112233")
        store.get("@user1:example.org").shouldBeNull()
    }

    @Test
    fun `given stored colors, when the cache is cleared, then nothing is restored`() {
        val store = storeFor()
        store.put("@alice:example.org", ColorPreference.fromHex("#112233"))

        store.clear()

        store.get("@alice:example.org").shouldBeNull()
        storeFor().get("@alice:example.org").shouldBeNull()
    }

    @Test
    fun `given a color that is the same on both themes, when read back, then one stored value serves both`() {
        storeFor().put("@alice:example.org", ColorPreference.fromHex("#112233"))

        storeFor().get("@alice:example.org") shouldBeEqualTo ColorPreference.fromHex("#112233")
    }

    @Test
    fun `given a full store, when persisted, then it stays well under what a plain line per entry costs`() {
        val store = storeFor()
        repeat(1024) { store.put("@user$it:example.org", ColorPreference.fromHex("#112233")) }

        val bytes = file.length()

        // A line per entry is ~33 bytes uncompressed; the repeated server name is what makes it cheap.
        (bytes < 1024 * 12) shouldBeEqualTo true
    }

    @Test
    fun `given a garbled file, when loading, then it reads as empty rather than throwing`() {
        temporaryFolder.root.resolve("files").mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        storeFor().get("@alice:example.org").shouldBeNull()
    }

    @Test
    fun `given several users, when read back, then each keeps its own color`() {
        val store = storeFor()
        store.put("@alice:example.org", ColorPreference.fromHex("#112233"))
        store.put("@bob:example.org", ColorPreference.fromHex("#445566"))

        storeFor().get("@alice:example.org") shouldBeEqualTo ColorPreference.fromHex("#112233")
        storeFor().get("@bob:example.org") shouldBeEqualTo ColorPreference.fromHex("#445566")
    }
}
