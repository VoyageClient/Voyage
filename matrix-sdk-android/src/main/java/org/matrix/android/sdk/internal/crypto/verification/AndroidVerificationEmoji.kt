/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.verification

import org.matrix.android.sdk.R

private val NAME_RES_IDS = intArrayOf(
        R.string.verification_emoji_dog, R.string.verification_emoji_cat, R.string.verification_emoji_lion, R.string.verification_emoji_horse,
        R.string.verification_emoji_unicorn, R.string.verification_emoji_pig, R.string.verification_emoji_elephant, R.string.verification_emoji_rabbit,
        R.string.verification_emoji_panda, R.string.verification_emoji_rooster, R.string.verification_emoji_penguin, R.string.verification_emoji_turtle,
        R.string.verification_emoji_fish, R.string.verification_emoji_octopus, R.string.verification_emoji_butterfly, R.string.verification_emoji_flower,
        R.string.verification_emoji_tree, R.string.verification_emoji_cactus, R.string.verification_emoji_mushroom, R.string.verification_emoji_globe,
        R.string.verification_emoji_moon, R.string.verification_emoji_cloud, R.string.verification_emoji_fire, R.string.verification_emoji_banana,
        R.string.verification_emoji_apple, R.string.verification_emoji_strawberry, R.string.verification_emoji_corn, R.string.verification_emoji_pizza,
        R.string.verification_emoji_cake, R.string.verification_emoji_heart, R.string.verification_emoji_smiley, R.string.verification_emoji_robot,
        R.string.verification_emoji_hat, R.string.verification_emoji_glasses, R.string.verification_emoji_spanner, R.string.verification_emoji_santa,
        R.string.verification_emoji_thumbs_up, R.string.verification_emoji_umbrella, R.string.verification_emoji_hourglass, R.string.verification_emoji_clock,
        R.string.verification_emoji_gift, R.string.verification_emoji_light_bulb, R.string.verification_emoji_book, R.string.verification_emoji_pencil,
        R.string.verification_emoji_paperclip, R.string.verification_emoji_scissors, R.string.verification_emoji_lock, R.string.verification_emoji_key,
        R.string.verification_emoji_hammer, R.string.verification_emoji_telephone, R.string.verification_emoji_flag, R.string.verification_emoji_train,
        R.string.verification_emoji_bicycle, R.string.verification_emoji_aeroplane, R.string.verification_emoji_rocket, R.string.verification_emoji_trophy,
        R.string.verification_emoji_ball, R.string.verification_emoji_guitar, R.string.verification_emoji_trumpet, R.string.verification_emoji_bell,
        R.string.verification_emoji_anchor, R.string.verification_emoji_headphones, R.string.verification_emoji_folder, R.string.verification_emoji_pin,
)

private val DRAWABLE_RES_IDS = intArrayOf(
        R.drawable.ic_verification_dog, R.drawable.ic_verification_cat, R.drawable.ic_verification_lion, R.drawable.ic_verification_horse,
        R.drawable.ic_verification_unicorn, R.drawable.ic_verification_pig, R.drawable.ic_verification_elephant, R.drawable.ic_verification_rabbit,
        R.drawable.ic_verification_panda, R.drawable.ic_verification_rooster, R.drawable.ic_verification_penguin, R.drawable.ic_verification_turtle,
        R.drawable.ic_verification_fish, R.drawable.ic_verification_octopus, R.drawable.ic_verification_butterfly, R.drawable.ic_verification_flower,
        R.drawable.ic_verification_tree, R.drawable.ic_verification_cactus, R.drawable.ic_verification_mushroom, R.drawable.ic_verification_globe,
        R.drawable.ic_verification_moon, R.drawable.ic_verification_cloud, R.drawable.ic_verification_fire, R.drawable.ic_verification_banana,
        R.drawable.ic_verification_apple, R.drawable.ic_verification_strawberry, R.drawable.ic_verification_corn, R.drawable.ic_verification_pizza,
        R.drawable.ic_verification_cake, R.drawable.ic_verification_heart, R.drawable.ic_verification_smiley, R.drawable.ic_verification_robot,
        R.drawable.ic_verification_hat, R.drawable.ic_verification_glasses, R.drawable.ic_verification_spanner, R.drawable.ic_verification_santa,
        R.drawable.ic_verification_thumbs_up, R.drawable.ic_verification_umbrella, R.drawable.ic_verification_hourglass, R.drawable.ic_verification_clock,
        R.drawable.ic_verification_gift, R.drawable.ic_verification_light_bulb, R.drawable.ic_verification_book, R.drawable.ic_verification_pencil,
        R.drawable.ic_verification_paperclip, R.drawable.ic_verification_scissors, R.drawable.ic_verification_lock, R.drawable.ic_verification_key,
        R.drawable.ic_verification_hammer, R.drawable.ic_verification_phone, R.drawable.ic_verification_flag, R.drawable.ic_verification_train,
        R.drawable.ic_verification_bicycle, R.drawable.ic_verification_aeroplane, R.drawable.ic_verification_rocket, R.drawable.ic_verification_trophy,
        R.drawable.ic_verification_ball, R.drawable.ic_verification_guitar, R.drawable.ic_verification_trumpet, R.drawable.ic_verification_bell,
        R.drawable.ic_verification_anchor, R.drawable.ic_verification_headphones, R.drawable.ic_verification_folder, R.drawable.ic_verification_pin,
)

internal fun installSasEmojiResourceIds() {
    sasEmojiResourceIds = { index -> NAME_RES_IDS[index] to DRAWABLE_RES_IDS[index] }
}
