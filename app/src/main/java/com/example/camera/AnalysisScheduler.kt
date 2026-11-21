package com.example.camera

import com.example.visor.VisorMode

/**
 * AnalysisScheduler (Part B):
 * Coordinates CameraX ImageAnalysis workloads so Helio G85 CPU cores never get saturated:
 * - Single-flight atomic execution guard with generation token on visor/camera switches.
 * - Thermal-throttled slow CV interval with round-robin task selection (sharpness+exposure,
 *   focus peaking, sentinel motion, and GPU visor telemetry).
 * - Grants priority frame rate to [VisorMode.BIO_RPPG] and [VisorMode.WIREFRAME_POSE].
 */
class AnalysisScheduler {

    enum class SubTask {
        SHARPNESS_EXPOSURE,
        PEAKING,
        SENTINEL_MOTION,
        VISOR_TELEMETRY
    }

    data class FrameDispatchPlan(
        val generation: Long,
        val runSharpnessAndExpo: Boolean,
        val runCpuPeaking: Boolean,
        val runSentinel: Boolean,
        val runVisorTelemetry: Boolean,
        val runVisorDehazeLut: Boolean
    )

    @Volatile
    private var currentGeneration: Long = 1L

    @Volatile
    private var busy: Boolean = false

    private var lastSlowTickMs: Long = 0L
    private var roundRobinCursor: Int = 0

    fun bumpGeneration(): Long {
        val next = currentGeneration + 1L
        currentGeneration = next
        return next
    }

    fun getGeneration(): Long = currentGeneration

    fun tryBeginFrame(): Long? {
        synchronized(this) {
            if (busy) return null
            busy = true
            return currentGeneration
        }
    }

    fun endFrame() {
        synchronized(this) {
            busy = false
        }
    }

    fun isGenerationCurrent(gen: Long): Boolean = (gen == currentGeneration)

    fun planFrameWork(
        nowMs: Long,
        thermalIntervalMs: Long,
        visorMode: VisorMode,
        useGpuShader: Boolean,
        needExpo: Boolean,
        needPeaking: Boolean,
        needSentinel: Boolean,
        stealthBlackoutActive: Boolean
    ): FrameDispatchPlan {
        val gen = currentGeneration
        val slowDue = (nowMs - lastSlowTickMs) >= thermalIntervalMs.coerceAtLeast(80L)

        // When thermal throttling tightens (>= 250ms), alternate non-critical tasks cleanly across slow ticks
        val slot = roundRobinCursor
        if (slowDue) {
            lastSlowTickMs = nowMs
            roundRobinCursor = (roundRobinCursor + 1) and 0x7FFFFFFF
        }

        val throttledSpread = thermalIntervalMs >= 250L
        val runSharpExpo = slowDue && (!throttledSpread || (slot % 2 == 0) || needExpo)
        val runCpuPeak = slowDue && needPeaking && (!useGpuShader) && (!throttledSpread || (slot % 2 == 1))
        val runSent = slowDue && needSentinel

        val priorityHighRateVisor = (visorMode == VisorMode.BIO_RPPG || visorMode == VisorMode.WIREFRAME_POSE)
        val runTelemetry = (visorMode != VisorMode.NORMAL && !stealthBlackoutActive && useGpuShader) &&
            (priorityHighRateVisor || slowDue)
        val runDehaze = slowDue && useGpuShader && (visorMode == VisorMode.DEHAZE_LIVE) && !stealthBlackoutActive

        return FrameDispatchPlan(
            generation = gen,
            runSharpnessAndExpo = runSharpExpo,
            runCpuPeaking = runCpuPeak,
            runSentinel = runSent,
            runVisorTelemetry = runTelemetry,
            runVisorDehazeLut = runDehaze
        )
    }
}
