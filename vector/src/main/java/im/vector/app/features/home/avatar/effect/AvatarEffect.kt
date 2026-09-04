/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import androidx.annotation.StringRes
import im.vector.lib.strings.CommonStrings

/**
 * An animated avatar shape, ported from 3dgifmaker's p5/WEBGL renderer. [PERIOD_FRAMES] and the
 * frame delay are that site's own defaults, so a rendered loop lines up with the goldens the port is
 * checked against.
 *
 * @param heroFrame the frame a still is taken at, picked so the shape reads as itself — a cube
 *   face-on is indistinguishable from a plain square.
 * @param minSizePx below this the shape is too small to survive its own edge handling and callers
 *   fall back to the still frame.
 */
enum class AvatarEffect(
        val family: Family,
        val heroFrame: Int = 0,
        val minSizePx: Int = 32,
        val reach: Float = 1.4143f,
) {
    SPIN_360(Family.SPIN, heroFrame = 3, reach = 1.10f),
    SPIN_360_THICK(Family.SPIN, heroFrame = 3, reach = 1.10f),
    SPIN_180(Family.SPIN, heroFrame = 3, reach = 1.10f),
    SPIN_CW(Family.SPIN, heroFrame = 3, reach = 0.95f),
    SPIN_CCW(Family.SPIN, heroFrame = 3, reach = 0.95f),
    FRONT_FLIP(Family.SPIN, heroFrame = 3, reach = 1.10f),
    ROCKING(Family.SPIN, heroFrame = 10, reach = 0.95f),
    FIGURE_EIGHT(Family.SPIN, heroFrame = 5, reach = 1.16f),
    RANDOM_ROTATIONS(Family.SPIN, heroFrame = 10, reach = 1.10f),

    CUBE(Family.SOLID, heroFrame = 3, reach = 1.05f),
    CUBE_WOBBLY(Family.SOLID, heroFrame = 3, reach = 1.06f),
    CUBE_DIAGONAL(Family.SOLID, heroFrame = 3, reach = 1.06f),
    PHOTO_CUBE(Family.SOLID, heroFrame = 5),
    SPHERE(Family.SOLID, reach = 0.77f),
    SPHERE_LOW_POLY(Family.SOLID, reach = 0.79f),
    SPHERE_INSIDE(Family.SOLID, heroFrame = 5),
    PYRAMID(Family.SOLID, heroFrame = 3, reach = 1.12f),
    DONUT(Family.SOLID, heroFrame = 5, reach = 0.70f),
    DODECAHEDRON(Family.SOLID, heroFrame = 3, reach = 0.97f),
    TETRAHEDRON(Family.SOLID, heroFrame = 3, reach = 1.11f),

    WAVE_VERTICAL(Family.WARP, heroFrame = 10),
    WAVE_HORIZONTAL(Family.WARP, heroFrame = 10),
    SWIRL(Family.WARP, heroFrame = 10),
    WOBBLE(Family.WARP, heroFrame = 10),
    TREMBLE(Family.WARP),
    SQUISHY(Family.WARP, heroFrame = 10, reach = 1.24f),
    LENS_DISTORT(Family.WARP, heroFrame = 10, reach = 1.21f),
    SHOCKWAVE(Family.WARP, heroFrame = 15, reach = 1.30f),
    BALLOON(Family.WARP, heroFrame = 30),
    HEARTBEAT(Family.WARP, heroFrame = 10),
    FLOAT(Family.WARP, heroFrame = 10, reach = 1.14f),
    ZOOM(Family.WARP, heroFrame = 20),
    ZOOM_TILTED(Family.WARP, heroFrame = 10),
    DVD_BOUNCE(Family.WARP, heroFrame = 10),
    BLINK(Family.WARP, heroFrame = 6, reach = 0.95f);

    /** What kind of shape this reads as, for grouping in the picker. */
    // Declaration order is the order the picker lists them in.
    enum class Family(@StringRes val titleRes: Int) {
        SOLID(CommonStrings.settings_avatar_shape_section_solids),
        SPIN(CommonStrings.settings_avatar_shape_section_spins),
        WARP(CommonStrings.settings_avatar_shape_section_effects),
    }

    companion object {
        const val PERIOD_FRAMES = 39
        const val FRAME_DELAY_MS = 50L

        /** p5's fit factor: the image is scaled to this fraction of the canvas before anything else. */
        const val FIT_FRACTION = 0.63f
    }
}
