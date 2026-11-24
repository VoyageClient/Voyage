package de.spiritcroc.matrixsdk

import org.matrix.android.sdk.api.MatrixPatterns

/**
 * Lets the app expose a few preferences directly to the SDK without threading them
 * through the many indirections of the Element session/config code.
 */
object StaticScSdkHelper {

    var scSdkPreferenceProvider: ScSdkPreferenceProvider? = null

    fun mscLinkBaseUrl(): String = scSdkPreferenceProvider?.mscLinkBaseUrl() ?: MatrixPatterns.MSC_GITHUB_BASE_URL

    interface ScSdkPreferenceProvider {
        fun includeSpaceMembersAsSpaceRooms(): Boolean
        fun linkifyMscReferencesOnSend(): Boolean = true
        fun mscLinkBaseUrl(): String = MatrixPatterns.MSC_GITHUB_BASE_URL
        fun preventUnintendedMarkdown(): Boolean = true
    }
}
