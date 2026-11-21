package com.example.ui

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera.CaptureManager
import com.example.security.CamouflageManager
import java.util.concurrent.ExecutorService

private val CyberGreen = Color(0xFF00FF66)
private val CyberRed = Color(0xFFFF1744)
private val CyberMuted = Color(0xFF8FA3B0)
private val CyberSurface = Color(0xFF111820)
private val CyberBorder = Color(0xFF223240)

@Composable
internal fun CameraDialogsLayer(
    context: Context,
    showVaultDialog: Boolean,
    forcePinSetupFlow: Boolean,
    showCamouflageDialog: Boolean,
    currentCamouflage: CamouflageManager.CamouflageMode,
    showSettingsDialog: Boolean,
    boundUseCasesSummary: String,
    showVikaSheet: Boolean,
    vikaSheetInitialTab: VikaSheetTab,
    showPcbLabDialog: Boolean,
    showPanicConfirm: Boolean,
    showSelfTestDialog: Boolean,
    eglRecordableOk: Boolean?,
    imageCapture: ImageCapture?,
    captureExecutor: ExecutorService,
    isDcpDehazeActive: Boolean,
    sentinelIsNight: Boolean,
    onDismissVault: () -> Unit,
    onVaultUpdated: () -> Unit,
    onDismissCamouflage: () -> Unit,
    onSelectCamouflage: (CamouflageManager.CamouflageMode, String) -> Unit,
    onDismissSettings: () -> Unit,
    onStatusMessage: (String) -> Unit,
    onOpenPinSetup: () -> Unit,
    onOpenVikaFromSettings: () -> Unit,
    onOpenSelfTestFromSettings: () -> Unit,
    onDismissSelfTest: () -> Unit,
    onCamouflageChangedFromSettings: (CamouflageManager.CamouflageMode) -> Unit,
    onSelectModeFromSettings: (String) -> Unit,
    onDismissVika: () -> Unit,
    onPcbSavedToVault: (String) -> Unit,
    onDismissPcbLab: () -> Unit,
    onDismissPanic: () -> Unit,
    onExecutePanicShred: () -> Unit
) {
    if (showVaultDialog) {
        GhostVaultDialog(
            forceSetupFlow = forcePinSetupFlow,
            onDismiss = onDismissVault,
            onVaultUpdated = onVaultUpdated
        )
    }

    if (showCamouflageDialog) {
        AlertDialog(
            onDismissRequest = onDismissCamouflage,
            title = {
                Text(
                    "МАСКИРОВКА / КАМУФЛЯЖ",
                    color = CyberGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            },
            text = {
                Column {
                    Text("Выбери фальшивую личину для лаунчера телефона:", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    for (mode in CamouflageManager.CamouflageMode.values()) {
                        val ruName = when (mode) {
                            CamouflageManager.CamouflageMode.ZVER -> "Zver Camera (По умолчанию)"
                            CamouflageManager.CamouflageMode.CALCULATOR -> "Калькулятор"
                            CamouflageManager.CamouflageMode.SYSTEM_TOOLS -> "Системные утилиты"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectCamouflage(mode, ruName) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ruName,
                                color = if (mode == currentCamouflage) CyberGreen else Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                            if (mode == currentCamouflage) {
                                Icon(Icons.Default.Check, contentDescription = "Активно", tint = CyberGreen, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissCamouflage) {
                    Text("ЗАКРЫТЬ", color = CyberMuted, fontFamily = FontFamily.Monospace)
                }
            },
            containerColor = CyberSurface
        )
    }

    if (showSettingsDialog) {
        GhostSettingsDialog(
            onDismiss = onDismissSettings,
            onStatusMessage = onStatusMessage,
            onOpenPinSetup = onOpenPinSetup,
            onOpenVikaSheet = onOpenVikaFromSettings,
            onOpenSelfTest = onOpenSelfTestFromSettings,
            onCamouflageChanged = onCamouflageChangedFromSettings,
            boundUseCasesSummary = boundUseCasesSummary,
            onSelectMode = onSelectModeFromSettings
        )
    }

    if (showSelfTestDialog) {
        SelfTestDialog(
            boundUseCasesSummary = boundUseCasesSummary,
            eglRecordableOk = eglRecordableOk,
            onStatusMessage = onStatusMessage,
            onDismiss = onDismissSelfTest
        )
    }

    if (showVikaSheet) {
        VikaAiSheet(
            initialTab = vikaSheetInitialTab,
            externalBitmap = null,
            externalVideoFile = null,
            onCaptureLiveFrame = {
                val cap = imageCapture ?: return@VikaAiSheet null
                CaptureManager(
                    context = context,
                    imageCapture = cap,
                    captureExecutor = captureExecutor
                ).captureCleanFrameForAi()
            },
            onCaptureTwoStageFrame = { detectCrop, isolateBg ->
                val cap = imageCapture ?: return@VikaAiSheet null
                CaptureManager(
                    context = context,
                    imageCapture = cap,
                    captureExecutor = captureExecutor
                ).captureTwoStageFrameForAi(
                    applyClahe = isDcpDehazeActive || sentinelIsNight,
                    detectFaceCrop = detectCrop,
                    isolateBackground = isolateBg
                )
            },
            onCaptureOfflineBitmap = {
                val cap = imageCapture ?: return@VikaAiSheet null
                CaptureManager(
                    context = context,
                    imageCapture = cap,
                    captureExecutor = captureExecutor
                ).captureBitmapInRamForOfflineCv()
            },
            onStatusMessage = onStatusMessage,
            onDismiss = onDismissVika
        )
    }

    if (showPcbLabDialog) {
        PcbLabDialog(
            initialBitmap = null,
            onCaptureFromCamera = {
                val cap = imageCapture ?: return@PcbLabDialog null
                CaptureManager(
                    context = context,
                    imageCapture = cap,
                    captureExecutor = captureExecutor
                ).captureBitmapInRamForOfflineCv()
            },
            onSavedToVault = onPcbSavedToVault,
            onDismiss = onDismissPcbLab
        )
    }

    if (showPanicConfirm) {
        var shredConfirmCode by remember { mutableStateOf("") }
        val canShred = shredConfirmCode.trim() == "999"
        AlertDialog(
            onDismissRequest = {
                shredConfirmCode = ""
                onDismissPanic()
            },
            title = {
                Text(
                    "ЭКСТРЕННЫЙ ШРЕДЕР СХРОНА",
                    color = CyberRed,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Мгновенно затирает весь контейнер случайным шумом и навсегда уничтожает аппаратные ключи процессора. Восстановление невозможно.",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = shredConfirmCode,
                        onValueChange = { if (it.length <= 6) shredConfirmCode = it },
                        label = { Text("Введи 999 для подтверждения", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("shred_confirm_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberRed,
                            unfocusedBorderColor = CyberBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        shredConfirmCode = ""
                        onExecutePanicShred()
                    },
                    enabled = canShred,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberRed,
                        disabledContainerColor = CyberRed.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.testTag("shred_confirm_execute_button")
                ) {
                    Text("СТЕРЕТЬ СЕЙЧАС", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        shredConfirmCode = ""
                        onDismissPanic()
                    }
                ) {
                    Text("ОТМЕНА", color = CyberMuted)
                }
            },
            containerColor = CyberSurface
        )
    }
}
