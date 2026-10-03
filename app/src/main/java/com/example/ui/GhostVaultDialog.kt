package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.crypto.GhostCryptoVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val CyberBlack = Color(0xFF090D10)
private val CyberSurface = Color(0xFF131A21)
private val CyberBorder = Color(0xFF223240)
private val CyberGreen = Color(0xFF00FF66)
private val CyberRed = Color(0xFFFF3366)
private val CyberMuted = Color(0xFF8A9BA8)

@Composable
fun GhostVaultDialog(
    onDismiss: () -> Unit,
    onVaultUpdated: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var unlockedMode by remember { mutableStateOf<GhostCryptoVault.PinMode?>(null) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    var vaultFiles by remember { mutableStateOf(emptyList<File>()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var decryptedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isDecrypting by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshFiles() {
        if (unlockedMode == GhostCryptoVault.PinMode.REAL) {
            vaultFiles = GhostCryptoVault.listVaultFiles(context)
        } else if (unlockedMode == GhostCryptoVault.PinMode.DECOY) {
            vaultFiles = emptyList() // Clean empty state for decoy mode under duress
        }
        onVaultUpdated()
    }

    Dialog(
        onDismissRequest = {
            decryptedBitmap?.recycle()
            decryptedBitmap = null
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBlack.copy(alpha = 0.96f))
                .padding(16.dp)
        ) {
            // STEP 1: TELLA-STYLE PIN CHALLENGE
            if (unlockedMode == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .align(Alignment.Center)
                        .background(CyberSurface, RoundedCornerShape(14.dp))
                        .border(1.dp, CyberGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock",
                        tint = CyberGreen,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "TELLA STEALTH VAULT",
                        color = CyberGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "ENTER SECURE PIN",
                        color = CyberMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // PIN Dots indicator
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) { idx ->
                            val filled = idx < enteredPin.length
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (filled) CyberGreen else CyberBorder)
                                    .border(1.dp, if (filled) CyberGreen else CyberMuted, RoundedCornerShape(7.dp))
                            )
                        }
                    }

                    if (pinError) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "INVALID PIN",
                            color = CyberRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Numpad 0-9
                    val digits = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("CLR", "0", "OK")
                    )

                    for (row in digits) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            for (key in row) {
                                Button(
                                    onClick = {
                                        pinError = false
                                        when (key) {
                                            "CLR" -> enteredPin = ""
                                            "OK" -> {
                                                val mode = GhostCryptoVault.verifyPin(enteredPin)
                                                if (mode != GhostCryptoVault.PinMode.INVALID) {
                                                    unlockedMode = mode
                                                    refreshFiles()
                                                } else {
                                                    pinError = true
                                                    enteredPin = ""
                                                }
                                            }
                                            else -> {
                                                if (enteredPin.length < 4) {
                                                    enteredPin += key
                                                    if (enteredPin.length == 4) {
                                                        val mode = GhostCryptoVault.verifyPin(enteredPin)
                                                        if (mode != GhostCryptoVault.PinMode.INVALID) {
                                                            unlockedMode = mode
                                                            refreshFiles()
                                                        } else {
                                                            pinError = true
                                                            enteredPin = ""
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(64.dp, 48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberBorder.copy(alpha = 0.6f)),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = key,
                                        color = if (key == "OK") CyberGreen else if (key == "CLR") CyberRed else Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Real PIN: 1337 | Decoy PIN: 0000",
                        color = CyberMuted.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(onClick = onDismiss) {
                        Text("CANCEL", color = CyberMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            } else {
                // STEP 2: VAULT CONTENT
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, CyberGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "Vault",
                                tint = CyberGreen,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (unlockedMode == GhostCryptoVault.PinMode.REAL) "SECURE VAULT // AUTHENTIC" else "VAULT // SYSTEM ARCHIVE",
                                color = CyberGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        IconButton(onClick = {
                            decryptedBitmap?.recycle()
                            decryptedBitmap = null
                            onDismiss()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = CyberMuted
                            )
                        }
                    }

                    // Stats subheader
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val totalSizeKb = vaultFiles.sumOf { it.length() } / 1024
                        Text(
                            text = "OBJECTS: ${vaultFiles.size} | TOTAL: ${totalSizeKb} KB",
                            color = CyberMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        Text(
                            text = if (unlockedMode == GhostCryptoVault.PinMode.REAL) "KEY: TEE-HARDWARE" else "MODE: ISOLATED",
                            color = CyberGreen.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    HorizontalDivider(color = CyberBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Detail viewer if an image is selected
                    if (selectedFile != null) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "<- BACK TO LIST",
                                    color = CyberGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clickable {
                                            decryptedBitmap?.recycle()
                                            decryptedBitmap = null
                                            selectedFile = null
                                        }
                                        .padding(4.dp)
                                )
                                Button(
                                    onClick = {
                                        selectedFile?.let { f ->
                                            GhostCryptoVault.shredFile(f)
                                            decryptedBitmap?.recycle()
                                            decryptedBitmap = null
                                            selectedFile = null
                                            statusMessage = "FILE ZERO-SHREDDED FROM DISK"
                                            refreshFiles()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteForever,
                                        contentDescription = "Shred",
                                        modifier = Modifier.size(16.dp),
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "SHRED FILE",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color.White
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (isDecrypting) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = CyberGreen)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "DECRYPTING IN RAM...",
                                            color = CyberGreen,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            } else if (decryptedBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        bitmap = decryptedBitmap!!.asImageBitmap(),
                                        contentDescription = "Decrypted photo",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CyberSurface, RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "FORENSIC STATUS: 100% STRIPPED",
                                        color = CyberGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "EXIF/GPS/MAKER-NOTES PURGED AT BYTE LEVEL // RAM RE-RENDER ONLY",
                                        color = CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    } else {
                        // List of files
                        if (vaultFiles.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Shield,
                                        contentDescription = "Empty",
                                        tint = CyberMuted,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        if (unlockedMode == GhostCryptoVault.PinMode.DECOY) "VAULT IS EMPTY" else "NO FILES IN VAULT",
                                        color = CyberMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = if (unlockedMode == GhostCryptoVault.PinMode.DECOY) "Decoy profile active. Zero artifacts detected." else "Take shots to store encrypted images here.",
                                        color = CyberMuted.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                items(vaultFiles) { file ->
                                    val dateStr = remember(file) {
                                        val sdf = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault())
                                        sdf.format(Date(file.lastModified()))
                                    }
                                    val sizeKb = remember(file) { file.length() / 1024 }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .background(CyberSurface, RoundedCornerShape(8.dp))
                                            .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                                            .clickable {
                                                selectedFile = file
                                                isDecrypting = true
                                                scope.launch {
                                                    try {
                                                        val bmp = withContext(Dispatchers.IO) {
                                                            GhostCryptoVault.decryptToBitmap(file)
                                                        }
                                                        decryptedBitmap?.recycle()
                                                        decryptedBitmap = bmp
                                                    } catch (e: Exception) {
                                                        statusMessage = "DECRYPT ERROR: ${e.message}"
                                                    } finally {
                                                        isDecrypting = false
                                                    }
                                                }
                                            }
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = file.name,
                                                color = Color.White,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = "$dateStr | $sizeKb KB | AES-256-GCM",
                                                color = CyberMuted,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Visibility,
                                                contentDescription = "View",
                                                tint = CyberGreen,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            IconButton(
                                                onClick = {
                                                    GhostCryptoVault.shredFile(file)
                                                    refreshFiles()
                                                    statusMessage = "FILE SHREDDED"
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteForever,
                                                    contentDescription = "Delete",
                                                    tint = CyberRed
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Emergency Crypto-Shredder Button
                        if (vaultFiles.isNotEmpty()) {
                            Button(
                                onClick = { showWipeConfirm = true },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Crypto-Shred",
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "CRYPTO-SHRED: DESTROY TEE KEY & VAULT",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            // Crypto-Shred confirmation dialog
            if (showWipeConfirm) {
                AlertDialog(
                    onDismissRequest = { showWipeConfirm = false },
                    title = {
                        Text(
                            "CRYPTO-SHRED CONFIRMATION",
                            color = CyberRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            "This will immediately destroy the AES-256 key inside the hardware TEE/KeyStore. All files become mathematically unrecoverable noise, immune to NAND wear-leveling forensics. Irreversible!",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val count = GhostCryptoVault.cryptoShred(context)
                                showWipeConfirm = false
                                statusMessage = "CRYPTO-SHRED COMPLETE: $count FILES DESTROYED"
                                refreshFiles()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRed)
                        ) {
                            Text("DESTROY KEY & ERASE", color = Color.White)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showWipeConfirm = false }) {
                            Text("CANCEL", color = CyberMuted)
                        }
                    },
                    containerColor = CyberSurface
                )
            }
        }
    }
}
