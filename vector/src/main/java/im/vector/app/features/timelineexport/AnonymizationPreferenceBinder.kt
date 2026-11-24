/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.content.Context
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.preference.UserAvatarPreference
import im.vector.app.core.preference.VectorEditTextPreference
import im.vector.app.core.preference.VectorListPreference
import im.vector.app.core.preference.VectorPreference
import im.vector.app.core.preference.VectorPreferenceCategory
import im.vector.app.core.preference.VectorSwitchPreference
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.profile.AnonymizationConfig
import org.matrix.android.sdk.api.session.profile.AnonymizationRule
import org.matrix.android.sdk.api.session.profile.IdentityPresentation
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.util.MatrixItem

/**
 * Builds the anonymization settings into a preference category, for the whole account or for one room. Rows are
 * matched by key and updated in place, so a change redraws only what it changed; rows declared in the screen's XML
 * (where settings search finds them) are reused the same way.
 * People are chosen with the invite screen's picker, which [launchPicker] opens; its result goes to [onPeoplePicked].
 */
class AnonymizationPreferenceBinder(
        private val fragment: Fragment,
        private val session: Session,
        private val launchPicker: (title: String, excluded: Collection<String>, roomId: String?) -> Unit,
        private val onChanged: () -> Unit = {},
) {
    private val self = session.myUserId
    private var bound: Pair<PreferenceGroup, String?>? = null
    private val profiles = HashMap<String, Pair<String?, String?>>()
    private val renderedAvatars = HashMap<String, MatrixItem>()

    fun bind(category: PreferenceGroup, roomId: String?) {
        bound = category to roomId
        val context = category.context
        val config = IdentityPresentation.config(self)
        val roomRule = roomId?.let { config.rooms[it] }
        val rows = Rows(category)
        if (roomId == null) {
            rows.reuse(ENABLED) { VectorSwitchPreference(it) }.apply {
                setTitle(CommonStrings.anonymization_enabled)
                isChecked = config.rule.enabled
                setOnPreferenceChangeListener { _, value -> update(config.copy(rule = config.rule.copy(enabled = value == true))); true }
            }
        } else {
            // The same use-the-account-setting/yes/no choice as the room's other overrides.
            val values = context.resources.getStringArray(im.vector.lib.strings.R.array.redaction_inherit_bool_values)
            rows.list(ENABLED, CommonStrings.anonymization_enabled, values.zip(context.resources.getStringArray(im.vector.lib.strings.R.array.redaction_inherit_bools)),
                    values[when (roomRule?.enabled) { null -> 0; true -> 1; false -> 2 }]) { value ->
                update(when (values.indexOf(value)) {
                    0 -> config.copy(rooms = config.rooms - roomId)
                    // A room's own list starts as a copy of the account's.
                    else -> config.copy(rooms = config.rooms + (roomId to (roomRule ?: config.rule).copy(enabled = values.indexOf(value) == 1)))
                })
            }
        }
        val rule = (if (roomId == null) config.rule else roomRule)?.takeIf { it.enabled }
        if (rule != null) {
            rows.list(LIST_MODE, CommonStrings.anonymization_list_mode, listOf(
                    WHITELIST to context.getString(CommonStrings.anonymization_whitelist),
                    BLACKLIST to context.getString(CommonStrings.anonymization_blacklist)), if (rule.onlyListed) BLACKLIST else WHITELIST) {
                updateRule(roomId, config, rule.copy(onlyListed = it == BLACKLIST))
            }
            // Before the exclusions: whatever follows a nested category's rows reads as part of it.
            if (roomId == null) bindAliases(rows, context, config, rule)
            val exclusions = rows.reuse(EXCLUSIONS) { VectorPreferenceCategory(it) }.apply { setTitle(CommonStrings.anonymization_exclusions) }
            val people = Rows(exclusions)
            people.reuse(ADD_EXCLUSION) { VectorPreference(it) }.apply {
                setTitle(CommonStrings.anonymization_add_exclusion)
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    launchPicker(context.getString(CommonStrings.anonymization_exclusions), rule.users, roomId); true
                }
            }
            rule.users.sorted().forEach { userId ->
                people.reuse(PERSON + userId) { UserAvatarPreference(it) }.also { bindPerson(it, config, rule, roomId, userId) }
            }
            people.finish()
        }
        rows.finish()
    }

    /** Adds the people chosen in the picker to the exclusions of the scope last bound. */
    fun onPeoplePicked(userIds: List<String>) {
        val (_, roomId) = bound ?: return
        if (userIds.isEmpty()) return
        val config = IdentityPresentation.config(self)
        val rule = config.ruleFor(roomId)
        updateRule(roomId, config, rule.copy(users = rule.users + userIds))
    }

    // How anonymized people are shown: account-wide, so not offered per room.
    private fun bindAliases(rows: Rows, context: Context, config: AnonymizationConfig, rule: AnonymizationRule) {
        rows.list(NAMING, CommonStrings.export_alias_style, listOf("WORDS" to context.getString(CommonStrings.export_two_words),
                "SHARED" to context.getString(CommonStrings.export_shared_name)), config.naming) {
            update(config.copy(naming = it))
        }
        if (config.naming == "SHARED") rows.text(SHARED_LABEL, CommonStrings.export_shared_label, config.sharedName()) { update(config.copy(sharedLabel = it)) }
        if (rule.includes(self)) rows.text(SELF_LABEL, CommonStrings.export_self_label, config.selfName()) { update(config.copy(selfLabel = it)) }
        rows.reuse(SHARED_AVATAR) { VectorSwitchPreference(it) }.apply {
            setTitle(CommonStrings.export_shared_avatar)
            isChecked = config.sharedAvatar
            setOnPreferenceChangeListener { _, value -> update(config.copy(sharedAvatar = value == true)); true }
        }
    }

    // Who they really are, never their alias; looked up once, then kept so a redraw never blanks the row.
    private fun bindPerson(row: UserAvatarPreference, config: AnonymizationConfig, rule: AnonymizationRule, roomId: String?, userId: String) {
        val cacheKey = "$roomId|$userId"
        fun show(profile: Pair<String?, String?>?) {
            row.title = profile?.first ?: userId
            val item = MatrixItem.UserItem(userId, profile?.first, profile?.second, presentationRoomId = IdentityPresentation.REVEALED_SCOPE)
            if (renderedAvatars[row.key] != item) {
                renderedAvatars[row.key] = item
                row.refreshAvatar(item)
            }
        }
        show(profiles[cacheKey])
        row.summary = if (rule.includes(userId)) row.context.getString(CommonStrings.anonymization_person_anonymized, config.name(userId, self))
        else row.context.getString(CommonStrings.anonymization_person_visible)
        row.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            MaterialAlertDialogBuilder(ContextThemeWrapper(row.context, ThemeUtils.getApplicationThemeRes(row.context)))
                    .setTitle(row.title)
                    .setMessage(CommonStrings.anonymization_remove_exclusion)
                    .setPositiveButton(CommonStrings.action_delete) { _, _ ->
                        val current = IdentityPresentation.config(self)
                        val currentRule = current.ruleFor(roomId)
                        updateRule(roomId, current, currentRule.copy(users = currentRule.users - userId))
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
            true
        }
        if (cacheKey !in profiles) fragment.viewLifecycleOwner.lifecycleScope.launch {
            val profile = trueProfile(userId, roomId)
            profiles[cacheKey] = profile
            show(profile)
        }
    }

    private fun update(next: AnonymizationConfig) {
        IdentityPresentation.update(self, next)
        onChanged()
        val (category, roomId) = bound ?: return
        if (fragment.isAdded) bind(category, roomId)
    }

    private fun updateRule(roomId: String?, config: AnonymizationConfig, next: AnonymizationRule) =
            update(if (roomId != null) config.copy(rooms = config.rooms + (roomId to next)) else config.copy(rule = next))

    // The member event in a room, else the global profile.
    private suspend fun trueProfile(userId: String, roomId: String?): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val member = roomId?.let { session.getRoom(it) }?.stateService()
                ?.getStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(userId))?.content.toModel<RoomMemberContent>()
        val user = session.userService().getUser(userId)
        (member?.displayName ?: user?.displayName) to (member?.avatarUrl ?: user?.avatarUrl)
    }

    /** Keeps a group's rows in the order they are asked for, reusing the existing row of each key. */
    private inner class Rows(private val group: PreferenceGroup) {
        private val kept = HashSet<String>()
        private var next = 0

        fun <T : Preference> reuse(key: String, create: (Context) -> T): T {
            @Suppress("UNCHECKED_CAST")
            val existing = (0 until group.preferenceCount).map(group::getPreference).firstOrNull { it.key == key } as T?
            val preference = existing ?: create(group.context).also { it.key = key; it.isPersistent = false }
            preference.order = next++
            if (existing == null) group.addPreference(preference)
            preference.isVisible = true
            kept += key
            return preference
        }

        // Dialog preferences find themselves again by key, which reuse() always gives them.
        fun list(key: String, title: Int, choices: List<Pair<String, String>>, value: String, update: (String) -> Unit) =
                reuse(key) { VectorListPreference(it) }.apply {
                    setTitle(title)
                    setDialogTitle(title)
                    entries = choices.map { it.second }.toTypedArray()
                    entryValues = choices.map { it.first }.toTypedArray()
                    this.value = value
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    setOnPreferenceChangeListener { _, next -> (next as? String)?.takeIf { it != value }?.let(update); true }
                }

        fun text(key: String, title: Int, value: String, update: (String) -> Unit) = reuse(key) { VectorEditTextPreference(it) }.apply {
            setTitle(title)
            setDialogTitle(title)
            text = value
            summary = value
            setOnPreferenceChangeListener { _, next -> (next as? String)?.trim()?.takeIf { it.isNotEmpty() && it != value }?.let(update); true }
        }

        /** Rows not asked for this time: people are removed, the screen's own rows only hidden. */
        fun finish() {
            (group.preferenceCount - 1 downTo 0).map(group::getPreference).filter { it.key !in kept }.forEach {
                if (it.key?.startsWith(PERSON) == true) {
                    group.removePreference(it)
                    renderedAvatars.remove(it.key)
                } else it.isVisible = false
            }
        }
    }

    companion object {
        const val CATEGORY_KEY = "SETTINGS_ANONYMIZATION_CATEGORY_KEY"
        private const val ENABLED = "ANONYMIZATION_ENABLED"
        private const val LIST_MODE = "ANONYMIZATION_LIST_MODE"
        private const val EXCLUSIONS = "ANONYMIZATION_EXCLUSIONS"
        private const val ADD_EXCLUSION = "ANONYMIZATION_ADD_EXCLUSION"
        private const val PERSON = "ANONYMIZATION_PERSON_"
        private const val NAMING = "ANONYMIZATION_NAMING"
        private const val SHARED_LABEL = "ANONYMIZATION_SHARED_LABEL"
        private const val SELF_LABEL = "ANONYMIZATION_SELF_LABEL"
        private const val SHARED_AVATAR = "ANONYMIZATION_SHARED_AVATAR"
        private const val WHITELIST = "WHITELIST"
        private const val BLACKLIST = "BLACKLIST"

        /** Adds one person to, or deletes them from, the exclusions that apply in [roomId]. */
        fun showPerson(context: Context, self: String, roomId: String?, userId: String, title: CharSequence? = null) {
            val listed = userId in IdentityPresentation.config(self).ruleFor(roomId).users
            MaterialAlertDialogBuilder(ContextThemeWrapper(context, ThemeUtils.getApplicationThemeRes(context)))
                    .setTitle(title ?: context.getString(CommonStrings.export_anonymization))
                    .setMessage(if (listed) CommonStrings.anonymization_remove_exclusion else CommonStrings.anonymization_add_to_exclusions)
                    .setPositiveButton(if (listed) CommonStrings.action_delete else CommonStrings.action_add) { _, _ ->
                        // Read again: the settings may have changed while the dialog was up.
                        val config = IdentityPresentation.config(self)
                        val rule = config.ruleFor(roomId)
                        val next = rule.copy(users = if (listed) rule.users - userId else rule.users + userId)
                        IdentityPresentation.update(self, if (roomId != null && roomId in config.rooms) config.copy(rooms = config.rooms + (roomId to next)) else config.copy(rule = next))
                    }
                    .setNegativeButton(CommonStrings.action_cancel, null)
                    .show()
        }
    }
}
