package com.example.camera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Hands-Free Voice Controller for Zver Camera:
 * Operates strictly locally using on-device SpeechRecognizer.
 * Requires zero root rights.
 */
class VoiceControlManager(
    private val context: Context,
    private val onCommandRecognized: (VoiceCommand) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {
    enum class VoiceCommand {
        CAPTURE,
        ZOOM_IN,
        ZOOM_MAX,
        ZOOM_RESET,
        TOGGLE_TORCH,
        TORCH_ON,
        TORCH_OFF,
        TIMER_5,
        TIMER_OFF,
        TOGGLE_STEALTH,
        OPEN_VAULT,
        FLIP_CAMERA
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val scope = CoroutineScope(Dispatchers.Main)
    private var restartJob: Job? = null

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening() {
        if (isListening) return
        if (!isAvailable()) {
            onStatusUpdate("Голосовое управление недоступно на устройстве")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
            startIntent()
            isListening = true
            onStatusUpdate("🎙️ Голосовой режим: скажи «Снять» или «Приблизить»")
        } catch (e: Exception) {
            onStatusUpdate("Ошибка микрофона: ${e.message}")
        }
    }

    fun stopListening() {
        isListening = false
        restartJob?.cancel()
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (_: Exception) {}
        onStatusUpdate("🎙️ Голосовой режим отключен")
    }

    private fun startIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {}
    }

    private fun parseWords(matches: List<String>) {
        for (raw in matches) {
            val lower = raw.lowercase(Locale.ROOT)
            when {
                // Съемка (Сделай снимок, сфоткай, фото, огонь)
                lower.contains("снять") || lower.contains("снимок") || lower.contains("сфотк") || lower.contains("фотк") ||
                lower.contains("фото") || lower.contains("огонь") || lower.contains("кадр") || lower.contains("чик") -> {
                    onCommandRecognized(VoiceCommand.CAPTURE)
                    return
                }
                // Максимальный зум / приблизь к лицу / максимум
                lower.contains("максимум") || lower.contains("максимальн") || lower.contains("к лицу") || lower.contains("к лицам") ||
                lower.contains("сильно") || lower.contains("три") || lower.contains("х3") || lower.contains("3x") -> {
                    onCommandRecognized(VoiceCommand.ZOOM_MAX)
                    return
                }
                // Приблизить / зум 2
                lower.contains("приблиз") || lower.contains("зум") || lower.contains("ближе") || lower.contains("увелич") ||
                lower.contains("два") || lower.contains("х2") || lower.contains("2x") -> {
                    onCommandRecognized(VoiceCommand.ZOOM_IN)
                    return
                }
                // Сброс зума / исходное / уменьшить
                lower.contains("сброс") || lower.contains("исходн") || lower.contains("отдалить") || lower.contains("уменьш") ||
                lower.contains("назад") || lower.contains("один") || lower.contains("х1") || lower.contains("1x") -> {
                    onCommandRecognized(VoiceCommand.ZOOM_RESET)
                    return
                }
                // Включить фонарик / свет
                lower.contains("включи фонар") || lower.contains("вруби фонар") || lower.contains("включи свет") || lower.contains("подсветк") -> {
                    onCommandRecognized(VoiceCommand.TORCH_ON)
                    return
                }
                // Выключить фонарик
                lower.contains("выключи фонар") || lower.contains("потуши") || lower.contains("выруби фонар") || lower.contains("выключи свет") -> {
                    onCommandRecognized(VoiceCommand.TORCH_OFF)
                    return
                }
                // Фонарик переключить
                lower.contains("фонар") || lower.contains("свет") || lower.contains("вспышк") -> {
                    onCommandRecognized(VoiceCommand.TOGGLE_TORCH)
                    return
                }
                // Таймер
                lower.contains("таймер") || lower.contains("5 секунд") || lower.contains("пять секунд") -> {
                    onCommandRecognized(VoiceCommand.TIMER_5)
                    return
                }
                // Стелс / темнота
                lower.contains("стелс") || lower.contains("темнот") || lower.contains("черн") || lower.contains("час") || lower.contains("спрячь") -> {
                    onCommandRecognized(VoiceCommand.TOGGLE_STEALTH)
                    return
                }
                // Сейф / схрон
                lower.contains("сейф") || lower.contains("схрон") || lower.contains("хранилищ") -> {
                    onCommandRecognized(VoiceCommand.OPEN_VAULT)
                    return
                }
                // Перевернуть камеру
                lower.contains("переверни") || lower.contains("селфи") || lower.contains("фронтал") -> {
                    onCommandRecognized(VoiceCommand.FLIP_CAMERA)
                    return
                }
            }
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (isListening) {
                restartJob?.cancel()
                restartJob = scope.launch {
                    delay(800)
                    if (isListening) startIntent()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                parseWords(matches)
            }
            if (isListening) {
                restartJob?.cancel()
                restartJob = scope.launch {
                    delay(300)
                    if (isListening) startIntent()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                parseWords(matches)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
