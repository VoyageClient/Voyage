/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * The resolution boxes have been the source of every compression bug so far: a size that was typed
 * but never sent, and a pair of boxes that only half filled in.
 */
class CompressionSettingsTest {

    private val widescreen = 1920f / 1080f

    @Test
    fun `an untouched setting asks for nothing`() {
        CompressionSettings().isDefault shouldBeEqualTo true
        CompressionSettings(quality = 50).isDefault shouldBeEqualTo false
        CompressionSettings(width = 640, height = 360).isDefault shouldBeEqualTo false
    }

    @Test
    fun `typing one dimension moves the other while the link holds`() {
        val typed = CompressionSettings().withWidth(960, widescreen)

        typed.width shouldBeEqualTo 960
        typed.height shouldBeEqualTo 540
    }

    @Test
    fun `typing a height moves the width the same way`() {
        val typed = CompressionSettings().withHeight(540, widescreen)

        typed.width shouldBeEqualTo 960
        typed.height shouldBeEqualTo 540
    }

    @Test
    fun `breaking the link leaves the other dimension alone`() {
        val unlinked = CompressionSettings(width = 1920, height = 1080, linked = false)

        unlinked.withWidth(960, widescreen).height shouldBeEqualTo 1080
        unlinked.withHeight(540, widescreen).width shouldBeEqualTo 1920
    }

    @Test
    fun `a dimension never rounds away to nothing`() {
        // A very wide source and a tiny width would otherwise ask for a zero-pixel height.
        val squashed = CompressionSettings().withWidth(1, aspect = 100f)

        squashed.height shouldBeEqualTo 1
    }

    @Test
    fun `boxes left at the source size are not a resize request`() {
        // Both boxes are seeded from the source, so an untouched sheet must send nothing at all —
        // this is what stops every attachment being re-encoded just for opening the sheet.
        val untouched = CompressionSettings(width = 1920, height = 1080)

        untouched.withoutRedundantSize(1920, 1080) shouldBeEqualTo CompressionSettings(width = null, height = null)
    }

    @Test
    fun `a size that differs on either axis survives`() {
        CompressionSettings(width = 960, height = 1080).withoutRedundantSize(1920, 1080).width shouldBeEqualTo 960
        CompressionSettings(width = 1920, height = 540).withoutRedundantSize(1920, 1080).height shouldBeEqualTo 540
    }

    @Test
    fun `dropping a redundant size keeps the quality that was chosen`() {
        val qualityOnly = CompressionSettings(quality = 20, width = 1920, height = 1080)
                .withoutRedundantSize(1920, 1080)

        qualityOnly.quality shouldBeEqualTo 20
        qualityOnly.isDefault shouldBeEqualTo false
    }

    @Test
    fun `a half-filled pair is still resolved to both sides`() {
        // The compressors need a complete pair; a width with a null height used to be dropped.
        val fromWidthAlone = CompressionSettings(width = null, height = null).withWidth(640, widescreen)

        (fromWidthAlone.width != null && fromWidthAlone.height != null) shouldBeEqualTo true
    }
}
