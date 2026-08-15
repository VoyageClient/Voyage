/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.session.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.flow.flow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MSC4278 media preview controls, kept in `m.media_preview_config` global account data so the choice
 * follows the user across devices and interoperates with Element Web/X.
 *
 * The local preferences stay the thing every read site actually hits; this only mirrors the synced
 * value into them and publishes local changes back out.
 */
@Singleton
class MediaPreviewConfigDataSource @Inject constructor(
        // Lazy: ActiveSessionHolder builds ConfigureAndStartSessionUseCase, which injects this.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        private val vectorPreferences: VectorPreferences,
) {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observeJob = AtomicReference<Job?>(null)
    private val publishing = AtomicInteger(0)

    fun onSessionStarted(session: Session) {
        val newJob = session.coroutineScope.launch {
            session.flow()
                    .liveUserAccountData(setOf(STABLE_TYPE, UNSTABLE_TYPE))
                    .collect { events ->
                        // A publish writes the two types one after the other, so mid-flight the pair
                        // disagrees and the rule below would apply the not-yet-written sibling, undoing
                        // the choice the user just made.
                        if (publishing.get() > 0) return@collect
                        // Unstable wins: Element Web only writes that one, so preferring the stable key
                        // would permanently mask any later change made there.
                        val content = events.firstOrNull { it.type == UNSTABLE_TYPE }?.content
                                ?: events.firstOrNull { it.type == STABLE_TYPE }?.content
                        if (content != null) {
                            apply(content)
                        } else if (!vectorPreferences.hasSyncedMediaPreviewConfig() && hasLocalOverride()) {
                            // Nothing recorded on the account. Only publish if this device actually holds a
                            // choice the user made: the first emission is the local DB snapshot, which on a
                            // fresh sign-in is empty, so publishing defaults here would overwrite whatever
                            // the account really had before the first sync arrives.
                            try {
                                publish(session)
                            } catch (failure: Throwable) {
                                // An unhandled throw here would kill the collector for the whole session.
                                Timber.w(failure, "Unable to publish the initial media preview config")
                            }
                        } else {
                            // Past the one-time migration, an absent event means this account has no
                            // config — reset the device mirror so a switched-to account doesn't
                            // silently inherit the previous account's preview policy.
                            resetLocalToDefaults()
                        }
                        vectorPreferences.setMediaPreviewConfigSynced()
                    }
        }
        observeJob.getAndSet(newJob)?.cancel()
    }

    /**
     * Push the local settings out to account data; best-effort, failures only warn. The preference
     * framework persists after its change listener returns, so callers pass the value they just chose
     * rather than letting this read a stale preference.
     */
    fun onLocalChange(mode: MediaPreviewMode? = null, hideInviteAvatars: Boolean? = null) {
        coroutineScope.launch {
            val session = activeSessionHolder.get().getSafeActiveSession() ?: return@launch
            try {
                publish(session, mode, hideInviteAvatars)
            } catch (failure: Throwable) {
                Timber.w(failure, "Unable to save media preview config account data")
            }
        }
    }

    /** True when this device holds a media-preview choice that differs from the shipped defaults. */
    private fun hasLocalOverride(): Boolean {
        return vectorPreferences.getMediaPreviewMode() != MediaPreviewMode.ALWAYS_SHOW ||
                vectorPreferences.hideInviteAvatars()
    }

    private fun resetLocalToDefaults() {
        if (!hasLocalOverride()) return
        vectorPreferences.setMediaPreviewMode(MediaPreviewMode.ALWAYS_SHOW)
        vectorPreferences.setHideInviteAvatars(false)
    }

    private fun apply(content: Content) {
        (content[KEY_MEDIA_PREVIEWS] as? String)?.let { remote ->
            val current = vectorPreferences.getMediaPreviewMode()
            // DIRECT is a fork-only refinement of "private" and publishes as such, so a remote "private"
            // must not downgrade a local DIRECT back to the broader mode.
            if (!(remote == VALUE_PRIVATE && current == MediaPreviewMode.DIRECT)) {
                MediaPreviewMode.fromMscValue(remote)?.let { vectorPreferences.setMediaPreviewMode(it) }
            }
        }
        (content[KEY_INVITE_AVATARS] as? String)?.let { remote ->
            when (remote) {
                VALUE_ON -> vectorPreferences.setHideInviteAvatars(false)
                VALUE_OFF -> vectorPreferences.setHideInviteAvatars(true)
            }
        }
    }

    private suspend fun publish(session: Session, mode: MediaPreviewMode? = null, hideInviteAvatars: Boolean? = null) {
        val content = mapOf(
                KEY_MEDIA_PREVIEWS to (mode ?: vectorPreferences.getMediaPreviewMode()).toMscValue(),
                KEY_INVITE_AVATARS to if (hideInviteAvatars ?: vectorPreferences.hideInviteAvatars()) VALUE_OFF else VALUE_ON,
        )
        publishing.incrementAndGet()
        try {
            session.accountDataService().updateUserAccountData(STABLE_TYPE, content)
            // Element Web only reads the unstable type, so keep both in step for interop.
            session.accountDataService().updateUserAccountData(UNSTABLE_TYPE, content)
        } finally {
            publishing.decrementAndGet()
        }
    }

    companion object {
        private const val STABLE_TYPE = UserAccountDataTypes.TYPE_MEDIA_PREVIEW_CONFIG
        private const val UNSTABLE_TYPE = UserAccountDataTypes.TYPE_MEDIA_PREVIEW_CONFIG_UNSTABLE

        private const val KEY_MEDIA_PREVIEWS = "media_previews"
        private const val KEY_INVITE_AVATARS = "invite_avatars"

        const val VALUE_ON = "on"
        const val VALUE_OFF = "off"
        const val VALUE_PRIVATE = "private"
    }
}

private fun MediaPreviewMode.toMscValue(): String = when (this) {
    MediaPreviewMode.ALWAYS_SHOW -> MediaPreviewConfigDataSource.VALUE_ON
    MediaPreviewMode.ALWAYS_HIDE -> MediaPreviewConfigDataSource.VALUE_OFF
    // "private" is the nearest spec value; DIRECT is stricter and stays a local refinement.
    MediaPreviewMode.PRIVATE, MediaPreviewMode.DIRECT -> MediaPreviewConfigDataSource.VALUE_PRIVATE
}

private fun MediaPreviewMode.Companion.fromMscValue(value: String): MediaPreviewMode? = when (value) {
    MediaPreviewConfigDataSource.VALUE_ON -> MediaPreviewMode.ALWAYS_SHOW
    MediaPreviewConfigDataSource.VALUE_OFF -> MediaPreviewMode.ALWAYS_HIDE
    MediaPreviewConfigDataSource.VALUE_PRIVATE -> MediaPreviewMode.PRIVATE
    else -> null
}
