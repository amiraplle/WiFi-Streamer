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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.SurroundSound
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.example.domain.audio.AudioDspEngine
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
import androidx.compose.ui.text.style.TextAlign
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

    var udpPortText by remember(prefs.targetPort) { mutableStateOf(prefs.targetPort.toString()) }
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
        Text(
            text = "Streaming Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // =========================================================================
        // AUDIO CONFIGURATION (Premium Dropdowns & Continuous Buffer Slider)
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
                    itemTitle = { "${it.displayName} [${it.dacCompatibility}] — ${it.bitrateKbps} kbps" },
                    onItemSelected = { viewModel.updateAudioFormat(it) },
                    testTag = "settings_audio_format"
                )

                HorizontalDivider(color = Color(0xFF22222E))

                // Target Buffer & Latency Slider (15ms - 300ms)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Speed,
                                contentDescription = null,
                                tint = Color(0xFF8E8E98),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "TARGET BUFFER & LATENCY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                letterSpacing = 0.6.sp,
                                color = Color(0xFF8E8E98)
                            )
                        }

                        Surface(
                            color = StreamEmerald.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, StreamEmerald.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = "${prefs.targetBufferMs} ms",
                                color = StreamEmerald,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    val latencyDescription = when {
                        prefs.targetBufferMs <= 40 -> "Ultra-Low Latency • Best for strong 5GHz Wi-Fi (<40ms)"
                        prefs.targetBufferMs <= 90 -> "Low Latency • Fast response with light jitter shield"
                        prefs.targetBufferMs <= 180 -> "Balanced • Absorbs UI touches, gestures & notifications"
                        else -> "Rock-Solid • Maximum immunity against app switches & OS pauses"
                    }

                    Text(
                        text = latencyDescription,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = Color(0xFF8E8E98),
                        modifier = Modifier.padding(start = 2.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("15ms", fontSize = 10.sp, color = TextSecondary)
                        Slider(
                            value = prefs.targetBufferMs.toFloat(),
                            onValueChange = { viewModel.updateTargetBufferMs(it.toInt()) },
                            valueRange = 15f..300f,
                            steps = 56, // ~5ms step increments
                            modifier = Modifier
                                .weight(1f)
                                .testTag("settings_target_buffer_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = StreamEmerald,
                                inactiveTrackColor = Color(0xFF262634)
                            )
                        )
                        Text("300ms", fontSize = 10.sp, color = TextSecondary)
                    }

                    // Quick Preset Shortcut Pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val quickPresets = listOf(
                            "25 ms" to 25,
                            "50 ms" to 50,
                            "120 ms" to 120,
                            "250 ms" to 250
                        )
                        quickPresets.forEach { (label, ms) ->
                            val isSelected = prefs.targetBufferMs == ms
                            Surface(
                                color = if (isSelected) StreamEmerald.copy(alpha = 0.2f) else Color(0xFF14141E),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (isSelected) StreamEmerald else Color(0xFF282836)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewModel.updateTargetBufferMs(ms) }
                                    .testTag("buffer_preset_$ms")
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) StreamEmerald else TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

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

                // Dedicated UDP Audio Transport Info Card
                Surface(
                    color = Color(0xFF111116),
                    border = BorderStroke(1.dp, Color(0xFF282834)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Router,
                            contentDescription = "UDP Transport",
                            tint = StreamEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "STREAM PROTOCOL",
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF8E8E98)
                            )
                            Text(
                                text = "UDP Datagram (Real-time, zero stall)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                        }
                    }
                }

                // Port & Timeout text fields
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = udpPortText,
                        onValueChange = {
                            udpPortText = it
                            val p = it.toIntOrNull()
                            if (p != null) viewModel.updateNetworkSettings(p, prefs.connectionTimeoutMs, prefs.autoReconnect, prefs.maxReconnectRetries)
                        },
                        label = { Text("UDP PORT", fontSize = 10.sp, letterSpacing = 0.5.sp) },
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
                        value = timeoutText,
                        onValueChange = {
                            timeoutText = it
                            val t = it.toIntOrNull()
                            if (t != null) viewModel.updateNetworkSettings(prefs.targetPort, t, prefs.autoReconnect, prefs.maxReconnectRetries)
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
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = retriesText,
                        onValueChange = {
                            retriesText = it
                            val r = it.toIntOrNull()
                            if (r != null) viewModel.updateNetworkSettings(prefs.targetPort, prefs.connectionTimeoutMs, prefs.autoReconnect, r)
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
                        modifier = Modifier.fillMaxWidth(),
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
                            viewModel.updateNetworkSettings(prefs.targetPort, prefs.connectionTimeoutMs, it, prefs.maxReconnectRetries)
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

                // Wi-Fi QoS Voice Priority (WMM Voice / DSCP 46) Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        SettingItemHeader(
                            icon = Icons.Outlined.Wifi,
                            label = "Wi-Fi QoS Voice Priority"
                        )
                        Text(
                            text = "Tags UDP packets with WMM Voice (DSCP 46 / 0xB8) to prevent packet loss under heavy network traffic",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = Color(0xFF8E8E98),
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                        )
                    }
                    StudioSwitch(
                        checked = prefs.wifiQosEnabled,
                        onCheckedChange = { viewModel.updateWifiQos(it) },
                        modifier = Modifier.testTag("wifi_qos_switch")
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
        // AUDIOPHILE ENGINE TWEAKS (Optional Pro Toggles)
        // =========================================================================
        SettingsHeader(title = "AUDIOPHILE ENGINE TWEAKS")

        StudioCard(
            modifier = Modifier.fillMaxWidth().testTag("audiophile_tweaks_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Bit-Perfect Direct Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        SettingItemHeader(
                            icon = Icons.Outlined.GraphicEq,
                            label = "Bit-Perfect Direct Mode"
                        )
                        Text(
                            text = "Bypasses volume math, EQ, and limiter stages for 100% bit-exact bitstream to external DAC",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = Color(0xFF8E8E98),
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                        )
                    }
                    StudioSwitch(
                        checked = prefs.bitPerfectMode,
                        onCheckedChange = { viewModel.updateBitPerfectMode(it) },
                        modifier = Modifier.testTag("bit_perfect_switch")
                    )
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // 2. TPDF Dithering & Anti-Truncation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        SettingItemHeader(
                            icon = Icons.Outlined.Bolt,
                            label = "TPDF Dithering (Anti-Truncation)"
                        )
                        Text(
                            text = "Applies Triangular PDF dither during volume changes to eliminate quantization distortion and preserve micro-details",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = Color(0xFF8E8E98),
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                        )
                    }
                    StudioSwitch(
                        checked = prefs.ditherEnabled,
                        onCheckedChange = { viewModel.updateDitherEnabled(it) },
                        modifier = Modifier.testTag("dither_switch")
                    )
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // 3. Warm Radio Keep-Alive Carrier
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        SettingItemHeader(
                            icon = Icons.Outlined.Podcasts,
                            label = "Warm Radio Keep-Alive Carrier"
                        )
                        Text(
                            text = "Transmits low-rate silence packets during pauses to keep ESP32 Wi-Fi & I2S PLL locked for instant resume",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = Color(0xFF8E8E98),
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                        )
                    }
                    StudioSwitch(
                        checked = prefs.keepAliveSilence,
                        onCheckedChange = { viewModel.updateKeepAliveSilence(it) },
                        modifier = Modifier.testTag("keep_alive_carrier_switch")
                    )
                }

            }
        }

        // =========================================================================
        // DSP & AUDIO PROCESSING (Controllable Audiophile Suite)
        // =========================================================================
        SettingsHeader(title = "DSP & AUDIO PROCESSING")

        StudioCard(
            modifier = Modifier.fillMaxWidth().testTag("dsp_settings_card")
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Master DSP Engine Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        SettingItemHeader(
                            icon = Icons.Outlined.Equalizer,
                            label = "Master DSP Engine"
                        )
                        Text(
                            text = "Enables real-time 64-bit biquad filtering, EQ, and sound enhancement modules",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = Color(0xFF8E8E98),
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                        )
                    }
                    StudioSwitch(
                        checked = prefs.dspEnabled,
                        onCheckedChange = { viewModel.updateDspEnabled(it) },
                        modifier = Modifier.testTag("settings_dsp_master_switch")
                    )
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // 2. Soft-Knee Peak Limiter (-1.36 dBFS Anti-Clipping)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        SettingItemHeader(
                            icon = Icons.Outlined.Compress,
                            label = "Soft-Knee Peak Limiter"
                        )
                        Text(
                            text = "Asymptotic saturation curve at -1.36 dBFS to prevent DAC rail clipping and crackling",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = Color(0xFF8E8E98),
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                        )
                    }
                    StudioSwitch(
                        checked = prefs.softLimiterEnabled,
                        onCheckedChange = { viewModel.updateSoftLimiter(it) },
                        modifier = Modifier.testTag("settings_soft_limiter_switch")
                    )
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // 3. Preamp / Limiter Trim Slider
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            SettingItemHeader(
                                icon = Icons.Outlined.Tune,
                                label = "Preamp / Limiter Trim"
                            )
                            Text(
                                text = "Adjust digital headroom & gain trim before transmission (default 0.0 dB)",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = Color(0xFF8E8E98),
                                modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                            )
                        }

                        Surface(
                            color = if (prefs.limiterTrimDb == 0f) Color(0xFF1E1E28) else StreamEmerald.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (prefs.limiterTrimDb == 0f) Color(0xFF2C2C3C) else StreamEmerald.copy(alpha = 0.5f)),
                            modifier = Modifier.clickable { viewModel.updateLimiterTrim(0f) }
                        ) {
                            Text(
                                text = if (prefs.limiterTrimDb > 0f) "+${String.format("%.1f", prefs.limiterTrimDb)} dB"
                                       else if (prefs.limiterTrimDb < 0f) "${String.format("%.1f", prefs.limiterTrimDb)} dB"
                                       else "0.0 dB",
                                color = if (prefs.limiterTrimDb == 0f) TextPrimary else StreamEmerald,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 25.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("-6 dB", fontSize = 10.sp, color = TextSecondary)
                        Slider(
                            value = prefs.limiterTrimDb,
                            onValueChange = { viewModel.updateLimiterTrim(it) },
                            valueRange = -6f..6f,
                            steps = 23,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("limiter_trim_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = StreamEmerald,
                                activeTrackColor = StreamEmerald,
                                inactiveTrackColor = Color(0xFF262634)
                            )
                        )
                        Text("+6 dB", fontSize = 10.sp, color = TextSecondary)
                    }
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // 4. Air Band High-Shelf (14 kHz)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            SettingItemHeader(
                                icon = Icons.Outlined.Waves,
                                label = "Air Band High-Shelf (14 kHz)"
                            )
                            Text(
                                text = "Silky high-end sheen & open room ambiance without sibilance harshness",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = Color(0xFF8E8E98),
                                modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                            )
                        }
                        StudioSwitch(
                            checked = prefs.airBandEnabled,
                            onCheckedChange = { viewModel.updateAirBandEnabled(it) },
                            modifier = Modifier.testTag("air_band_switch")
                        )
                    }

                    if (prefs.airBandEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 25.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Gain", fontSize = 10.sp, color = TextSecondary)
                            Slider(
                                value = prefs.airBandGainDb,
                                onValueChange = { viewModel.updateAirBandGain(it) },
                                valueRange = 0f..6f,
                                steps = 11,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("air_band_gain_slider"),
                                colors = SliderDefaults.colors(
                                    thumbColor = StreamEmerald,
                                    activeTrackColor = StreamEmerald,
                                    inactiveTrackColor = Color(0xFF262634)
                                )
                            )
                            Text("+${String.format("%.1f", prefs.airBandGainDb)} dB", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = StreamEmerald)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // 5. Tight Bass Punch & Mud-Cut (65 Hz & 280 Hz)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            SettingItemHeader(
                                icon = Icons.Outlined.Bolt,
                                label = "Tight Bass Punch & Mud-Cut"
                            )
                            Text(
                                text = "Boosts 65 Hz kick punch while notching 280 Hz boxy mud to keep bass tight",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = Color(0xFF8E8E98),
                                modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                            )
                        }
                        StudioSwitch(
                            checked = prefs.bassPunchEnabled,
                            onCheckedChange = { viewModel.updateBassPunchEnabled(it) },
                            modifier = Modifier.testTag("bass_punch_switch")
                        )
                    }

                    if (prefs.bassPunchEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 25.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Punch", fontSize = 10.sp, color = TextSecondary)
                            Slider(
                                value = prefs.bassPunchIntensity.toFloat(),
                                onValueChange = { viewModel.updateBassPunchIntensity(it.toInt()) },
                                valueRange = 0f..100f,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("bass_punch_slider"),
                                colors = SliderDefaults.colors(
                                    thumbColor = StreamEmerald,
                                    activeTrackColor = StreamEmerald,
                                    inactiveTrackColor = Color(0xFF262634)
                                )
                            )
                            Text("${prefs.bassPunchIntensity}%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = StreamEmerald)
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // 6. Sub-Bass Infrasonic Rumble Cut (28 Hz HPF)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        SettingItemHeader(
                            icon = Icons.Outlined.VolumeOff,
                            label = "Sub-Bass Rumble Cut (28 Hz HPF)"
                        )
                        Text(
                            text = "Filters inaudible <28 Hz energy to save DAC power and prevent small driver flutter",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = Color(0xFF8E8E98),
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                        )
                    }
                    StudioSwitch(
                        checked = prefs.subBassCutEnabled,
                        onCheckedChange = { viewModel.updateSubBassCut(it) },
                        modifier = Modifier.testTag("sub_bass_cut_switch")
                    )
                }

                HorizontalDivider(color = Color(0xFF22222E))

                // 7. Frequency-Split Stereo Widener
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            SettingItemHeader(
                                icon = Icons.Outlined.SurroundSound,
                                label = "Split-Band Stereo Widener"
                            )
                            Text(
                                text = "Locks sub-150Hz bass centered in mono while expanding stereo width for mids & highs",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = Color(0xFF8E8E98),
                                modifier = Modifier.padding(start = 25.dp, top = 2.dp)
                            )
                        }
                        StudioSwitch(
                            checked = prefs.stereoWidenerEnabled,
                            onCheckedChange = { viewModel.updateStereoWidener(it) },
                            modifier = Modifier.testTag("stereo_widener_switch")
                        )
                    }

                    if (prefs.stereoWidenerEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 25.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Width", fontSize = 10.sp, color = TextSecondary)
                            Slider(
                                value = prefs.stereoWidthPercent.toFloat(),
                                onValueChange = { viewModel.updateStereoWidth(it.toInt()) },
                                valueRange = 100f..150f,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("stereo_width_slider"),
                                colors = SliderDefaults.colors(
                                    thumbColor = StreamEmerald,
                                    activeTrackColor = StreamEmerald,
                                    inactiveTrackColor = Color(0xFF262634)
                                )
                            )
                            Text("${prefs.stereoWidthPercent}%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = StreamEmerald)
                        }
                    }
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
                    SettingItemHeader(icon = Icons.Outlined.Info, label = "Audio Studio (v1.6)")

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
