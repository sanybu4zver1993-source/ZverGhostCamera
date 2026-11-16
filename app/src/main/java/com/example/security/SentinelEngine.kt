package com.example.security

import java.util.UUID

/**
 * SentinelEngine ("APEX SENTINEL v5.0" Edge Guard):
 * Ported from ESP32 sketch.ino + Python sentinel_brain.py directly onto on-device sensors:
 * 1. Linger Filter ("Отсечка соседа"): fleeting hallway motion < [lingerThresholdSec] is ignored.
 * 2. Vibration Knock Trigger: sudden tap on door/shelf fires instant capture even before motion lingers.
 * 3. Day/Night Auto-Profile: real Y-plane ambient luma selects DAY-HDR vs NIGHT-CLAHE Anti-Glare.
 * 4. Anti-Spam Cooldown: avoids flooding vault/API while target lingers.
 * 5. Strictly RAM-only event log (wipes cleanly on lock/shred/exit).
 */
object SentinelEngine {

    enum class GuardPhase(val labelRu: String) {
        IDLE("ОЖИДАНИЕ"),
        ARMED("НА ПОСТУ"),
        TRACKING("ЦЕЛЬ (ФИЛЬТР...)"),
        COOLDOWN("ПАУЗА (АНТИ-СПАМ)")
    }

    enum class EventKind(val tagRu: String) {
        INFO("ИНФО"),
        IGNORED("СОСЕД [ИГНОР]"),
        LINGER_ALARM("ТРЕВОГА: ЗАВИСАНИЕ"),
        KNOCK_ALARM("ТРЕВОГА: СТУК / УДАР"),
        AI_VERDICT("ВИКА ИИ")
    }

    data class SentinelConfig(
        val lingerThresholdSec: Int = 4,
        val cooldownSec: Int = 25,
        val motionPercentThreshold: Int = 8,
        val knockTriggerEnabled: Boolean = true,
        val autoNightClaheEnabled: Boolean = true,
        val aiTriageEnabled: Boolean = false,
        val twoStageFaceCropEnabled: Boolean = true
    )

    data class SentinelEvent(
        val id: String = UUID.randomUUID().toString().take(8),
        val timestampMs: Long = System.currentTimeMillis(),
        val kind: EventKind,
        val message: String,
        val facesCount: Int = 0
    )

    sealed class SentinelStepResult {
        data object Idle : SentinelStepResult()
        data class Tracking(val elapsedMs: Long, val motionScore: Int) : SentinelStepResult()
        data class NeighborIgnored(val durationMs: Long) : SentinelStepResult()
        data class TriggerLingerCapture(val durationMs: Long, val nightClahe: Boolean) : SentinelStepResult()
        data class Cooldown(val remainingSec: Int) : SentinelStepResult()
    }

    private val lock = Any()
    private var armed: Boolean = false
    private var config = SentinelConfig()
    private var motionStartMs: Long = 0L
    private var lastTriggerMs: Long = 0L
    private var lastMotionSeenMs: Long = 0L
    private var isNightProfile: Boolean = false
    private val events = mutableListOf<SentinelEvent>()

    fun getConfig(): SentinelConfig = synchronized(lock) { config }

    fun updateConfig(transform: (SentinelConfig) -> SentinelConfig): SentinelConfig = synchronized(lock) {
        config = transform(config)
        config
    }

    fun isArmed(): Boolean = synchronized(lock) { armed }

    fun isNightProfileActive(): Boolean = synchronized(lock) { isNightProfile }

    fun setArmed(enable: Boolean, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            armed = enable
            motionStartMs = 0L
            lastMotionSeenMs = 0L
            if (enable) {
                lastTriggerMs = 0L
                addEventLocked(EventKind.INFO, "Дозор активирован (порог ${config.lingerThresholdSec}с)", nowMs)
            } else {
                addEventLocked(EventKind.INFO, "Дозор снят с охраны", nowMs)
            }
        }
    }

    fun getEvents(): List<SentinelEvent> = synchronized(lock) { events.toList() }

    fun logEvent(kind: EventKind, message: String, facesCount: Int = 0, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            addEventLocked(kind, message, nowMs, facesCount)
        }
    }

    fun clearSession() {
        synchronized(lock) {
            armed = false
            motionStartMs = 0L
            lastTriggerMs = 0L
            lastMotionSeenMs = 0L
            events.clear()
        }
    }

    fun evaluateMotionFrame(
        nowMs: Long,
        motionScore: Int,
        avgLuma: Int
    ): SentinelStepResult = synchronized(lock) {
        val nextNight = avgLuma < 60
        if (nextNight != isNightProfile) {
            isNightProfile = nextNight
            if (armed) {
                addEventLocked(
                    EventKind.INFO,
                    if (nextNight) "Профиль НОЧЬ: CLAHE анти-блик (L=$avgLuma)" else "Профиль ДЕНЬ: HDR база (L=$avgLuma)",
                    nowMs
                )
            }
        }

        if (!armed) return SentinelStepResult.Idle

        val cooldownMs = config.cooldownSec * 1000L
        if (lastTriggerMs > 0L && (nowMs - lastTriggerMs) < cooldownMs) {
            val remSec = (((cooldownMs - (nowMs - lastTriggerMs)) + 999L) / 1000L).toInt().coerceAtLeast(1)
            return SentinelStepResult.Cooldown(remSec)
        }

        val hasMotion = motionScore >= config.motionPercentThreshold
        val lingerMs = config.lingerThresholdSec * 1000L

        if (hasMotion) {
            lastMotionSeenMs = nowMs
            if (motionStartMs == 0L) {
                motionStartMs = nowMs
                return SentinelStepResult.Tracking(0L, motionScore)
            }
            val elapsed = nowMs - motionStartMs
            if (elapsed >= lingerMs) {
                motionStartMs = 0L
                lastTriggerMs = nowMs
                val useClahe = config.autoNightClaheEnabled && isNightProfile
                addEventLocked(
                    EventKind.LINGER_ALARM,
                    "Объект у порога ${elapsed / 1000}.${(elapsed % 1000) / 100}с (${motionScore}% поля)",
                    nowMs
                )
                return SentinelStepResult.TriggerLingerCapture(elapsed, useClahe)
            }
            return SentinelStepResult.Tracking(elapsed, motionScore)
        } else {
            if (motionStartMs > 0L) {
                // Allow brief 900ms micro-pause while someone shifts weight before declaring neighbor gone
                if ((nowMs - lastMotionSeenMs) < 900L) {
                    return SentinelStepResult.Tracking(nowMs - motionStartMs, motionScore)
                }
                val dur = (lastMotionSeenMs - motionStartMs).coerceAtLeast(0L)
                motionStartMs = 0L
                if (dur in 250L until lingerMs) {
                    addEventLocked(
                        EventKind.IGNORED,
                        "Отсечка соседа: проход ${dur / 1000}.${(dur % 1000) / 100}с — тревога отменена",
                        nowMs
                    )
                    return SentinelStepResult.NeighborIgnored(dur)
                }
            }
            return SentinelStepResult.Idle
        }
    }

    fun evaluateKnockImpulse(
        nowMs: Long,
        linearAccelMag: Float
    ): Boolean = synchronized(lock) {
        if (!armed || !config.knockTriggerEnabled) return false
        // Guard against immediate vibration right at arming moment
        val cooldownMs = config.cooldownSec * 1000L
        if (lastTriggerMs > 0L && (nowMs - lastTriggerMs) < cooldownMs) return false

        lastTriggerMs = nowMs
        motionStartMs = 0L
        addEventLocked(
            EventKind.KNOCK_ALARM,
            "Зафиксирован стук/удар (${String.format("%.1f", linearAccelMag)} м/с²)",
            nowMs
        )
        return true
    }

    private fun addEventLocked(
        kind: EventKind,
        message: String,
        nowMs: Long,
        facesCount: Int = 0
    ) {
        events.add(
            0,
            SentinelEvent(
                timestampMs = nowMs,
                kind = kind,
                message = message,
                facesCount = facesCount
            )
        )
        if (events.size > 25) {
            events.removeAt(events.lastIndex)
        }
    }
}
