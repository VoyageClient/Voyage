package de.spiritcroc.matrixsdk

/**
 * Lets the app expose a few preferences directly to the SDK without threading them
 * through the many indirections of the Element session/config code.
 */
object StaticScSdkHelper {

    var scSdkPreferenceProvider: ScSdkPreferenceProvider? = null

    interface ScSdkPreferenceProvider {
        fun includeSpaceMembersAsSpaceRooms(): Boolean
    }
}
