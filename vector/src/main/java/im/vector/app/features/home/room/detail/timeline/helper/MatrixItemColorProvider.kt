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
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeProvider
import im.vector.lib.ui.styles.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.session.profile.ProfileKeys
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.api.util.Optional
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class MatrixItemColorProvider @Inject constructor(
        private val colorProvider: ColorProvider,
        private val vectorPreferences: VectorPreferences,
        private val themeProvider: ThemeProvider,
        private val activeSessionHolder: Provider<ActiveSessionHolder>,
) {
    private val cache = ConcurrentHashMap<String, Int>()
    private val hexCache = ConcurrentHashMap<String, Int>()

    // im.vector.setting.override_colors: the pre-MSC4522 per-user override, kept as a fallback for
    // overrides written by older clients.
    private val legacyOverrideColors = ConcurrentHashMap<String, Int>()

    // Optimistic per-user overrides applied the instant the user picks one, so every consumer recolors
    // in the same pass rather than waiting for the (two, out-of-order) account-data writes to land.
    // An absent value means "optimistically cleared". Reconciled away once the real data arrives.
    private val optimisticOverrides = ConcurrentHashMap<String, Optional<ColorPreference>>()

    // The element-web palette is theme-dependent and opt-in, so the computed cache is only valid for the
    // current (uglier?, light?) combination. Drop it when either flips.
    @Volatile
    private var cacheSignature: Pair<Boolean, Boolean>? = null

    // Bumped whenever any resolved color may differ; screens rebind rather than restart. A state flow
    // so a screen that was stopped while it changed catches up when it starts again.
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun invalidate() {
        cache.clear()
        cacheSignature = null
        _changes.value++
    }

    /**
     * Override > per-room member color > account profile color > hash default. Other users' chosen
     * colors are subject to the "show other users' profile colors" setting; our own always apply.
     */
    @ColorInt
    fun getColor(matrixItem: MatrixItem): Int = resolveColor(matrixItem, themeProvider.isLightTheme())

    @ColorInt
    fun resolveColor(matrixItem: MatrixItem, light: Boolean): Int {
        return resolveHex(matrixItem, light)?.let { hexToColor(it) } ?: defaultColor(matrixItem, light)
    }

    /** The explicitly chosen color for this theme, or null when the hash default applies. */
    fun resolveHex(matrixItem: MatrixItem, light: Boolean): String? {
        if (matrixItem !is MatrixItem.UserItem) return null
        return overrideHex(matrixItem.id, light) ?: ownColorHex(matrixItem, light)
    }

    /** The user's own chosen color (per-room member, then account profile), ignoring any override. */
    fun ownColorHex(matrixItem: MatrixItem, light: Boolean): String? {
        if (matrixItem !is MatrixItem.UserItem) return null
        val userId = matrixItem.id
        val session = activeSessionHolder.get().getSafeActiveSession()
        if (userId != session?.myUserId && !vectorPreferences.showOthersProfileColors()) return null
        matrixItem.colorPreference?.forTheme(light)?.let { return it }
        val profileService = session?.profileService() ?: return null
        val global = profileService.getCachedColorPreference(userId)
        // prefetch dedups internally (in-flight set + already-cached check), so calling per bind is cheap
        // and lets a forgotten/failed profile be re-requested instead of staying colorless all session.
        if (global == null) profileService.prefetchProfileFields(userId)
        return global?.forTheme(light)
    }

    /** The per-user override for this theme, falling back to the other theme's axis (for rendering). */
    fun overrideHex(userId: String, light: Boolean): String? = effectiveOverride(userId)?.forTheme(light)

    /** The override's exact axis for this theme, with no cross-theme fallback (for settings rows). */
    fun overrideAxis(userId: String, light: Boolean): String? =
            effectiveOverride(userId)?.let { if (light) it.onLight else it.onDark }

    /** The per-user override we set for this user (optimistic, then profile override, then legacy spec). */
    private fun effectiveOverride(userId: String): ColorPreference? {
        optimisticOverrides[userId]?.let { return it.getOrNull() }
        return authoritativeOverride(userId)
    }

    fun setOptimisticOverride(userId: String, color: ColorPreference?) {
        optimisticOverrides[userId] = Optional.from(color?.takeIf { !it.isEmpty() })
        invalidate()
    }

    /** Drop an optimistic override (e.g. its account-data write failed) so the UI falls back to reality. */
    fun clearOptimisticOverride(userId: String) {
        if (optimisticOverrides.remove(userId) != null) invalidate()
    }

    /**
     * Drops an optimistic entry only once the authoritative sources (profile overrides, then the
     * legacy spec) actually match it. Both land as separate account-data writes that clear out of
     * order, so clearing on the first signal would briefly resurrect the other's stale value.
     */
    fun reconcileOptimisticOverrides() {
        if (optimisticOverrides.isEmpty()) return
        var changed = false
        optimisticOverrides.forEach { (userId, optimistic) ->
            if (authoritativeOverride(userId) == optimistic.getOrNull()) {
                optimisticOverrides.remove(userId)
                changed = true
            }
        }
        if (changed) invalidate()
    }

    private fun authoritativeOverride(userId: String): ColorPreference? {
        ColorPreference.parse(ProfileOverrides.fieldsFor(userId)?.get(ProfileKeys.COLOR_PREFERENCE))?.let { return it }
        return legacyOverrideColors[userId]?.let { ColorPreference.fromHex(toHex(it)) }
    }

    /** The hash color this user/room gets when nothing is set. */
    @ColorInt
    fun defaultColor(matrixItem: MatrixItem, light: Boolean = themeProvider.isLightTheme()): Int {
        val uglier = vectorPreferences.useUglierUsernameColors()
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

    fun defaultColorHex(userId: String, light: Boolean = themeProvider.isLightTheme()): String =
            toHex(defaultColor(MatrixItem.UserItem(userId), light))

    fun setLegacyOverrideColors(overrideColors: Map<String, String>?) {
        val parsed = overrideColors.orEmpty().mapNotNull { (id, spec) -> parseLegacyColorSpec(spec)?.let { id to it } }.toMap()
        if (parsed != legacyOverrideColors) {
            legacyOverrideColors.clear()
            legacyOverrideColors.putAll(parsed)
            invalidate()
        }
        reconcileOptimisticOverrides()
    }

    @ColorInt
    private fun hexToColor(hex: String): Int = hexCache.getOrPut(hex) { Color.parseColor(hex) }

    @ColorInt
    private fun parseLegacyColorSpec(colorText: String?): Int? {
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
        fun toHex(@ColorInt color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

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
