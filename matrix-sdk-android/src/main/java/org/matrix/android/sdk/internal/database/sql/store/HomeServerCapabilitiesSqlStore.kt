/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.HomeServerCapabilitiesEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Home_server_capabilities as HomeServerCapabilitiesRow

/** SQL access for the single-row `home_server_capabilities`. */
internal class HomeServerCapabilitiesSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.homeServerCapabilitiesQueries

    fun get(): HomeServerCapabilitiesEntity? = queries.selectFirst().executeAsOneOrNull()?.toHomeServerCapabilitiesEntity()

    fun upsert(entity: HomeServerCapabilitiesEntity) = queries.upsert(
            can_change_password = entity.canChangePassword.toLong(),
            can_change_display_name = entity.canChangeDisplayName.toLong(),
            can_change_avatar = entity.canChangeAvatar.toLong(),
            can_change_3pid = entity.canChange3pid.toLong(),
            room_versions_json = entity.roomVersionsJson,
            max_upload_file_size = entity.maxUploadFileSize,
            last_version_identity_server_supported = entity.lastVersionIdentityServerSupported.toLong(),
            default_identity_server_url = entity.defaultIdentityServerUrl,
            last_updated_timestamp = entity.lastUpdatedTimestamp,
            can_use_threading = entity.canUseThreading.toLong(),
            can_control_logout_devices = entity.canControlLogoutDevices.toLong(),
            can_login_with_qr_code = entity.canLoginWithQrCode.toLong(),
            can_use_thread_read_receipts_and_notifications = entity.canUseThreadReadReceiptsAndNotifications.toLong(),
            can_remotely_toggle_push_notifications_of_devices = entity.canRemotelyTogglePushNotificationsOfDevices.toLong(),
            can_redact_event_with_relations = entity.canRedactEventWithRelations.toLong(),
            can_view_unredacted_content = entity.canViewUnredactedContent.toLong(),
            external_account_management_url = entity.externalAccountManagementUrl,
            external_account_management_supported_actions = entity.externalAccountManagementSupportedActions,
            authentication_issuer = entity.authenticationIssuer,
            disable_network_constraint = entity.disableNetworkConstraint?.let { if (it) 1L else 0L },
            can_use_authenticated_media = entity.canUseAuthenticatedMedia.toLong(),
    )

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}

internal fun HomeServerCapabilitiesRow.toHomeServerCapabilitiesEntity(): HomeServerCapabilitiesEntity = HomeServerCapabilitiesEntity(
            canChangePassword = can_change_password != 0L,
            canChangeDisplayName = can_change_display_name != 0L,
            canChangeAvatar = can_change_avatar != 0L,
            canChange3pid = can_change_3pid != 0L,
            roomVersionsJson = room_versions_json,
            maxUploadFileSize = max_upload_file_size,
            lastVersionIdentityServerSupported = last_version_identity_server_supported != 0L,
            defaultIdentityServerUrl = default_identity_server_url,
            lastUpdatedTimestamp = last_updated_timestamp,
            canUseThreading = can_use_threading != 0L,
            canControlLogoutDevices = can_control_logout_devices != 0L,
            canLoginWithQrCode = can_login_with_qr_code != 0L,
            canUseThreadReadReceiptsAndNotifications = can_use_thread_read_receipts_and_notifications != 0L,
            canRemotelyTogglePushNotificationsOfDevices = can_remotely_toggle_push_notifications_of_devices != 0L,
            canRedactEventWithRelations = can_redact_event_with_relations != 0L,
            canViewUnredactedContent = can_view_unredacted_content != 0L,
            externalAccountManagementUrl = external_account_management_url,
            externalAccountManagementSupportedActions = external_account_management_supported_actions,
            authenticationIssuer = authentication_issuer,
            disableNetworkConstraint = disable_network_constraint?.let { it != 0L },
            canUseAuthenticatedMedia = can_use_authenticated_media != 0L,
    )
