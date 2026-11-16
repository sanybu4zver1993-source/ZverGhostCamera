package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.crypto.GhostCryptoVault
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

private val CalcBg = Color(0xFF0E1012)
private val CalcPanel = Color(0xFF171A1E)
private val CalcBtnDark = Color(0xFF23272E)
private val CalcBtnFunc = Color(0xFF323844)
private val CalcAccent = Color(0xFFFF9500)
private val CalcTextMuted = Color(0xFF9AA3AE)

/**
 * Genuine working dark-mode Calculator camouflage gate.
 * - Performs real arithmetic (+, -, ×, ÷, %, +/-, decimals) with expression history.
 * - Entering Main/Decoy/Duress PIN + '=' silently unlocks Zver Camera into the matched profile.
 * - If vault PIN is not configured yet, '1337' + '=' unlocks Zver Camera.
 */
@Composable
fun DecoyCalculatorScreen(
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current

    var display by remember { mutableStateOf("0") }
    var historyLine by remember { mutableStateOf("") }
    var pendingOp by remember { mutableStateOf<String?>(null) }
    var firstOperand by remember { mutableStateOf<BigDecimal?>(null) }
    var resetOnNextDigit by remember { mutableStateOf(false) }

    fun formatDecimal(v: BigDecimal): String {
        val normalized = v.stripTrailingZeros()
        val s = normalized.toPlainString()
        return if (s == "-0") "0" else s
    }

    fun evaluate(a: BigDecimal, op: String, b: BigDecimal): String {
        return try {
            val res = when (op) {
                "+" -> a.add(b)
                "-" -> a.subtract(b)
                "×" -> a.multiply(b)
                "÷" -> {
                    if (b.compareTo(BigDecimal.ZERO) == 0) return "Ошибка"
                    a.divide(b, 8, RoundingMode.HALF_UP)
                }
                else -> b
            }
            formatDecimal(res)
        } catch (_: Exception) {
            "Ошибка"
        }
    }

    fun pressDigit(d: String) {
        if (display == "Ошибка" || resetOnNextDigit) {
            display = if (d == ".") "0." else d
            resetOnNextDigit = false
        } else {
            if (d == "." && display.contains(".")) return
            display = if (display == "0" && d != ".") d else (display + d).take(14)
        }
    }

    fun pressOp(op: String) {
        val current = display.toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (firstOperand != null && pendingOp != null && !resetOnNextDigit) {
            val res = evaluate(firstOperand!!, pendingOp!!, current)
            display = res
            firstOperand = res.toBigDecimalOrNull()
        } else {
            firstOperand = current
        }
        pendingOp = op
        historyLine = "${formatDecimal(firstOperand ?: current)} $op"
        resetOnNextDigit = true
    }

    fun pressEquals() {
        // Check stealth PIN trigger ONLY when user hasn't started an arithmetic expression
        if (pendingOp == null && firstOperand == null && display.all { it.isDigit() } && display.length in 4..8) {
            val configured = GhostCryptoVault.isConfigured(context)
            if (!configured && display == "1337") {
                display = "0"
                onUnlocked()
                return
            }
            if (configured) {
                val outcome = GhostCryptoVault.unlockWithPinOutcome(context, display.toCharArray())
                if (outcome != GhostCryptoVault.UnlockOutcome.INVALID) {
                    display = "0"
                    onUnlocked()
                    return
                }
            }
        }

        val a = firstOperand ?: return
        val op = pendingOp ?: return
        val b = display.toBigDecimalOrNull() ?: return
        val resultStr = evaluate(a, op, b)
        historyLine = "${formatDecimal(a)} $op ${formatDecimal(b)} ="
        display = resultStr
        firstOperand = null
        pendingOp = null
        resetOnNextDigit = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CalcBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        // Display Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = historyLine,
                color = CalcTextMuted,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = display,
                color = Color.White,
                fontSize = if (display.length > 9) 38.sp else 54.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("calc_display")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        val rows = listOf(
            listOf("AC", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ".", "=")
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CalcPanel, RoundedCornerShape(20.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (row in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (label in row) {
                        val isOp = label in listOf("÷", "×", "-", "+", "=")
                        val isFunc = label in listOf("AC", "⌫", "%", "±")
                        val bg = when {
                            label == "=" -> CalcAccent
                            isOp -> CalcAccent.copy(alpha = 0.22f)
                            isFunc -> CalcBtnFunc
                            else -> CalcBtnDark
                        }
                        val fg = when {
                            label == "=" -> Color.White
                            isOp -> CalcAccent
                            isFunc -> Color(0xFFD6DCE5)
                            else -> Color.White
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 64.dp)
                                .clip(CircleShape)
                                .background(bg)
                                .testTag("calc_btn_$label")
                                .clickable {
                                    when (label) {
                                        "AC" -> {
                                            display = "0"
                                            historyLine = ""
                                            firstOperand = null
                                            pendingOp = null
                                            resetOnNextDigit = false
                                        }
                                        "⌫" -> {
                                            if (!resetOnNextDigit && display != "Ошибка") {
                                                display = if (display.length <= 1) "0" else display.dropLast(1)
                                            }
                                        }
                                        "±" -> {
                                            val v = display.toBigDecimalOrNull()
                                            if (v != null && v.compareTo(BigDecimal.ZERO) != 0) {
                                                display = formatDecimal(v.negate())
                                            }
                                        }
                                        "%" -> {
                                            val v = display.toBigDecimalOrNull()
                                            if (v != null) {
                                                display = formatDecimal(v.divide(BigDecimal(100), MathContext.DECIMAL64))
                                            }
                                        }
                                        "÷", "×", "-", "+" -> pressOp(label)
                                        "=" -> pressEquals()
                                        else -> pressDigit(label)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = fg,
                                fontSize = 24.sp,
                                fontWeight = if (isOp) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}
