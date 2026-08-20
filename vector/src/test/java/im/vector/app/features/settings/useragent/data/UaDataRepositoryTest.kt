/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent.data

import android.content.Context
import im.vector.app.features.settings.useragent.UaSpoofClient
import im.vector.app.features.settings.useragent.providerIdFor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class UaDataRepositoryTest {

    private fun context(): Context =
            mockk<Context>().also { every { it.cacheDir } returns kotlin.io.path.createTempDirectory("uatest").toFile() }

    private fun repository(): UaDataRepository {
        val repo = UaDataRepository(context())
        repo.http = mockk()
        return repo
    }

    @Test
    fun `every provider id the model can produce is registered`() {
        val repo = UaDataRepository(context())
        val missing = mutableListOf<String>()
        for (client in UaSpoofClient.entries) {
            for (field in client.fields) {
                for (os in listOf("windows", "macos")) {
                    val id = client.providerIdFor(field, os) ?: continue
                    if (!repo.hasProvider(id)) missing.add("${client.id}/$field -> $id")
                }
            }
        }
        missing shouldBeEqualTo emptyList()
    }

    @Test
    fun `resolveSdkSha walks the app-tag to components-release chain`() {
        val repo = repository()
        coEvery { repo.http.get(match { it.contains("libs.versions.toml") }, any(), any()) } returns
                """matrix_sdk = "org.matrix.rustcomponents:sdk-android:26.08.05""""
        coEvery { repo.http.get(match { it.contains("releases/tags") }, any(), any()) } returns
                """{"body":"https://github.com/matrix-org/matrix-rust-sdk/tree/b629ba1e689fc45f588e485ee7aec901726cc2a8"}"""
        val sha = runBlocking {
            repo.resolveSdkSha("element-hq/element-x-android", "v26.08.05", "matrix-org/matrix-rust-components-kotlin", "sdk-v")
        }
        sha shouldBeEqualTo "b629ba1e6"
    }

    @Test
    fun `resolveSdkSha returns null when the pin is missing`() {
        val repo = repository()
        coEvery { repo.http.get(any(), any(), any()) } returns "nothing useful here"
        runBlocking { repo.resolveSdkSha("a", "b", "c", "d") } shouldBeEqualTo null
    }

    @Test
    fun `refreshElectron parses and returns the releases`() {
        val repo = repository()
        coEvery { repo.http.get(any()) } returns
                """{"33.2.1":"130.0.6723.137"}"""
        val releases = runBlocking { repo.refreshElectron() }
        releases shouldBeEqualTo listOf(ElectronRelease("33.2.1", "130.0.6723.137"))
    }

    @Test
    fun `refresh returns empty and does not throw on network failure`() {
        val repo = repository()
        coEvery { repo.http.get(any()) } throws java.io.IOException("boom")
        runBlocking { repo.refresh(UaProviderIds.CURL_VERSION) } shouldBeEqualTo emptyList()
        runBlocking { repo.refreshElectron() } shouldBeEqualTo emptyList()
        runBlocking { repo.resolveSdkSha("a", "b", "c", "d") } shouldBeEqualTo null
    }

    @Test
    fun `refresh keeps the existing cache when the source reports not modified`() {
        val repo = repository()
        coEvery { repo.http.get(any()) } returns """[{"tag_name":"curl-8_21_0"}]"""
        runBlocking { repo.refresh(UaProviderIds.CURL_VERSION) }.map { it.value } shouldBeEqualTo listOf("8.21.0")
        // A 304 must return the cached list, not empty, so the field keeps working with no download.
        coEvery { repo.http.get(any()) } throws UaNotModified("url")
        runBlocking { repo.refresh(UaProviderIds.CURL_VERSION) }.map { it.value } shouldBeEqualTo listOf("8.21.0")
    }

    @Test
    fun `refreshElectron keeps the existing cache on not modified`() {
        val repo = repository()
        coEvery { repo.http.get(any()) } returns
                """{"33.2.1":"130.0.6723.137"}"""
        runBlocking { repo.refreshElectron() }
        coEvery { repo.http.get(any()) } throws UaNotModified("url")
        runBlocking { repo.refreshElectron() } shouldBeEqualTo listOf(ElectronRelease("33.2.1", "130.0.6723.137"))
    }

    @Test
    fun `curl release provider maps tags and drops non-release tags`() {
        val repo = repository()
        coEvery { repo.http.get(any()) } returns
                """[{"tag_name":"curl-8_21_0"},{"tag_name":"rc-8_22_0-1"},{"tag_name":"curl-8_20_0"}]"""
        runBlocking { repo.refresh(UaProviderIds.CURL_VERSION) }.map { it.value } shouldBeEqualTo listOf("8.21.0", "8.20.0")
    }

    @Test
    fun `hasProvider is false for an unknown id`() {
        repository().hasProvider("nope") shouldBeEqualTo false
    }
}
