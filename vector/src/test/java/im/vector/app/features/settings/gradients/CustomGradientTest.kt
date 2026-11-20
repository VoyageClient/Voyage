/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.gradients

import im.vector.app.features.settings.gradients.CustomGradient.Problem
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class CustomGradientTest {
    private val existing = listOf(CustomGradient("sunset", listOf(0xFF0000)), CustomGradient("ocean", listOf(0x0000FF, 0x00FFFF)))

    @Test
    fun `a labeled gradient with one or more colors is valid`() {
        CustomGradient.validate("dusk", listOf(0x123456), existing, originalLabel = null) shouldBeEqualTo null
        CustomGradient.validate("dusk-2_b", listOf(1, 2, 3), existing, originalLabel = null) shouldBeEqualTo null
    }

    @Test
    fun `label and colors are mandatory`() {
        CustomGradient.validate("", listOf(1), existing, originalLabel = null) shouldBeEqualTo Problem.EMPTY_LABEL
        CustomGradient.validate("dusk", emptyList(), existing, originalLabel = null) shouldBeEqualTo Problem.NO_COLORS
        CustomGradient.validate("two words", listOf(1), existing, originalLabel = null) shouldBeEqualTo Problem.INVALID_LABEL
        CustomGradient.validate("a".repeat(CustomGradient.MAX_LABEL_LENGTH + 1), listOf(1), existing, null) shouldBeEqualTo Problem.INVALID_LABEL
    }

    @Test
    fun `labels conflict ignoring case, except with the gradient being edited`() {
        CustomGradient.validate("SUNSET", listOf(1), existing, originalLabel = null) shouldBeEqualTo Problem.DUPLICATE_LABEL
        CustomGradient.validate("Ocean", listOf(1), existing, originalLabel = "sunset") shouldBeEqualTo Problem.DUPLICATE_LABEL
        CustomGradient.validate("sunset", listOf(1), existing, originalLabel = "sunset") shouldBeEqualTo null
        CustomGradient.validate("Sunset", listOf(1), existing, originalLabel = "sunset") shouldBeEqualTo null
    }

    @Test
    fun `isValid accepts a single color but not none or too many`() {
        CustomGradient("solid", listOf(0xFFFFFF)).isValid() shouldBeEqualTo true
        CustomGradient("none", emptyList()).isValid() shouldBeEqualTo false
        CustomGradient("many", List(CustomGradient.MAX_COLORS + 1) { 0 }).isValid() shouldBeEqualTo false
    }
}
