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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
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
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.example.model.AudioStreamFormat
import com.example.model.BufferLatencyPreset
import com.example.model.HeaderMode
import com.example.model.ProtocolMode
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MatteDarkInset
import com.example.ui.theme.MatteDarkInsetBorder
import com.example.ui.theme.MatteDarkPill
import com.example.ui.theme.MatteDarkPillBorder
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // =========================================================================
        // AUDIO CONFIGURATION (M3 Dropdown for Audio Format)
        // =========================================================================
        SettingsSectionHeader(title = "AUDIO CONFIGURATION")

        Card(
            modifier = Modifier.fillMaxWidth().testTag("audio_format_card"),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, DarkCardBorder),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Audio Format Dropdown
                var formatDropdownExpanded by remember { mutableStateOf(false) }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.Audiotrack,
                        title = "Audio Type & Format"
                    )

                    ExposedDropdownMenuBox(
                        expanded = formatDropdownExpanded,
                        onExpandedChange = { formatDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = "${prefs.audioFormat.displayName} (${prefs.audioFormat.bitrateKbps} kbps)",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatDropdownExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MatteDarkInset,
                                unfocusedContainerColor = MatteDarkInset,
                                focusedBorderColor = Color(0xFF4A4A58),
                                unfocusedBorderColor = MatteDarkInsetBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                                .testTag("audio_format_dropdown_field")
                        )

                        ExposedDropdownMenu(
                            expanded = formatDropdownExpanded,
                            onDismissRequest = { formatDropdownExpanded = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            formatCaps.forEach { cap ->
                                val isSelected = prefs.audioFormat == cap.format
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "${cap.format.displayName} · ${cap.format.bitrateKbps} kbps",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color.White else TextSecondary
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = "Selected",
                                                    tint = StreamEmerald,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateAudioFormat(cap.format)
                                        formatDropdownExpanded = false
                                    },
                                    modifier = Modifier.testTag("format_option_${cap.format.sampleRate}_${cap.format.channelCount}")
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // Buffer Latency Preset Dropdown
                var bufferDropdownExpanded by remember { mutableStateOf(false) }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.Speed,
                        title = "Buffer & Latency Preset"
                    )

                    ExposedDropdownMenuBox(
                        expanded = bufferDropdownExpanded,
                        onExpandedChange = { bufferDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = prefs.bufferPreset.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bufferDropdownExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MatteDarkInset,
                                unfocusedContainerColor = MatteDarkInset,
                                focusedBorderColor = Color(0xFF4A4A58),
                                unfocusedBorderColor = MatteDarkInsetBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = bufferDropdownExpanded,
                            onDismissRequest = { bufferDropdownExpanded = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            BufferLatencyPreset.values().forEach { preset ->
                                val isSelected = prefs.bufferPreset == preset
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = preset.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color.White else TextSecondary
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = "Selected",
                                                    tint = StreamEmerald,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateBufferPreset(preset)
                                        bufferDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // Header Mode Dropdown
                var headerDropdownExpanded by remember { mutableStateOf(false) }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.Tune,
                        title = "Stream Header Mode"
                    )

                    ExposedDropdownMenuBox(
                        expanded = headerDropdownExpanded,
                        onExpandedChange = { headerDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = prefs.headerMode.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = headerDropdownExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MatteDarkInset,
                                unfocusedContainerColor = MatteDarkInset,
                                focusedBorderColor = Color(0xFF4A4A58),
                                unfocusedBorderColor = MatteDarkInsetBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = headerDropdownExpanded,
                            onDismissRequest = { headerDropdownExpanded = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            HeaderMode.values().forEach { mode ->
                                val isSelected = prefs.headerMode == mode
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = mode.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color.White else TextSecondary
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = "Selected",
                                                    tint = StreamEmerald,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateHeaderMode(mode)
                                        headerDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // NETWORK & TRANSMISSION (Clean, Single-Line Settings with Icons)
        // =========================================================================
        SettingsSectionHeader(title = "NETWORK & TRANSMISSION")

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, DarkCardBorder),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Phone IP Banner
                val localPhoneIp = remember { NetworkUtils.getLocalIpAddress(context) ?: "Unavailable" }
                Surface(
                    color = MatteDarkInset,
                    border = BorderStroke(1.dp, MatteDarkInsetBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("Phone IP", localPhoneIp)
                            clipboard?.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied IP: $localPhoneIp", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PhoneAndroid,
                                contentDescription = "Phone IP",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Phone Wi-Fi IP",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = localPhoneIp,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy",
                                tint = TextTertiary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                // Protocol Mode Dropdown
                var protocolDropdownExpanded by remember { mutableStateOf(false) }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.Router,
                        title = "Protocol Mode"
                    )

                    ExposedDropdownMenuBox(
                        expanded = protocolDropdownExpanded,
                        onExpandedChange = { protocolDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = prefs.protocolMode.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolDropdownExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MatteDarkInset,
                                unfocusedContainerColor = MatteDarkInset,
                                focusedBorderColor = Color(0xFF4A4A58),
                                unfocusedBorderColor = MatteDarkInsetBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )

                        ExposedDropdownMenu(
                            expanded = protocolDropdownExpanded,
                            onDismissRequest = { protocolDropdownExpanded = false },
                            modifier = Modifier.background(DarkSurface)
                        ) {
                            ProtocolMode.values().forEach { mode ->
                                val isSelected = prefs.protocolMode == mode
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = mode.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color.White else TextSecondary
                                            )
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Check,
                                                    contentDescription = "Selected",
                                                    tint = StreamEmerald,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.updateProtocolMode(mode)
                                        protocolDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Port TextFields
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tcpPortText,
                        onValueChange = {
                            tcpPortText = it
                            val p = it.toIntOrNull()
                            if (p != null) viewModel.updateNetworkSettings(p, prefs.httpPort, prefs.connectionTimeoutMs, prefs.autoReconnect, prefs.maxReconnectRetries)
                        },
                        label = { Text("TCP Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MatteDarkInset,
                            unfocusedContainerColor = MatteDarkInset,
                            focusedBorderColor = Color(0xFF4A4A58),
                            unfocusedBorderColor = MatteDarkInsetBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = httpPortText,
                        onValueChange = {
                            httpPortText = it
                            val p = it.toIntOrNull()
                            if (p != null) viewModel.updateNetworkSettings(prefs.targetPort, p, prefs.connectionTimeoutMs, prefs.autoReconnect, prefs.maxReconnectRetries)
                        },
                        label = { Text("HTTP Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MatteDarkInset,
                            unfocusedContainerColor = MatteDarkInset,
                            focusedBorderColor = Color(0xFF4A4A58),
                            unfocusedBorderColor = MatteDarkInsetBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // Setting Item: Auto-Reconnect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.Sync,
                        title = "Auto-Reconnect on Network Drop"
                    )
                    Switch(
                        checked = prefs.autoReconnect,
                        onCheckedChange = {
                            viewModel.updateNetworkSettings(prefs.targetPort, prefs.httpPort, prefs.connectionTimeoutMs, it, prefs.maxReconnectRetries)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF383844),
                            uncheckedThumbColor = Color(0xFF71717A),
                            uncheckedTrackColor = Color(0xFF1E1E26)
                        )
                    )
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // Setting Item: Rate Pacing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.ElectricBolt,
                        title = "Zero-Jitter Rate Pacing"
                    )
                    Switch(
                        checked = prefs.ratePacing,
                        onCheckedChange = { viewModel.updateRatePacing(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF383844),
                            uncheckedThumbColor = Color(0xFF71717A),
                            uncheckedTrackColor = Color(0xFF1E1E26)
                        ),
                        modifier = Modifier.testTag("rate_pacing_switch")
                    )
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // Setting Item: Silence Phone Speaker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.VolumeOff,
                        title = "Silence Phone Speaker During Stream"
                    )
                    Switch(
                        checked = prefs.mutePhoneWhileStreaming,
                        onCheckedChange = { viewModel.updateMutePhoneWhileStreaming(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF383844),
                            uncheckedThumbColor = Color(0xFF71717A),
                            uncheckedTrackColor = Color(0xFF1E1E26)
                        ),
                        modifier = Modifier.testTag("mute_phone_speaker_switch")
                    )
                }
            }
        }

        // =========================================================================
        // APPEARANCE & DIAGNOSTICS (Single-Line Items with Icons)
        // =========================================================================
        SettingsSectionHeader(title = "SYSTEM & DIAGNOSTICS")

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, DarkCardBorder),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // AMOLED Dark Theme Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.DarkMode,
                        title = "AMOLED Pure Black Theme"
                    )
                    Switch(
                        checked = prefs.amoledDarkTheme,
                        onCheckedChange = { viewModel.updateAmoledDarkTheme(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF383844),
                            uncheckedThumbColor = Color(0xFF71717A),
                            uncheckedTrackColor = Color(0xFF1E1E26)
                        )
                    )
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // Audio Interruption Logs Item
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showLogsDialog = true }
                        .testTag("audio_interruption_logs_card"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.History,
                        title = "Audio Interruption Logs"
                    )

                    Surface(
                        color = MatteDarkPill,
                        border = BorderStroke(1.dp, MatteDarkPillBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (interruptionLogs.isEmpty()) "0 Drops" else "${interruptionLogs.size} Event${if (interruptionLogs.size > 1) "s" else ""}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (interruptionLogs.isEmpty()) StreamEmerald else Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // About Item
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingLabelWithIcon(
                        icon = Icons.Outlined.Info,
                        title = "C3 Audio Streamer"
                    )

                    Surface(
                        color = MatteDarkPill,
                        border = BorderStroke(1.dp, MatteDarkPillBorder),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "v1.6",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
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

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun SettingLabelWithIcon(
    icon: ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            fontSize = 14.sp
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = Color(0xFF8E8E98),
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
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
        shape = RoundedCornerShape(22.dp),
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
                        tint = TextSecondary
                    )
                }
            }
        },
        text = {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Zero audio drops detected.\nCapture & transmission are running smoothly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StreamEmerald,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs.reversed()) { log ->
                        Surface(
                            color = MatteDarkInset,
                            border = BorderStroke(1.dp, MatteDarkInsetBorder),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    val clip = ClipData.newPlainText("C3 Interruption Logs", fullText)
                    clipboard?.setPrimaryClip(clip)
                    Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
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
