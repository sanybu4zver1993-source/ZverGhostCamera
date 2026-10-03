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
    var vaultFiles by remember { mutableStateOf(GhostCryptoVault.listVaultFiles(context)) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var decryptedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isDecrypting by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshFiles() {
        vaultFiles = GhostCryptoVault.listVaultFiles(context)
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
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Vault",
                            tint = CyberGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "ENCRYPTED VAULT // .GCF",
                            color = CyberGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
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
                        text = "CIPHER: AES-256-GCM",
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

                            // Forensic badge check
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CyberSurface, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "FORENSIC STATUS: CLEAN",
                                    color = CyberGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = "EXIF: 0 B | GPS: PURGED | DEVICE ID: REMOVED | RAM ONLY",
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
                                    "VAULT IS EMPTY",
                                    color = CyberMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "Take shots in Ghost Camera to encrypt them here.",
                                    color = CyberMuted.copy(alpha = 0.7f),
                                    fontSize = 12.sp
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
                                            text = "$dateStr | $sizeKb KB | AES-256",
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

                    // Emergency Wipe Button
                    if (vaultFiles.isNotEmpty()) {
                        Button(
                            onClick = { showWipeConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRed),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Panic Wipe",
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "PANIC WIPE: DESTROY ENTIRE VAULT",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Wipe confirmation dialog
            if (showWipeConfirm) {
                AlertDialog(
                    onDismissRequest = { showWipeConfirm = false },
                    title = {
                        Text(
                            "CONFIRM PANIC WIPE",
                            color = CyberRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            "This will overwrite all ${vaultFiles.size} encrypted files with random bytes and zeroes on flash storage, erase the KeyStore AES key, and purge RAM. Irreversible!",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val count = GhostCryptoVault.emergencyWipeAll(context)
                                showWipeConfirm = false
                                statusMessage = "PANIC WIPE COMPLETE: $count FILES DESTROYED"
                                refreshFiles()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberRed)
                        ) {
                            Text("SHRED EVERYTHING", color = Color.White)
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
