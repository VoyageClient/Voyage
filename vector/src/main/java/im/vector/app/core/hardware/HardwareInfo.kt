/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("DEPRECATION")

package im.vector.app.core.hardware

import android.content.Context
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.core.content.getSystemService
import javax.inject.Inject

class HardwareInfo @Inject constructor(
        private val context: Context
) {
    /**
     * Tell if the device has a back (or external) camera.
     */
    fun hasBackCamera(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return hasBackCameraLegacy()
        val manager = context.getSystemService<CameraManager>() ?: return hasBackCameraLegacy()

        return manager.cameraIdList.any {
            val lensFacing = manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)
            lensFacing == CameraCharacteristics.LENS_FACING_BACK || lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL
        }
    }

    // camera2 is API 21+; the legacy android.hardware.Camera API works back to API 9.
    private fun hasBackCameraLegacy(): Boolean {
        for (i in 0 until Camera.getNumberOfCameras()) {
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return true
        }
        return false
    }
}
