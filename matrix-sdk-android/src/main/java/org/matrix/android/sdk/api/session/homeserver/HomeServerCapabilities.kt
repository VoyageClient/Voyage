/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.api.session.homeserver

data class HomeServerCapabilities(
        /**
         * True if it is possible to change the password of the account.
         */
        val canChangePassword: Boolean = true,
        /**
         * True if it is possible to change the display name of the account.
         */
        val canChangeDisplayName: Boolean = true,
        /**
         * True if it is possible to change the avatar of the account.
         */
        val canChangeAvatar: Boolean = true,
        /**
         * True if it is possible to change the 3pid associations of the account.
         */
        val canChange3pid: Boolean = true,
        /**
         * Max size of file which can be uploaded to the homeserver in bytes. [MAX_UPLOAD_FILE_SIZE_UNKNOWN] if unknown or not retrieved yet.
         */
        val maxUploadFileSize: Long = MAX_UPLOAD_FILE_SIZE_UNKNOWN,
        /**
         * Last version identity server and binding supported.
         */
        val lastVersionIdentityServerSupported: Boolean = false,
        /**
         * Default identity server url, provided in Wellknown.
         */
        val defaultIdentityServerUrl: String? = null,
        /**
         * Room versions supported by the server.
         * This capability describes the default and available room versions a server supports, and at what level of stability.
         * Clients should make use of this capability to determine if users need to be encouraged to upgrade their rooms.
         */
        val roomVersions: RoomVersionCapabilities? = null,
        /**
         * True if the home server support threading.
         */
        val canUseThreading: Boolean = false,

        /**
         * True if the home server supports controlling the logout of all devices when changing password.
         */
        val canControlLogoutDevices: Boolean = false,

        /**
         * True if the home server supports login via qr code, false otherwise.
         */
        val canLoginWithQrCode: Boolean = false,

        /**
         * True if the home server supports threaded read receipts and unread notifications.
         */
        val canUseThreadReadReceiptsAndNotifications: Boolean = false,

        /**
         * True if the home server supports remote toggle of Pusher for a given device.
         */
        val canRemotelyTogglePushNotificationsOfDevices: Boolean = false,

        /**
         * True if the home server supports redaction of related events.
         */
        var canRedactRelatedEvents: Boolean = false,

        /**
         * True if the home server lets privileged users read the content of redacted events (MSC2815).
         */
        var canViewUnredactedContent: Boolean = false,

        /**
         * External account management url for use with OAuth API, provided by MSC4191 /auth_metadata discovery or in unstable Wellknown.
         */
        val externalAccountManagementUrl: String? = null,

        /**
         * External account management supported actions for use with OAuth API, provided by MSC4191 /auth_metadata discovery.
         */
        val externalAccountManagementSupportedActions: List<String>? = null,

        /**
         * Authentication issuer for use with MSC3824 delegated OIDC, provided by /auth_metadata discovery or in unstable Wellknown.
         */
        val authenticationIssuer: String? = null,

        /**
         * If set to true, the SDK will not use the network constraint when configuring Worker for the WorkManager, provided in Wellknown.
         */
        val disableNetworkConstraint: Boolean? = null,
        /**
         * True if the home server supports authenticated media.
         */
        val canUseAuthenticatedMedia: Boolean = false,
        /**
         * MSC4267. True when leaving a room always forgets it too, so there is no point offering the
         * user a separate forget action or an archive of left rooms.
         */
        val forgetForcedUponLeave: Boolean = false,
        /**
         * MSC4380. True when the server honours m.invite_permission_config, so offering the user a
         * "block all invites" switch would actually do something.
         */
        val canBlockInvites: Boolean = false,
        /**
         * MSC4186. True when the server serves simplified sliding sync.
         */
        val canUseSimplifiedSlidingSync: Boolean = false,
        /**
         * MSC4525. True when the server serves paginated sync, the successor to MSC4186 that
         * replaces lists and ranges with server-driven paging.
         */
        val canUsePaginatedSync: Boolean = false,
        /**
         * MSC4429. Which prefixes the server accepts for pushing other users' profile fields down
         * sync, sparing the client a profile fetch per user.
         */
        val syncProfileFieldsSupport: SyncProfileFieldsSupport = SyncProfileFieldsSupport.UNSUPPORTED,
        /**
         * MSC4262. The sliding sync counterpart of [syncProfileFieldsSupport]; only the unstable
         * extension name is defined so far.
         */
        val canUseSlidingSyncProfiles: Boolean = false,
) {

    enum class SyncProfileFieldsSupport {
        UNSUPPORTED,
        UNSTABLE,
        STABLE;

        val isSupported: Boolean get() = this != UNSUPPORTED
    }

    enum class RoomCapabilitySupport {
        SUPPORTED,
        SUPPORTED_UNSTABLE,
        UNSUPPORTED,
        UNKNOWN
    }

    /**
     * Check if a feature is supported by the homeserver.
     * @return
     *  UNKNOWN if the server does not implement room caps
     *  UNSUPPORTED if this feature is not supported
     *  SUPPORTED if this feature is supported by a stable version
     *  SUPPORTED_UNSTABLE if this feature is supported by an unstable version
     *  (unstable version should only be used for dev/experimental purpose)
     */
    fun isFeatureSupported(feature: String): RoomCapabilitySupport {
        if (roomVersions?.capabilities == null) return RoomCapabilitySupport.UNKNOWN
        val info = roomVersions.capabilities[feature] ?: return RoomCapabilitySupport.UNSUPPORTED

        val preferred = info.preferred ?: info.support.lastOrNull()
        val versionCap = roomVersions.supportedVersion.firstOrNull { it.version == preferred }

        return when {
            versionCap == null -> {
                RoomCapabilitySupport.UNKNOWN
            }
            versionCap.status == RoomVersionStatus.STABLE -> {
                RoomCapabilitySupport.SUPPORTED
            }
            else -> {
                RoomCapabilitySupport.SUPPORTED_UNSTABLE
            }
        }
    }

    fun isFeatureSupported(feature: String, byRoomVersion: String): Boolean {
        if (roomVersions?.capabilities == null) return false
        val info = roomVersions.capabilities[feature] ?: return false

        return info.preferred == byRoomVersion || info.support.contains(byRoomVersion)
    }

    /**
     * Use this method to know if you should force a version when creating
     * a room that requires this feature.
     * You can also use #isFeatureSupported prior to this call to check if the
     * feature is supported and report some feedback to user.
     */
    fun versionOverrideForFeature(feature: String): String? {
        val cap = roomVersions?.capabilities?.get(feature)
        return cap?.preferred ?: cap?.support?.lastOrNull()
    }

    val delegatedOidcAuthEnabled: Boolean = authenticationIssuer != null

    companion object {
        const val MAX_UPLOAD_FILE_SIZE_UNKNOWN = -1L
        const val ROOM_CAP_KNOCK = "knock"
        const val ROOM_CAP_RESTRICTED = "restricted"
        const val ROOM_CAP_KNOCK_RESTRICTED = "knock_restricted"

        // Lowest stable room versions that support these join rules, per the spec feature matrix.
        // Used as a fallback when the homeserver does not advertise org.matrix.msc3244.room_capabilities.
        const val ROOM_VERSION_KNOCK = 7
        const val ROOM_VERSION_RESTRICTED = 9
        const val ROOM_VERSION_KNOCK_RESTRICTED = 10

        fun roomVersionAtLeast(roomVersion: String?, minVersion: Int): Boolean {
            return (roomVersion?.toIntOrNull() ?: return false) >= minVersion
        }
    }

    fun getLogoutDeviceURL(deviceId: String): String? {
        if (externalAccountManagementUrl == null) {
            return null
        }

        // default to the stable value:
        var action = "org.matrix.device_delete"
        externalAccountManagementSupportedActions?.also { actions ->
            if (actions.contains("org.matrix.device_delete")) {
                // server supports stable version so use it
            } else if (actions.contains("org.matrix.session_end")) {
                // earlier version of MSC4191:
                action = "org.matrix.session_end"
            } else if (actions.contains("session_end")) {
                // previous unspecified version
                action = "session_end"
            }
        }

        return externalAccountManagementUrl.removeSuffix("/") + "?action=${action}&device_id=${deviceId}"
    }
}
