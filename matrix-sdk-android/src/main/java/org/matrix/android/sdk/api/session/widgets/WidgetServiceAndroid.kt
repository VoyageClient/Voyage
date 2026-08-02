/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.widgets

// The WebView-backed admin-widget JS bridge; kept off WidgetService so that stays android-free.
// DefaultWidgetService implements both; consumers cast session.widgetService() to reach it.
interface WidgetServiceAndroid {

    /**
     * Returns a new instance of [WidgetPostAPIMediator].
     * Be careful to call clearWebView method and setHandler to null to avoid memory leaks.
     * This is to be used for "admin" widgets so you can interact through JS.
     */
    fun getWidgetPostAPIMediator(): WidgetPostAPIMediator
}
