/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.text.toSpannable
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.core.extensions.containsRtLOverride
import im.vector.app.core.extensions.ensureEndsLeftToRight
import im.vector.app.core.extensions.filterDirectionOverrides
import im.vector.lib.strings.CommonStrings
import java.net.URL

/**
 * Opens [url] in the browser, asking first when the [label] it was shown under doesn't match where it leads.
 */
fun openExternalLinkWithConfirmation(context: Context, url: String, label: String) {
    val activity = context.findActivity()
    if (activity == null) {
        openUrlInExternalBrowser(context, url)
        return
    }
    when {
        url.containsRtLOverride() -> {
            showMismatchDialog(activity, seenUrl = label.ensureEndsLeftToRight(), actualUrl = url.filterDirectionOverrides(), continueTo = url)
        }
        label.isValidUrl() && url.isValidUrl() && URL(label).host != URL(url).host -> {
            showMismatchDialog(activity, seenUrl = label, actualUrl = url, continueTo = url)
        }
        !label.isValidUrl() && url.isValidUrl() && !url.isExpectedMscLink(label) &&
                url != "http://$label" && url != "https://$label" -> {
            showDestinationDialog(activity, url)
        }
        else -> openUrlInExternalBrowser(activity, url)
    }
}

private fun showMismatchDialog(activity: Activity, seenUrl: String, actualUrl: String, continueTo: String) {
    val highlight = MaterialColors.getColor(activity, im.vector.lib.ui.styles.R.attr.vctr_content_tertiary, 0)
    MaterialAlertDialogBuilder(activity, im.vector.lib.ui.styles.R.style.ThemeOverlay_Vector_MaterialAlertDialog_NegativeDestructive)
            .setTitle(CommonStrings.external_link_confirmation_title)
            .setMessage(
                    activity.getString(CommonStrings.external_link_confirmation_message, seenUrl, actualUrl)
                            .toSpannable()
                            .colorizeMatchingText(actualUrl, highlight)
                            .colorizeMatchingText(seenUrl, highlight)
            )
            .setPositiveButton(CommonStrings._continue) { _, _ -> openUrlInExternalBrowser(activity, continueTo) }
            .setNegativeButton(CommonStrings.action_cancel, null)
            .show()
}

private fun showDestinationDialog(activity: Activity, url: String) {
    MaterialAlertDialogBuilder(activity)
            .setTitle(CommonStrings.external_link_destination_title)
            .setMessage(activity.getString(CommonStrings.external_link_destination_message, url))
            .setPositiveButton(CommonStrings._continue) { _, _ -> openUrlInExternalBrowser(activity, url) }
            .setNegativeButton(CommonStrings.action_cancel, null)
            .show()
}

private fun Context.findActivity(): Activity? =
        generateSequence(this) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
