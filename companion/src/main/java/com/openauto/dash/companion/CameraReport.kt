package com.openauto.dash.companion

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan

/**
 * What this phone's cameras can give Road watch, for the driver's computer
 * ([ClipServer], GET /cameras): each camera apps can open, its lens and
 * view, its speeds, the physical cameras behind a logical one (wide, ultra
 * wide, zoom), and which cameras may run at the same time.
 */
object CameraReport {
    fun json(context: Context): JSONObject {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameras = JSONArray()
        val seen = mutableSetOf<String>()
        fun add(id: String, physicalOf: String?) {
            if (!seen.add(id)) return
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return
            cameras.put(describe(id, c, physicalOf))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) c.physicalCameraIds.forEach { add(it, id) }
        }
        manager.cameraIdList.forEach { add(it, null) }
        val together = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { manager.concurrentCameraIds }.getOrNull()?.forEach { together.put(JSONArray(it.toList())) }
        }
        return JSONObject()
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.SDK_INT)
            .put("cameras", cameras)
            .put("together", together)
    }

    private fun describe(id: String, c: CameraCharacteristics, physicalOf: String?): JSONObject {
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            else -> "external"
        }
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        // The view across the long side, as Road watch's LeadTracker takes it (hfov).
        val hfov = if (sensor != null && focals.isNotEmpty()) Math.toDegrees(2 * atan(sensor.width / (2.0 * focals.min()))) else null
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList()
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val biggest = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.maxByOrNull { it.width.toLong() * it.height }
        return JSONObject()
            .put("id", id)
            .put("physicalOf", physicalOf)
            .put("facing", facing)
            .put("focalMm", JSONArray(focals))
            .put("hfovDeg", hfov)
            .put("level", c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
            .put("logicalMulti", CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps)
            .put("zoom", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toString() else null)
            .put("fps", JSONArray(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { it.toString() } ?: emptyList<String>()))
            .put("maxYuv", biggest?.let(Size::toString))
            .put("stabilization", JSONArray(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList<Int>()))
    }
}
