/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.analytics.impl

import im.vector.app.features.analytics.VectorAnalytics
import im.vector.app.features.analytics.itf.VectorAnalyticsEvent
import im.vector.app.features.analytics.itf.VectorAnalyticsScreen
import im.vector.app.features.analytics.plan.SuperProperties
import im.vector.app.features.analytics.plan.UserProperties
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Telemetry is disabled in this fork: this is a no-op implementation that never collects or sends any
 * data. It reports "no consent" and "already asked" so no analytics UI is ever shown.
 */
@Singleton
class DefaultVectorAnalytics @Inject constructor() : VectorAnalytics {

    override fun init() = Unit

    override fun getUserConsent(): Flow<Boolean> = flowOf(false)

    override suspend fun setUserConsent(userConsent: Boolean) = Unit

    override fun didAskUserConsent(): Flow<Boolean> = flowOf(true)

    override suspend fun setDidAskUserConsent() = Unit

    override fun getAnalyticsId(): Flow<String> = flowOf("")

    override suspend fun setAnalyticsId(analyticsId: String) = Unit

    override suspend fun onSignOut() = Unit

    override fun capture(event: VectorAnalyticsEvent) = Unit

    override fun screen(screen: VectorAnalyticsScreen) = Unit

    override fun updateUserProperties(userProperties: UserProperties) = Unit

    override fun updateSuperProperties(updatedProperties: SuperProperties) = Unit

    override fun trackError(throwable: Throwable) = Unit
}
