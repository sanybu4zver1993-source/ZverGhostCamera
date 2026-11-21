package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.crypto.GhostCryptoVault
import com.example.crypto.VaultIndexEngine
import com.example.security.SecureClipboard
import com.example.security.SelfTestEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CyberBlack = Color(0xFF070B0E)
private val CyberSurface = Color(0xFF111820)
private val CyberBorder = Color(0xFF223240)
private val CyberGreen = Color(0xFF00FF66)
private val CyberAmber = Color(0xFFFFC107)
private val CyberRed = Color(0xFFFF1744)
private val CyberCyan = Color(0xFF00E5FF)
private val CyberMuted = Color(0xFF8FA3B0)

@Composable
fun SelfTestDialog(
    boundUseCasesSummary: String,
    eglRecordableOk: Boolean? = null,
    onStatusMessage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<SelfTestEngine.SelfTestReport?>(null) }
    var isRunning by remember { mutableStateOf(true) }

    fun runCheck() {
        isRunning = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                SelfTestEngine.runFullDiagnostics(
                    context = context,
                    boundUseCasesSummary = boundUseCasesSummary,
                    eglRecordableOk = eglRecordableOk
                )
            }
            report = res
            isRunning = false
        }
    }

    LaunchedEffect(Unit) {
        runCheck()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBlack.copy(alpha = 0.96f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Build, contentDescription = "Self-Test", tint = CyberCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SELF-TEST ДИАГНОСТИКА",
                            color = CyberCyan,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).testTag("selftest_close_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = CyberMuted)
                    }
                }

                report?.let { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(CyberSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PASS: ${r.passCount}   WARN: ${r.warnCount}   FAIL: ${r.failCount}",
                            color = when {
                                r.failCount > 0 -> CyberRed
                                r.warnCount > 0 -> CyberAmber
                                else -> CyberGreen
                            },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = "4GB RAM PROFILE",
                            color = CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                }

                if (isRunning && report == null) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = CyberCyan, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("ПРОВЕРКА УЗЛОВ...", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        report?.items?.forEach { item ->
                            val statusColor = when (item.status) {
                                SelfTestEngine.CheckStatus.PASS -> CyberGreen
                                SelfTestEngine.CheckStatus.WARN -> CyberAmber
                                SelfTestEngine.CheckStatus.FAIL -> CyberRed
                            }
                            val statusIcon = when (item.status) {
                                SelfTestEngine.CheckStatus.PASS -> Icons.Default.CheckCircle
                                SelfTestEngine.CheckStatus.WARN -> Icons.Default.Warning
                                SelfTestEngine.CheckStatus.FAIL -> Icons.Default.Error
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CyberSurface)
                                    .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(statusIcon, contentDescription = item.status.badge, tint = statusColor, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.title,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        text = item.status.badge,
                                        color = Color.Black,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(statusColor)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(item.summary, color = statusColor, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                Text(item.detail, color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val curReport = report
                if (curReport != null && (curReport.orphanTempCount > 0 || curReport.expiredTtlCount > 0)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val cleaned = withContext(Dispatchers.IO) {
                                    val a = GhostCryptoVault.shredOrphanedVaultTempFiles(context)
                                    val b = GhostCryptoVault.shredOrphanedVideoTemps(context)
                                    val c = VaultIndexEngine.purgeExpiredFiles(context, GhostCryptoVault.activeProfile)
                                    a + b + c
                                }
                                onStatusMessage("ОЧИЩЕНО ВРЕМЕННЫХ/TTL: $cleaned")
                                runCheck()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberAmber),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("selftest_cleanup_btn")
                    ) {
                        Text(
                            text = "🧹 ОЧИСТИТЬ ХВОСТЫ (${curReport.orphanTempCount}) И ПРОСРОЧЕННЫЕ TTL (${curReport.expiredTtlCount})",
                            color = Color.Black,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            curReport?.let {
                                SecureClipboard.copySensitive(context, "Zver Self-Test", it.formattedTextReport)
                                onStatusMessage("SELF-TEST СКОПИРОВАН (45с)")
                            }
                        },
                        enabled = curReport != null,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(48.dp).testTag("selftest_copy_btn")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Копировать", tint = CyberGreen, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("КОПИРОВАТЬ", color = CyberGreen, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { runCheck() },
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan.copy(alpha = 0.22f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(48.dp).testTag("selftest_rerun_btn")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Повтор", tint = CyberCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ПРОВЕРИТЬ", color = CyberCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
