/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import kotlin.math.abs
import im.vector.lib.ui.styles.R as StylesR

enum class MessageSwipeAction(@StringRes val titleRes: Int, @DrawableRes val iconRes: Int) {
    NONE(CommonStrings.none, R.drawable.ic_room_profile_block),
    REPLY(CommonStrings.reply, R.drawable.ic_reply),
    EDIT(CommonStrings.edit, R.drawable.ic_edit),
    SELECT(CommonStrings.action_select_message, R.drawable.ic_select_message),
    COPY(CommonStrings.action_copy, R.drawable.ic_copy),
    REACT(CommonStrings.message_add_reaction, R.drawable.ic_add_reaction),
    TRANSLATE(CommonStrings.message_action_translate, R.drawable.ic_translate),
    REPLY_IN_THREAD(CommonStrings.reply_in_thread, R.drawable.ic_reply_in_thread),
    SHARE(CommonStrings.action_share, R.drawable.ic_share),
    FORWARD(CommonStrings.action_forward, R.drawable.ic_forward),
    PERMALINK(CommonStrings.permalink, R.drawable.ic_permalink),
    PIN(CommonStrings.pin_action, R.drawable.ic_pin),
    REDACT(CommonStrings.action_redact, R.drawable.ic_recycle_bin);

    fun color(context: Context, muted: Boolean = false): Int = ThemeUtils.getColor(context, when {
        this == REDACT -> com.google.android.material.R.attr.colorError
        this == NONE || muted -> StylesR.attr.vctr_content_tertiary
        else -> StylesR.attr.vctr_content_primary
    })

    fun drawable(context: Context, @ColorInt tint: Int = color(context)): Drawable =
            DrawableCompat.wrap(AppCompatResources.getDrawable(context, iconRes)!!.mutate()).also {
                DrawableCompat.setTint(it, tint)
            }
}

enum class MessageSwipeSlot(val preferenceKey: String, val defaultAction: MessageSwipeAction, @StringRes val titleRes: Int) {
    LEFT("SETTINGS_MESSAGE_SWIPE_LEFT", MessageSwipeAction.REPLY, CommonStrings.settings_swipe_left),
    LEFT_FAR("SETTINGS_MESSAGE_SWIPE_LEFT_FAR", MessageSwipeAction.EDIT, CommonStrings.settings_swipe_farther_left),
    RIGHT("SETTINGS_MESSAGE_SWIPE_RIGHT", MessageSwipeAction.NONE, CommonStrings.settings_swipe_right),
    RIGHT_FAR("SETTINGS_MESSAGE_SWIPE_RIGHT_FAR", MessageSwipeAction.NONE, CommonStrings.settings_swipe_farther_right)
}

data class MessageSwipeBindings(val actions: Map<MessageSwipeSlot, MessageSwipeAction>) {
    operator fun get(slot: MessageSwipeSlot): MessageSwipeAction = actions[slot] ?: slot.defaultAction

    fun isEnabled(slot: MessageSwipeSlot): Boolean = when (slot) {
        MessageSwipeSlot.LEFT_FAR -> get(MessageSwipeSlot.LEFT) != MessageSwipeAction.NONE
        MessageSwipeSlot.RIGHT_FAR -> get(MessageSwipeSlot.RIGHT) != MessageSwipeAction.NONE
        else -> true
    }

    fun resolve(distanceDp: Float, available: (MessageSwipeAction) -> Boolean = { true }): MessageSwipeAction {
        if (abs(distanceDp) < SWIPE_DISTANCE_DP) return MessageSwipeAction.NONE
        val near = if (distanceDp < 0) MessageSwipeSlot.LEFT else MessageSwipeSlot.RIGHT
        val far = if (distanceDp < 0) MessageSwipeSlot.LEFT_FAR else MessageSwipeSlot.RIGHT_FAR
        if (!isEnabled(far)) return MessageSwipeAction.NONE
        val nearAction = get(near).takeIf { it != MessageSwipeAction.NONE && available(it) }
        val farAction = get(far).takeIf { it != MessageSwipeAction.NONE && available(it) }
        return if (abs(distanceDp) >= FARTHER_DISTANCE_DP) {
            farAction ?: nearAction ?: MessageSwipeAction.NONE
        } else nearAction ?: farAction ?: MessageSwipeAction.NONE
    }

    fun hint(distanceDp: Float, available: (MessageSwipeAction) -> Boolean): MessageSwipeAction {
        val resolved = resolve(distanceDp, available)
        if (resolved != MessageSwipeAction.NONE || abs(distanceDp) >= FARTHER_DISTANCE_DP) return resolved
        val near = if (distanceDp < 0) MessageSwipeSlot.LEFT else MessageSwipeSlot.RIGHT
        val far = if (distanceDp < 0) MessageSwipeSlot.LEFT_FAR else MessageSwipeSlot.RIGHT_FAR
        if (!isEnabled(far)) return MessageSwipeAction.NONE
        val nearAction = get(near)
        if (nearAction != MessageSwipeAction.NONE && available(nearAction)) return nearAction
        return get(far).takeIf { it != MessageSwipeAction.NONE && available(it) } ?: MessageSwipeAction.NONE
    }

    companion object {
        const val SWIPE_DISTANCE_DP = 100f
        const val FARTHER_DISTANCE_DP = 180f
    }
}
