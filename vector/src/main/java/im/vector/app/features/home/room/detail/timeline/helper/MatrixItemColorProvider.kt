/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.VisibleForTesting
import im.vector.app.core.resources.ColorProvider
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeProvider
import im.vector.lib.ui.styles.R
import org.matrix.android.sdk.api.util.MatrixItem
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class MatrixItemColorProvider @Inject constructor(
        private val colorProvider: ColorProvider,
        private val vectorPreferences: VectorPreferences,
        private val themeProvider: ThemeProvider,
) {
    private val cache = mutableMapOf<String, Int>()
    private val overrideColors = mutableMapOf<String, Int>()

    // The element-web palette is theme-dependent and opt-in, so the computed cache is only valid for the
    // current (uglier?, light?) combination. Drop it when either flips.
    private var cacheSignature: Pair<Boolean, Boolean>? = null

    @ColorInt
    fun getColor(matrixItem: MatrixItem): Int {
        overrideColors[matrixItem.id]?.let { return it }

        val uglier = vectorPreferences.useUglierUsernameColors()
        val light = themeProvider.isLightTheme()
        val signature = uglier to light
        if (signature != cacheSignature) {
            cache.clear()
            cacheSignature = signature
        }

        return cache.getOrPut(matrixItem.id) {
            colorProvider.getColor(
                    when (matrixItem) {
                        is MatrixItem.UserItem ->
                            if (uglier) getElementWebColorFromUserId(matrixItem.id, light) else getColorFromUserId(matrixItem.id)
                        else -> getColorFromRoomId(matrixItem.id)
                    }
            )
        }
    }

    fun setOverrideColors(overrideColors: Map<String, String>?) {
        this.overrideColors.clear()
        overrideColors?.forEach {
            setOverrideColor(it.key, it.value)
        }
    }

    fun setOverrideColor(id: String, colorSpec: String?): Boolean {
        val color = parseUserColorSpec(colorSpec)
        return if (color == null) {
            overrideColors.remove(id)
            false
        } else {
            overrideColors[id] = color
            true
        }
    }

    @ColorInt
    private fun parseUserColorSpec(colorText: String?): Int? {
        return if (colorText.isNullOrBlank()) {
            null
        } else {
            try {
                if (colorText.length == 1) {
                    colorProvider.getColor(getUserColorByIndex(colorText.toInt()))
                } else {
                    Color.parseColor(colorText)
                }
            } catch (e: Throwable) {
                Timber.e(e, "Unable to parse color $colorText")
                null
            }
        }
    }

    companion object {
        @ColorRes
        @VisibleForTesting
        fun getColorFromUserId(userId: String?): Int {
            var hash = 0

            userId?.toList()?.map { chr -> hash = (hash shl 5) - hash + chr.code }

            return getUserColorByIndex(abs(hash))
        }

        @ColorRes
        private fun getUserColorByIndex(index: Int): Int {
            return when (index % 8) {
                1 -> R.color.element_name_02
                2 -> R.color.element_name_03
                3 -> R.color.element_name_04
                4 -> R.color.element_name_05
                5 -> R.color.element_name_06
                6 -> R.color.element_name_07
                7 -> R.color.element_name_08
                else -> R.color.element_name_01
            }
        }

        // element-web's current scheme (Compound's useIdColorHash): sum the char codes, modulo 6.
        // It replaced the nicer pre-2023 palette/hash that getColorFromUserId still mirrors.
        @VisibleForTesting
        fun getElementWebColorIndex(userId: String?): Int {
            return (userId?.sumOf { it.code } ?: 0) % 6
        }

        @ColorRes
        private fun getElementWebColorFromUserId(userId: String?, light: Boolean): Int {
            return if (light) {
                when (getElementWebColorIndex(userId)) {
                    1 -> R.color.element_name_ew_light_02
                    2 -> R.color.element_name_ew_light_03
                    3 -> R.color.element_name_ew_light_04
                    4 -> R.color.element_name_ew_light_05
                    5 -> R.color.element_name_ew_light_06
                    else -> R.color.element_name_ew_light_01
                }
            } else {
                when (getElementWebColorIndex(userId)) {
                    1 -> R.color.element_name_ew_dark_02
                    2 -> R.color.element_name_ew_dark_03
                    3 -> R.color.element_name_ew_dark_04
                    4 -> R.color.element_name_ew_dark_05
                    5 -> R.color.element_name_ew_dark_06
                    else -> R.color.element_name_ew_dark_01
                }
            }
        }

        @ColorRes
        private fun getColorFromRoomId(roomId: String?): Int {
            return when ((roomId?.toList()?.sumOf { it.code } ?: 0) % 3) {
                1 -> R.color.element_room_02
                2 -> R.color.element_room_03
                else -> R.color.element_room_01
            }
        }
    }
}
