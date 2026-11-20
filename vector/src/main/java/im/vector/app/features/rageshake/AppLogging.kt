/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.rageshake

import android.util.Log
import im.vector.app.core.resources.BuildMeta
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.debug.DebugLog
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Plants or uproots every log destination to follow the "Enable logging" setting. */
@Singleton
class AppLogging @Inject constructor(
        private val buildMeta: BuildMeta,
        private val vectorPreferences: VectorPreferences,
        private val vectorFileLogger: VectorFileLogger,
        private val debugTagFileLogger: DebugTagFileLogger,
) {

    private var enabled: Boolean? = null

    private val logcatTree by lazy { LogcatTree() }

    @Synchronized
    fun apply(enable: Boolean = vectorPreferences.isLoggingEnabled()) {
        if (enable == enabled) return
        val wasEnabled = enabled == true
        enabled = enable
        DebugLog.enabled = enable
        val trees = listOfNotNull(
                vectorFileLogger,
                debugTagFileLogger,
                // Before Jelly Bean any app holding READ_LOGS can read every other app's logcat.
                logcatTree.takeIf { buildMeta.isDebug },
        )
        if (enable) {
            vectorFileLogger.open()
            Timber.plant(*trees.toTypedArray())
        } else {
            if (wasEnabled) trees.forEach { Timber.uproot(it) }
            vectorFileLogger.close()
            vectorFileLogger.deleteLogFiles()
        }
    }
}

// Per-event chatter from these tags filled a 512 KiB logcat buffer within a minute; the file log still keeps it.
internal class LogcatTree : Timber.DebugTree() {

    public override fun isLoggable(tag: String?, priority: Int): Boolean =
            priority >= Log.INFO || tag?.substringBefore('$') !in FILE_ONLY_TAGS

    private companion object {
        val FILE_ONLY_TAGS = setOf(
                "CRYPTO/MXOlmDevice",
                "CRYPTO/MXMegolmDecryption",
                "CRYPTO/InboundGroupSessionStore",
                "CRYPTO/EventDecryptor",
                "CRYPTO/OutgoingKeyRequestManager",
                "CRYPTO/IncomingKeyRequestManager",
                "StreamEventsManager",
                "SyncResponseHandler",
                "DefaultProcessEventForPushTask",
                "NotificationDrawerManager",
                "UpdateTrustTaskBody",
                "DefaultCrossSigningService",
                "VerificationMessageProcessor",
        )
    }
}
