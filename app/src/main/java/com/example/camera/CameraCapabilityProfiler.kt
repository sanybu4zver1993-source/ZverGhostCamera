package com.example.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import androidx.camera.core.CameraSelector
import java.util.Locale

/**
 * CameraCapabilityProfiler:
 * Inspects factual Camera2 HAL capabilities for the active lens facing (BACK / FRONT),
 * including ISO range, shutter exposure range, EV step/range, manual focus diopters,
 * AWB modes, digital zoom cap, and MediaTek/Helio-safe stream guidance.
 */
object CameraCapabilityProfiler {

    enum class AwbPreset(val labelRu: String, val camera2Mode: Int) {
        AUTO("Авто", CameraMetadata.CONTROL_AWB_MODE_AUTO),
        DAYLIGHT("Солнце", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
        CLOUDY("Облака", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
        INCANDESCENT("Лампа", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
        FLUORESCENT("Люминесц.", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT)
    }

    data class CameraProProfile(
        val cameraId: String,
        val lensFacing: Int,
        val hardwareLevelName: String,
        val socModel: String,
        val isoRange: IntRange?,
        val exposureTimeNsRange: LongRange?,
        val minFocusDiopters: Float,
        val isManualFocusSupported: Boolean,
        val evRange: IntRange,
        val evStep: Float,
        val maxZoomRatio: Float,
        val supportsManualSensorCapability: Boolean,
        val supportsManualExposure: Boolean,
        val supportsAeLock: Boolean,
        val supportsAwbLock: Boolean,
        val availableAwbModes: List<Int>,
        val maxJpegRes: String,
        val maxYuvRes: String,
        val ultraHighResSupported: Boolean,
        val halSafetyNoteRu: String
    ) {
        companion object {
            fun fallback(lensFacing: Int): CameraProProfile = CameraProProfile(
                cameraId = "?",
                lensFacing = lensFacing,
                hardwareLevelName = "UNKNOWN",
                socModel = Build.HARDWARE,
                isoRange = null,
                exposureTimeNsRange = null,
                minFocusDiopters = 0f,
                isManualFocusSupported = false,
                evRange = -4..4,
                evStep = 0.5f,
                maxZoomRatio = 4.0f,
                supportsManualSensorCapability = false,
                supportsManualExposure = false,
                supportsAeLock = false,
                supportsAwbLock = false,
                availableAwbModes = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO),
                maxJpegRes = "?",
                maxYuvRes = "?",
                ultraHighResSupported = false,
                halSafetyNoteRu = "Профиль HAL недоступен (нет доступа к CameraManager)"
            )
        }
    }

    fun profileCamera(context: Context, lensFacing: Int): CameraProProfile {
        val targetC2Facing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }

        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return CameraProProfile.fallback(lensFacing)

            var selectedId: String? = null
            var selectedChars: CameraCharacteristics? = null
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == targetC2Facing) {
                    selectedId = id
                    selectedChars = chars
                    break
                }
            }
            val chars = selectedChars ?: return CameraProProfile.fallback(lensFacing)
            val camId = selectedId ?: "0"

            val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val hwName = when (hwLevel) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "UNKNOWN"
            }

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val hasManualSensorCap = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper }
            val expNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper }
            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val supportsManualFocus = minFocus > 0.05f &&
                (chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                    ?.contains(CameraMetadata.CONTROL_AF_MODE_OFF) ?: false)

            val evR = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val evRange = if (evR != null && evR.lower < evR.upper) evR.lower..evR.upper else -2..2
            val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.5f

            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4.0f
            val aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
            val awbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false
            val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList()
                ?: listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val maxJpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
            val maxYuv = map?.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width * it.height }
            val ultraRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
            } else {
                false
            }

            val supportsManualExposure = hasManualSensorCap && iso != null && expNs != null
            val safetyNote = when {
                hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY ->
                    "HAL LEGACY: ручные ISO/выдержка ограничены прошивкой, используйте EV/AE-Lock и раздельные потоки"
                !supportsManualExposure ->
                    "HAL $hwName: нет полного MANUAL_SENSOR (используются EV, WB, MF, AE-L); видео изолировано"
                else ->
                    "HAL $hwName: доступен полный MANUAL_SENSOR (ISO/S/MF/WB/EV)"
            }

            CameraProProfile(
                cameraId = camId,
                lensFacing = lensFacing,
                hardwareLevelName = hwName,
                socModel = soc,
                isoRange = iso,
                exposureTimeNsRange = expNs,
                minFocusDiopters = minFocus,
                isManualFocusSupported = supportsManualFocus,
                evRange = evRange,
                evStep = evStep,
                maxZoomRatio = maxZoom.coerceAtLeast(1.0f),
                supportsManualSensorCapability = hasManualSensorCap,
                supportsManualExposure = supportsManualExposure,
                supportsAeLock = aeLock,
                supportsAwbLock = awbLock,
                availableAwbModes = awbModes,
                maxJpegRes = maxJpeg?.let { "${it.width}x${it.height}" } ?: "?",
                maxYuvRes = maxYuv?.let { "${it.width}x${it.height}" } ?: "?",
                ultraHighResSupported = ultraRes,
                halSafetyNoteRu = safetyNote
            )
        } catch (_: Exception) {
            CameraProProfile.fallback(lensFacing)
        }
    }

    fun formatShutterSpeed(ns: Long): String {
        return when {
            ns >= 1_000_000_000L -> String.format(Locale.US, "%.1fs", ns / 1_000_000_000.0)
            ns >= 100_000_000L -> String.format(Locale.US, "1/%.0f", 1_000_000_000.0 / ns)
            ns > 0L -> "1/${(1_000_000_000.0 / ns).toInt().coerceAtLeast(1)}"
            else -> "Авто"
        }
    }

    fun formatEvStops(exposureIndex: Int, evStep: Float): String {
        if (exposureIndex == 0) return "EV 0.0"
        val stops = exposureIndex * evStep
        return String.format(Locale.US, "EV %+.1f", stops)
    }

    fun evIndexForStops(targetStops: Float, evStep: Float, evRange: IntRange): Int {
        if (evStep <= 0f) return 0
        val idx = kotlin.math.round(targetStops / evStep).toInt()
        return idx.coerceIn(evRange.first, evRange.last)
    }
}
