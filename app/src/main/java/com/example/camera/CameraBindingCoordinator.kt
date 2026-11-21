package com.example.camera

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.example.visor.VisorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Isolated tab identities so overlay flags and CameraX Extension eligibility
 * stay strictly scoped and never leak across modes.
 */
enum class CameraTab(val routeKey: String) {
    VIDEO("VIDEO"),
    PHOTO("PHOTO"),
    NIGHT("NIGHT"),
    VISOR("VISOR"),
    SENTINEL("SENTINEL"),
    DOCUMENTS("DOCUMENTS"),
    STEALTH("STEALTH");

    companion object {
        fun fromKey(key: String): CameraTab =
            values().firstOrNull { it.routeKey == key } ?: PHOTO
    }
}

/**
 * Single source of truth for viewfinder overlay visibility across tabs.
 */
data class CameraUiState(
    val activeTab: CameraTab = CameraTab.PHOTO,
    val visorMode: VisorMode = VisorMode.NORMAL,
    val isDocumentModeActive: Boolean = false,
    val isRecordingVideo: Boolean = false
) {
    val showDocumentLevel: Boolean
        get() = activeTab == CameraTab.DOCUMENTS && isDocumentModeActive && !isRecordingVideo

    val showTacticalReticle: Boolean
        get() = !isRecordingVideo && activeTab != CameraTab.DOCUMENTS && visorMode == VisorMode.NORMAL
}

/**
 * Dynamic thermal throttling controller for MediaTek MT6769Z (Helio G85).
 * Listens to Android PowerManager thermal status (API 29+) and backs off slow
 * ImageAnalysis CV passes to preserve frame rate and avoid SoC throttling.
 */
object ThermalThrottleController {
    const val BASE_ANALYSIS_INTERVAL_MS = 230L
    const val LIGHT_ANALYSIS_INTERVAL_MS = 350L
    const val MODERATE_ANALYSIS_INTERVAL_MS = 650L
    const val SEVERE_ANALYSIS_INTERVAL_MS = 1400L
    const val CRITICAL_ANALYSIS_INTERVAL_MS = 1800L

    fun computeAnalysisIntervalMs(thermalStatus: Int): Long = when {
        thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> CRITICAL_ANALYSIS_INTERVAL_MS
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> SEVERE_ANALYSIS_INTERVAL_MS
        thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> MODERATE_ANALYSIS_INTERVAL_MS
        thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT -> LIGHT_ANALYSIS_INTERVAL_MS
        else -> BASE_ANALYSIS_INTERVAL_MS
    }

    class ThermalMonitor(context: Context) {
        private val appContext = context.applicationContext
        private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        private var listener: Any? = null

        @Volatile
        var currentThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
            private set

        @Volatile
        var currentIntervalMs: Long = BASE_ANALYSIS_INTERVAL_MS
            private set

        fun start() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val pm = powerManager ?: return
            if (listener != null) return
            val initialStatus = try {
                pm.currentThermalStatus
            } catch (_: Throwable) {
                PowerManager.THERMAL_STATUS_NONE
            }
            currentThermalStatus = initialStatus
            currentIntervalMs = computeAnalysisIntervalMs(initialStatus)
            val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                currentThermalStatus = status
                currentIntervalMs = computeAnalysisIntervalMs(status)
            }
            listener = thermalListener
            try {
                pm.addThermalStatusListener(thermalListener)
            } catch (_: Throwable) {
                listener = null
            }
        }

        fun stop() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val pm = powerManager ?: return
            val active = listener as? PowerManager.OnThermalStatusChangedListener ?: return
            listener = null
            try {
                pm.removeThermalStatusListener(active)
            } catch (_: Throwable) {}
        }
    }
}

/**
 * Coordinates CameraX lifecycle binding, generation-guarded rebinds, optional
 * CameraX Extensions (strictly NIGHT still mode only), and a 1500 ms first-frame
 * watchdog that cleanly downgrades to base CameraSelector if a vendor extension stalls.
 */
class CameraBindingCoordinator(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val generation = AtomicLong(0L)
    private val firstFrameArrived = AtomicBoolean(false)

    @Volatile
    private var watchdogJob: Job? = null

    @Volatile
    private var cachedExtensionsManager: ExtensionsManager? = null

    data class BindOutcome(
        val camera: Camera,
        val activeUseCasesSummary: String,
        val extensionFallbackUsed: Boolean,
        val activeImageAnalysis: ImageAnalysis?
    )

    fun markFirstFrameReceived() {
        if (firstFrameArrived.compareAndSet(false, true)) {
            watchdogJob?.cancel()
            watchdogJob = null
        }
    }

    fun cancelPendingWatchdog() {
        generation.incrementAndGet()
        watchdogJob?.cancel()
        watchdogJob = null
    }

    suspend fun bindPhotoOrMode(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        lensFacing: Int,
        tab: CameraTab,
        visorMode: VisorMode,
        useGpuVisorPipeline: Boolean,
        preview: Preview,
        imageCapture: ImageCapture,
        imageAnalysis: ImageAnalysis?,
        onFirstFrameWatchdogFallback: suspend (BindOutcome) -> Unit,
        onFatalBindError: (Exception) -> Unit
    ): BindOutcome? {
        val bindGen = generation.incrementAndGet()
        watchdogJob?.cancel()
        watchdogJob = null
        firstFrameArrived.set(false)

        val baseSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val tryNightExtension = isExtensionEligibleTab(tab, visorMode, useGpuVisorPipeline)
        var selectedSelector = baseSelector
        var activeExtLabel: String? = null

        if (tryNightExtension) {
            val extSelector = resolveNightExtensionSelectorOrNull(cameraProvider, baseSelector)
            if (bindGen != generation.get()) return null
            if (extSelector != null) {
                selectedSelector = extSelector
                activeExtLabel = "EXT:NIGHT"
            }
        }

        val outcome = tryBindWithFallbackAnalysis(
            cameraProvider = cameraProvider,
            lifecycleOwner = lifecycleOwner,
            selector = selectedSelector,
            preview = preview,
            imageCapture = imageCapture,
            imageAnalysis = imageAnalysis,
            extLabel = activeExtLabel,
            fallbackUsed = false
        )

        if (outcome != null) {
            if (activeExtLabel != null && !firstFrameArrived.get()) {
                watchdogJob = scope.launch(Dispatchers.Main) {
                    delay(FIRST_FRAME_WATCHDOG_MS)
                    if (bindGen == generation.get() && !firstFrameArrived.get()) {
                        val fallbackOutcome = tryBindWithFallbackAnalysis(
                            cameraProvider = cameraProvider,
                            lifecycleOwner = lifecycleOwner,
                            selector = baseSelector,
                            preview = preview,
                            imageCapture = imageCapture,
                            imageAnalysis = imageAnalysis,
                            extLabel = null,
                            fallbackUsed = true
                        )
                        if (fallbackOutcome != null && bindGen == generation.get()) {
                            onFirstFrameWatchdogFallback(fallbackOutcome)
                        }
                    }
                }
            }
            return outcome
        }

        // If extension bind itself threw, retry once immediately with baseSelector (non-reentrant)
        if (activeExtLabel != null) {
            val baseOutcome = try {
                tryBindWithFallbackAnalysis(
                    cameraProvider = cameraProvider,
                    lifecycleOwner = lifecycleOwner,
                    selector = baseSelector,
                    preview = preview,
                    imageCapture = imageCapture,
                    imageAnalysis = imageAnalysis,
                    extLabel = null,
                    fallbackUsed = true
                )
            } catch (e: Exception) {
                onFatalBindError(e)
                null
            }
            if (baseOutcome != null) return baseOutcome
        }

        try {
            cameraProvider.unbindAll()
            val cam = cameraProvider.bindToLifecycle(lifecycleOwner, baseSelector, preview, imageCapture)
            return BindOutcome(
                camera = cam,
                activeUseCasesSummary = "FALLBACK (без ImageAnalysis): Preview + ImageCapture",
                extensionFallbackUsed = activeExtLabel != null,
                activeImageAnalysis = null
            )
        } catch (e: Exception) {
            onFatalBindError(e)
            return null
        }
    }

    private fun tryBindWithFallbackAnalysis(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        selector: CameraSelector,
        preview: Preview,
        imageCapture: ImageCapture,
        imageAnalysis: ImageAnalysis?,
        extLabel: String?,
        fallbackUsed: Boolean
    ): BindOutcome? {
        val useCasesWithAnalysis = ArrayList<UseCase>(3).apply {
            add(preview)
            add(imageCapture)
            if (imageAnalysis != null) add(imageAnalysis)
        }.toTypedArray()

        return try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, *useCasesWithAnalysis)
            val prefix = extLabel?.let { "[$it] " } ?: if (fallbackUsed) "[BASE FALLBACK] " else ""
            val summary = if (imageAnalysis != null) {
                "${prefix}PHOTO/OPTIC: Preview + ImageCapture + ImageAnalysis (YUV 640x480)"
            } else {
                "${prefix}PHOTO: Preview + ImageCapture"
            }
            BindOutcome(
                camera = camera,
                activeUseCasesSummary = summary,
                extensionFallbackUsed = fallbackUsed,
                activeImageAnalysis = imageAnalysis
            )
        } catch (_: Exception) {
            if (imageAnalysis == null) return null
            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                val prefix = extLabel?.let { "[$it] " } ?: if (fallbackUsed) "[BASE FALLBACK] " else ""
                BindOutcome(
                    camera = camera,
                    activeUseCasesSummary = "${prefix}FALLBACK (без ImageAnalysis): Preview + ImageCapture",
                    extensionFallbackUsed = fallbackUsed,
                    activeImageAnalysis = null
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun resolveNightExtensionSelectorOrNull(
        cameraProvider: ProcessCameraProvider,
        baseSelector: CameraSelector
    ): CameraSelector? {
        return try {
            val mgr = cachedExtensionsManager ?: withContext(Dispatchers.IO) {
                ExtensionsManager.getInstanceAsync(appContext, cameraProvider).get()
            }.also { cachedExtensionsManager = it }

            when {
                mgr.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT) ->
                    mgr.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)
                mgr.isExtensionAvailable(baseSelector, ExtensionMode.AUTO) ->
                    mgr.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.AUTO)
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val FIRST_FRAME_WATCHDOG_MS = 1500L

        /**
         * ExtensionsManager is strictly restricted to Night still capture when GPU shader pipeline is off.
         * Never enabled for VIDEO, VISOR, SENTINEL, or DOCUMENTS to avoid MediaTek HAL stalls.
         */
        fun isExtensionEligibleTab(
            tab: CameraTab,
            visorMode: VisorMode,
            useGpuVisorPipeline: Boolean
        ): Boolean {
            if (useGpuVisorPipeline || visorMode != VisorMode.NORMAL) return false
            return tab == CameraTab.NIGHT
        }
    }
}
