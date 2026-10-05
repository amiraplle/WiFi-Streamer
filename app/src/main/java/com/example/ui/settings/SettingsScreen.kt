package com.example.ui.settings

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.network.NetworkUtils
import com.example.domain.audio.AudioInterruptionEvent
import com.example.domain.audio.AudioInterruptionLogger
import com.example.model.BufferLatencyPreset
import com.example.model.HeaderMode
import com.example.model.ProtocolMode
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioDropdown
import com.example.ui.components.StudioSwitch
import androidx.compose.ui.graphics.Brush
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs by viewModel.userPreferences.collectAsState()
    val formatCaps = viewModel.supportedFormatCapabilities

    var tcpPortText by remember(prefs.targetPort) { mutableStateOf(prefs.targetPort.toString()) }
    var httpPortText by remember(prefs.httpPort) { mutableStateOf(prefs.httpPort.toString()) }
    var timeoutText by remember(prefs.connectionTimeoutMs) { mutableStateOf(prefs.connectionTimeoutMs.toString()) }
    var retriesText by remember(prefs.maxReconnectRetries) { mutableStateOf(prefs.maxReconnectRetries.toString()) }

    var showLogsDialog by remember { mutableStateOf(false) }
    val interruptionLogs by AudioInterruptionLogger.logsFlow.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // =========================================================================
        // AUDIO CONFIGURATION (Premium Dropdowns Matching Example)
        // =========================================================================
        SettingsHeader(title = "AUDIO CONFIGURATION")

        StudioCard(
            modifier = Modifier.fillMaxWidth().testTag("audio_format_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Audio Format Dropdown
                StudioDropdown(
                    label = "Audio Format & Quality",
                    icon = Icons.Outlined.Audiotrack,
                    selectedValue = prefs.audioFormat,
                    items = formatCaps.map { it.format },
                    itemTitle = { "${it.displayName} — ${it.bitrateKbps} kbps" },
                    onItemSelected = { viewModel.updateAudioFormat(it) },
                    testTag = "settings_audio_format"
                )

                HorizontalDivider(color = Color(0xFF22222E))

                // Buffer Latency Preset Dropdown
                StudioDropdown(
                    label = "Target Buffer & Latency",
                    icon = Icons.Outlined.Speed,
                    selectedValue = prefs.bufferPreset,
                    items = BufferLatencyPreset.values().toList(),
                    itemTitle = { it.displayName },
                    onItemSelected = { viewModel.updateBufferPreset(it) },
                    testTag = "settings_buffer_preset"
                )

                HorizontalDivider(color = Color(0xFF22222E))

                // Stream Header Mode Dropdown
                StudioDropdown(
                    label = "Stream Header Mode",
                    icon = Icons.Outlined.Tune,
                    selectedValue = prefs.headerMode,
                    items = HeaderMode.values().toList(),
                    itemTitle = { it.displayName },
                    onItemSelected = { viewModel.updateHeaderMode(it) },
                    testTag = "settings_header_mode"
                )
            }
        }

        // =========================================================================
        // NETWORK & TRANSMISSION (Dropdown + High-Contrast Switches)
        // =========================================================================
        SettingsHeader(title = "STREAM SETTINGS")

        StudioCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Phone Wi-Fi IP Banner with copy
                val localPhoneIp = remember { NetworkUtils.getLocalIpAddress(context) ?: "Unavailable" }
                Surface(
                    color = Color(0xFF111116),
                    border = BorderStroke(
                        1.2.dp,
                        Brush.verticalGradient(listOf(Color(0xFF363644), Color(0xFF202028)))
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("Phone IP", localPhoneIp)
                            clipboard?.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied IP: $localPhoneIp", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "PHONE HOST / IP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp,
                                color = Color(0xFF8E8E98)
                            )
                            Text(
                                text = localPhoneIp,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy IP",
                            tint = Color(0xFF8E8E98),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Preferred Stream Protocol Dropdown (Matching Screenshot Exactly)
                StudioDropdown(
                    label = "Preferred Stream",
                    icon = Icons.Outlined.Router,
                    selectedValue = prefs.protocolMode,
                    items = ProtocolMode.values().toList(),
                    itemTitle = { mode ->
                        when (mode) {
                            ProtocolMode.RAW_TCP_SERVER -> "Raw TCP PCM — port ${prefs.targetPort}"
                            ProtocolMode.RAW_TCP_CLIENT -> "Raw TCP Client — port ${prefs.targetPort}"
                            ProtocolMode.HTTP_SERVER -> "HTTP WAV/PCM — port ${prefs.httpPort}"
                        }
                    },
                    onItemSelected = { viewModel.updateProtocolMode(it) },
                    testTag = "settings_protocol_mode"
                )

                // Port & Timeout text fields
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = tcpPortText,
                        onValueChange = {
                            tcpPortText = it
                            val p = it.toIntOrNull()
                            if (p != null) viewModel.updateNetworkSettings(p, prefs.httpPort, prefs.connectionTimeoutMs, prefs.autoReconnect, prefs.maxReconnectRetries)
                        },
                        label = { Text("TCP PORT", fontSize = 10.sp, letterSpacing = 0.5.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF111116),
                            unfocusedContainerColor = Color(0xFF111116),
                            focusedBorderColor = Color(0xFF5E5E76),
                            unfocusedBorderColor = Color(0xFF343444),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    OutlinedTextField(
                        value = httpPortText,
                        onValueChange = {
                            httpPortText = it
                            val p = it.toIntOrNull()
                            if (p != null) viewModel.updateNetworkSettings(prefs.targetPort, p, prefs.connectionTimeoutMs, prefs.autoReconnect, prefs.maxReconnectRetries)
                        },
                        label = { Text("HTTP PORT", fontSize = 10.sp, letterSpacing = 0.5.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF111116),
                            unfocusedContainerColor = Color(0xFF111116),
                            focusedBorderColor = Color(0xFF5E5E76),
                            unfocusedBorderColor = Color(0xFF343444),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = {
                            timeoutText = it
                            val t = it.toIntOrNull()
                            if (t != null) viewModel.updateNetworkSettings(prefs.targetPort, prefs.httpPort, t, prefs.autoReconnect, prefs.maxReconnectRetries)
                        },
                        label = { Text("TIMEOUT (MS)", fontSize = 10.sp, letterSpacing = 0.5.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF111116),
                            unfocusedContainerColor = Color(0xFF111116),
                            focusedBorderColor = Color(0xFF5E5E76),
                            unfocusedBorderColor = Color(0xFF343444),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    OutlinedTextField(
                        value = retriesText,
                        onValueChange = {
                            retriesText = it
                            val r = it.toIntOrNull()
                            if (r != null) viewModel.updateNetworkSettings(prefs.targetPort, prefs.httpPort, prefs.connectionTimeoutMs, prefs.autoReconnect, r)
                        },
                        label = { Text("MAX RETRIES", fontSize = 10.sp, letterSpacing = 0.5.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF111116),
                            unfocusedContainerColor = Color(0xFF111116),
                            focusedBorderColor = Color(0xFF5E5E76),
                            unfocusedBorderColor = Color(0xFF343444),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    )
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // Automatic stream reconnect (High-Contrast White Pill Switch)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingItemHeader(icon = Icons.Outlined.Sync, label = "Automatic stream reconnect")
                    StudioSwitch(
                        checked = prefs.autoReconnect,
                        onCheckedChange = {
                            viewModel.updateNetworkSettings(prefs.targetPort, prefs.httpPort, prefs.connectionTimeoutMs, it, prefs.maxReconnectRetries)
                        }
                    )
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // Zero-Jitter Rate Pacing Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingItemHeader(icon = Icons.Outlined.ElectricBolt, label = "Zero-Jitter Rate Pacing")
                    StudioSwitch(
                        checked = prefs.ratePacing,
                        onCheckedChange = { viewModel.updateRatePacing(it) },
                        modifier = Modifier.testTag("rate_pacing_switch")
                    )
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // Silence Phone Speaker Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingItemHeader(icon = Icons.Outlined.VolumeOff, label = "Silence Phone Speaker During Stream")
                    StudioSwitch(
                        checked = prefs.mutePhoneWhileStreaming,
                        onCheckedChange = { viewModel.updateMutePhoneWhileStreaming(it) },
                        modifier = Modifier.testTag("mute_phone_speaker_switch")
                    )
                }
            }
        }

        // =========================================================================
        // SYSTEM & DIAGNOSTICS
        // =========================================================================
        SettingsHeader(title = "SYSTEM & DIAGNOSTICS")

        StudioCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Audio Interruption Logs Item
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showLogsDialog = true }
                        .testTag("audio_interruption_logs_card"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingItemHeader(icon = Icons.Outlined.History, label = "Audio Interruption Logs")

                    Surface(
                        color = Color(0xFF1E1E26),
                        border = BorderStroke(1.dp, Color(0xFF282834)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (interruptionLogs.isEmpty()) "0 Drops" else "${interruptionLogs.size} Events",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (interruptionLogs.isEmpty()) StreamEmerald else Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // About Item
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingItemHeader(icon = Icons.Outlined.Info, label = "C3 Audio Streamer (v1.6)")

                    Surface(
                        color = Color(0xFF1E1E26),
                        border = BorderStroke(1.dp, Color(0xFF282834)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "MIT License",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (showLogsDialog) {
            AudioInterruptionLogsDialog(
                logs = interruptionLogs,
                onDismiss = { showLogsDialog = false },
                onClear = { AudioInterruptionLogger.clear() }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
fun SettingItemHeader(
    icon: ImageVector,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF8E8E98),
            modifier = Modifier.size(17.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            fontSize = 13.sp
        )
    }
}

@Composable
fun SettingsHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = Color(0xFF8E8E98),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
fun AudioInterruptionLogsDialog(
    logs: List<AudioInterruptionEvent>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        shape = RoundedCornerShape(18.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Interruption Logs", fontWeight = FontWeight.Bold, color = TextPrimary)
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Clear Logs",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Zero audio drops detected.\nCapture & transmission are smooth.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StreamEmerald,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(logs.reversed()) { log ->
                        Surface(
                            color = Color(0xFF141418),
                            border = BorderStroke(1.dp, Color(0xFF262630)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = log.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Text(
                                        text = log.formattedTime,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextTertiary
                                    )
                                }
                                Text(
                                    text = log.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val fullText = logs.joinToString("\n") { "[${it.formattedTime}] [${it.category}] ${it.description}" }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = ClipData.newPlainText("C3 Logs", fullText)
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(context, "Copied logs to clipboard", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Copy All", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}
