/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.Color
import android.net.Uri
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import im.vector.app.core.resources.ColorProvider

fun createUCropWithDefaultSettings(
        colorProvider: ColorProvider,
        source: Uri,
        destination: Uri,
        toolbarTitle: String?,
        freeStyleCropEnabled: Boolean = true
): UCrop {
    return UCrop.of(source, destination)
            .withOptions(
                    UCrop.Options()
                            .apply {
                                setAllowedGestures(
                                        /* tabScale = */ UCropActivity.SCALE,
                                        /* tabRotate = */ UCropActivity.ALL,
                                        /* tabAspectRatio = */ UCropActivity.SCALE
                                )
                                setToolbarTitle(toolbarTitle)
                                setFreeStyleCropEnabled(freeStyleCropEnabled)
                                // Color used for toolbar icon and text
                                setToolbarColor(colorProvider.getColorFromAttribute(android.R.attr.colorBackground))
                                setToolbarWidgetColor(colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_primary))
                                // Background
                                setRootViewBackgroundColor(colorProvider.getColorFromAttribute(android.R.attr.colorBackground))
                                // Dark status bar, matching what the Vector light/dark themes use, so its white
                                // icons stay legible (the old fixed light grey looked wrong in dark themes). On
                                // Android 15 this is ignored anyway; VectorUCropActivity handles it there.
                                setStatusBarColor(colorProvider.getColor(im.vector.lib.ui.styles.R.color.android_status_bar_background_dark))
                                setActiveControlsWidgetColor(colorProvider.getColorFromAttribute(com.google.android.material.R.attr.colorPrimary))
                                // Hide the logo (does not work)
                                setLogoColor(Color.TRANSPARENT)
                            }
            )
}
