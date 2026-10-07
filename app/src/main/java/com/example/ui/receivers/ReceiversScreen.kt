package com.example.ui.receivers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.ReceiverEntity
import com.example.data.discovery.DiscoveredReceiver
import com.example.data.network.NetworkUtils
import com.example.ui.components.StudioCard
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ReceiversScreen(
    viewModel: ReceiversViewModel,
    onReceiverSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isScanning by viewModel.isScanning.collectAsState()
    val discovered by viewModel.discoveredReceivers.collectAsState()
    val savedReceivers by viewModel.savedReceivers.collectAsState()
    val pingResult by viewModel.pingResult.collectAsState()

    var manualHost by remember { mutableStateOf("c3music.local") }
    var manualPort by remember { mutableStateOf("50005") }
    var manualName by remember { mutableStateOf("") }

    val phoneIp = remember { NetworkUtils.getLocalIpAddress(context) ?: "Checking Wi-Fi..." }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // =========================================================================
        // 1. NETWORK DISCOVERY HEADER
        // =========================================================================
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "RECEIVER DISCOVERY",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8E8E98),
                        letterSpacing = 0.8.sp,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Searching for s3music.local, c3music.local and mDNS",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                IconButton(
                    onClick = {
                        if (isScanning) viewModel.stopScan() else viewModel.startScan()
                    },
                    modifier = Modifier.testTag("scan_refresh_button")
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Scan for receivers",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Discovered Devices List
        if (discovered.isEmpty()) {
            item {
                StudioCard(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isScanning) "Probing local Wi-Fi for c3music.local…" else "No receivers discovered. Tap refresh or enter IP below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        } else {
            items(discovered) { receiver ->
                DiscoveredReceiverCard(
                    receiver = receiver,
                    onSelect = {
                        viewModel.selectReceiver(receiver.host, receiver.port)
                        Toast.makeText(context, "Selected ${receiver.host}:${receiver.port}", Toast.LENGTH_SHORT).show()
                        onReceiverSelected()
                    }
                )
            }
        }

        // =========================================================================
        // 2. PHONE WI-FI IP BANNER
        // =========================================================================
        item {
            StudioCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Wifi,
                                contentDescription = "Wi-Fi",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Your Phone's Wi-Fi IP",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Surface(
                            color = Color(0xFF1B1B24),
                            border = BorderStroke(1.dp, Color(0xFF2C2C3A)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = ClipData.newPlainText("Phone IP", phoneIp)
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied IP: $phoneIp", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = phoneIp,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Both devices must be on the same Wi-Fi. Enter your ESP32-S3 or ESP32-C3 IP (e.g. s3music.local, c3music.local or 192.168.1.50) to stream single-target audio directly.",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        // =========================================================================
        // 3. DIRECT PUSH TO ESP32-S3 / C3 (CLEAN, SPACIOUS, NO SQUISHED BUTTONS)
        // =========================================================================
        item {
            Text(
                text = "DIRECT PUSH (ESP32-S3 / ESP32-C3)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8E8E98),
                letterSpacing = 0.8.sp,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        item {
            StudioCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual_connection_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Quick Preset Hardware Switcher
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isS3 = manualHost == "s3music.local"
                        val isC3 = manualHost == "c3music.local"

                        Surface(
                            color = if (isS3) Color.White else Color(0xFF1B1B24),
                            border = BorderStroke(1.dp, if (isS3) Color.White else Color(0xFF2C2C3A)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    manualHost = "s3music.local"
                                    if (manualName.isBlank() || manualName == "ESP32-C3") manualName = "ESP32-S3"
                                }
                        ) {
                            Text(
                                text = "ESP32-S3 (s3music.local)",
                                color = if (isS3) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isS3) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        Surface(
                            color = if (isC3) Color.White else Color(0xFF1B1B24),
                            border = BorderStroke(1.dp, if (isC3) Color.White else Color(0xFF2C2C3A)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    manualHost = "c3music.local"
                                    if (manualName.isBlank() || manualName == "ESP32-S3") manualName = "ESP32-C3"
                                }
                        ) {
                            Text(
                                text = "ESP32-C3 (c3music.local)",
                                color = if (isC3) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isC3) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    // Hostname / IP Input
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "HOSTNAME OR IP ADDRESS",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            letterSpacing = 0.6.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF8E8E98)
                        )
                        OutlinedTextField(
                            value = manualHost,
                            onValueChange = { manualHost = it },
                            placeholder = { Text("e.g. s3music.local, c3music.local, 192.168.1.150", fontSize = 13.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF111116),
                                unfocusedContainerColor = Color(0xFF111116),
                                focusedBorderColor = Color(0xFF5E5E76),
                                unfocusedBorderColor = Color(0xFF343444),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_host_input"),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }

                    // Port (UDP) and Nickname Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "PORT (UDP)",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                letterSpacing = 0.6.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF8E8E98)
                            )
                            OutlinedTextField(
                                value = manualPort,
                                onValueChange = { manualPort = it },
                                placeholder = { Text("50005", fontSize = 13.sp) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF111116),
                                    unfocusedContainerColor = Color(0xFF111116),
                                    focusedBorderColor = Color(0xFF5E5E76),
                                    unfocusedBorderColor = Color(0xFF343444),
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manual_port_input"),
                                shape = RoundedCornerShape(16.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1.4f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "NICKNAME (OPTIONAL)",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                letterSpacing = 0.6.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF8E8E98)
                            )
                            OutlinedTextField(
                                value = manualName,
                                onValueChange = { manualName = it },
                                placeholder = { Text("Living Room C3", fontSize = 13.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF111116),
                                    unfocusedContainerColor = Color(0xFF111116),
                                    focusedBorderColor = Color(0xFF5E5E76),
                                    unfocusedBorderColor = Color(0xFF343444),
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }

                    // Ping Status Feedback
                    if (pingResult != null) {
                        Surface(
                            color = Color(0xFF1B1B24),
                            border = BorderStroke(1.dp, Color(0xFF2C2C3A)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = pingResult ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color.White,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Organized Action Buttons (Row 1: Two secondary actions | Row 2: Full-width Primary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val port = manualPort.toIntOrNull() ?: 50005
                                viewModel.testConnection(manualHost.trim(), port)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF383848)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF16161D),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                Icons.Outlined.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Test Ping",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val port = manualPort.toIntOrNull() ?: 50005
                                viewModel.addReceiver(
                                    name = manualName.ifBlank { manualHost },
                                    host = manualHost.trim(),
                                    tcpPort = port,
                                    httpPort = 8080,
                                    isDefault = false
                                )
                                Toast.makeText(context, "Saved to profiles", Toast.LENGTH_SHORT).show()
                                manualName = ""
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFF383848)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF16161D),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                Icons.Outlined.BookmarkAdd,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Save Profile",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    // Prominent Primary Button: Connect & Use Now
                    Button(
                        onClick = {
                            val port = manualPort.toIntOrNull() ?: 50005
                            viewModel.selectReceiver(manualHost.trim(), port)
                            Toast.makeText(context, "Selected ${manualHost.trim()}:$port", Toast.LENGTH_SHORT).show()
                            onReceiverSelected()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Connect & Use Receiver",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // =========================================================================
        // 4. SAVED RECEIVER PROFILES
        // =========================================================================
        if (savedReceivers.isNotEmpty()) {
            item {
                Text(
                    text = "SAVED PROFILES",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8E8E98),
                    letterSpacing = 0.8.sp,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            items(savedReceivers, key = { it.id }) { saved ->
                SavedReceiverCard(
                    receiver = saved,
                    onSelect = {
                        viewModel.selectReceiver(saved.host, saved.tcpPort)
                        Toast.makeText(context, "Selected ${saved.name}", Toast.LENGTH_SHORT).show()
                        onReceiverSelected()
                    },
                    onSetDefault = { viewModel.setDefaultReceiver(saved) },
                    onDelete = { viewModel.deleteReceiver(saved) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun DiscoveredReceiverCard(
    receiver: DiscoveredReceiver,
    onSelect: () -> Unit
) {
    StudioCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E1E26)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Podcasts,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = receiver.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${receiver.host}:${receiver.port}" + (receiver.pingMs?.let { " • ${it}ms" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onSelect,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("Select", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SavedReceiverCard(
    receiver: ReceiverEntity,
    onSelect: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    StudioCard(
        modifier = Modifier.fillMaxWidth(),
        borderGradientTop = if (receiver.isDefault) Color(0xFF6E6E85) else Color(0xFF383848),
        borderGradientBottom = if (receiver.isDefault) Color(0xFF3E3E50) else Color(0xFF22222E)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onSetDefault,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (receiver.isDefault) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (receiver.isDefault) "Default receiver" else "Set as default",
                        tint = if (receiver.isDefault) Color.White else Color(0xFF666675),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = receiver.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${receiver.host}:${receiver.tcpPort}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFF888898),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Button(
                    onClick = onSelect,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Connect", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
