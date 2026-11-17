package com.example.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build

/**
 * Runtime Camera2 CameraCharacteristics & SoC telemetry inspector.
 * Reports factual hardware capabilities from Android OS instead of static assumptions.
 */
object CameraDiagnostics {

    data class CameraLensReport(
        val cameraId: String,
        val lensFacing: String,
        val hardwareLevel: String,
        val pixelArray: String,
        val activeArray: String,
        val maxJpegSize: String,
        val maxYuvSize: String,
        val minFocusDiopters: String,
        val aeAwbLock: String,
        val flashAvailable: Boolean,
        val ultraHighResSupported: Boolean
    )

    data class HardwareDiagnosticReport(
        val manufacturer: String,
        val model: String,
        val device: String,
        val boardHardware: String,
        val socModel: String,
        val androidRelease: String,
        val sdkInt: Int,
        val boundUseCasesSummary: String,
        val lenses: List<CameraLensReport>
    )

    fun inspect(context: Context, boundUseCasesSummary: String = "N/A"): HardwareDiagnosticReport {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }
        val lenses = mutableListOf<CameraLensReport>()
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cm != null) {
                for (id in cm.cameraIdList) {
                    val chars = cm.getCameraCharacteristics(id)
                    val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN"
                    }
                    val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN"
                    }
                    val px = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val maxJpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                    val maxYuv = map?.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width * it.height }
                    val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    val aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) ?: false
                    val awbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false
                    val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                    val ultraRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
                    } else {
                        false
                    }

                    val minFocusStr = if (minFocus > 0f) {
                        val cmDist = 100f / minFocus
                        String.format(java.util.Locale.US, "%.1fD (~%.1fсм)", minFocus, cmDist)
                    } else {
                        "0.0D (fixed)"
                    }

                    lenses.add(
                        CameraLensReport(
                            cameraId = id,
                            lensFacing = facing,
                            hardwareLevel = hwLevel,
                            pixelArray = px?.let { "${it.width}x${it.height}" } ?: "?",
                            activeArray = active?.let { "${it.width()}x${it.height()}" } ?: "?",
                            maxJpegSize = maxJpeg?.let { "${it.width}x${it.height}" } ?: "?",
                            maxYuvSize = maxYuv?.let { "${it.width}x${it.height}" } ?: "?",
                            minFocusDiopters = minFocusStr,
                            aeAwbLock = "AE=${if (aeLock) "Y" else "N"}/AWB=${if (awbLock) "Y" else "N"}",
                            flashAvailable = flash,
                            ultraHighResSupported = ultraRes
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }

        return HardwareDiagnosticReport(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            boardHardware = Build.HARDWARE,
            socModel = soc,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            boundUseCasesSummary = boundUseCasesSummary,
            lenses = lenses
        )
    }

    fun buildTextReport(context: Context, boundUseCasesSummary: String = "N/A"): String {
        val r = inspect(context, boundUseCasesSummary)
        return buildString {
            appendLine("Device: ${r.manufacturer} ${r.model} (${r.device})")
            appendLine("SoC: ${r.socModel} • Board: ${r.boardHardware} • Android ${r.androidRelease} (API ${r.sdkInt})")
            appendLine("CameraX Bound: ${r.boundUseCasesSummary}")
            if (r.lenses.isEmpty()) {
                appendLine("Camera2: нет доступных камер или нет разрешения")
            } else {
                for (l in r.lenses) {
                    appendLine(
                        "ID ${l.cameraId} [${l.lensFacing}] HAL=${l.hardwareLevel} • px=${l.pixelArray} • JPEG=${l.maxJpegSize} • YUV=${l.maxYuvSize} • AF=${l.minFocusDiopters} • ${l.aeAwbLock} • UltraHiRes=${l.ultraHighResSupported}"
                    )
                }
            }
        }.trim()
    }
}
