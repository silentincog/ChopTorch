package dev.incog.choptorch

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Flashlight control through the public Camera2 torch API (no CAMERA permission needed). */
class Torch(context: Context, private val onChange: ((Boolean) -> Unit)? = null) {
    private val cm = context.getSystemService(CameraManager::class.java)
    private val cameraId: String? = findFlashCamera()

    @Volatile var isOn = false
        private set

    /** When the torch last became unavailable (another app opened the camera), elapsed-realtime ms. */
    @Volatile var unavailableAt = Long.MIN_VALUE / 2
        private set

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == cameraId) {
                isOn = enabled
                onChange?.invoke(enabled)
            }
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id == cameraId) unavailableAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    val available get() = cameraId != null

    fun start() = cm.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
    fun stop() = cm.unregisterTorchCallback(callback)

    /** @return the new state, or null if the torch could not be switched (e.g. camera busy). */
    fun toggle(): Boolean? = set(!isOn)

    /** @return the new state, or null if the torch could not be switched. */
    fun set(on: Boolean): Boolean? {
        val id = cameraId ?: return null
        return try {
            cm.setTorchMode(id, on)
            isOn = on
            on
        } catch (e: Exception) {
            Log.w("ChopTorch", "setTorchMode failed", e)
            null
        }
    }

    private fun findFlashCamera(): String? = try {
        val ids = cm.cameraIdList
        ids.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (e: Exception) {
        Log.w("ChopTorch", "camera lookup failed", e)
        null
    }
}
