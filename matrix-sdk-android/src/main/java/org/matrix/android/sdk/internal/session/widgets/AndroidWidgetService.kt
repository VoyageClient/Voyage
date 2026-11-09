/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.widgets

import org.matrix.android.sdk.api.session.widgets.WidgetPostAPIMediator
import org.matrix.android.sdk.api.session.widgets.WidgetService
import org.matrix.android.sdk.api.session.widgets.WidgetServiceAndroid
import javax.inject.Inject
import javax.inject.Provider

internal class AndroidWidgetService @Inject constructor(
        delegate: DefaultWidgetService,
        private val widgetPostAPIMediator: Provider<WidgetPostAPIMediator>,
) : WidgetService by delegate, WidgetServiceAndroid {

    override fun getWidgetPostAPIMediator(): WidgetPostAPIMediator {
        return widgetPostAPIMediator.get()
    }
}
