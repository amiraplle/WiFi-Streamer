package com.example.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioSourceType
import com.example.model.CaptureStatus
import com.example.model.StreamingState
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToReceivers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val telemetry by viewModel.telemetry.collectAsState()
    val prefs by viewModel.userPreferences.collectAsState()

    // Permission launcher for RECORD_AUDIO
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "Microphone permission is required for audio streaming", Toast.LENGTH_LONG).show()
        }
    }

    // MediaProjection permission launcher for Internal Audio
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startStreaming(context, result.resultCode, result.data)
        } else {
            Toast.makeText(context, "Internal audio capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val isStreaming = telemetry.streamingState == StreamingState.STREAMING
    val isConnecting = telemetry.streamingState == StreamingState.CONNECTING || telemetry.streamingState == StreamingState.RECONNECTING

    val statusColor by animateColorAsState(
        targetValue = when (telemetry.streamingState) {
            StreamingState.STREAMING -> StreamEmerald
            StreamingState.CONNECTING, StreamingState.RECONNECTING -> WarningAmber
            StreamingState.ERROR, StreamingState.DISCONNECTED -> ErrorCoral
            StreamingState.IDLE -> MaterialTheme.colorScheme.primary
        },
        label = "statusColor"
    )

    // Pulse animation when streaming
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isStreaming) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Active Receiver Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToReceivers() }
                .testTag("receiver_status_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = "Receiver",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${prefs.targetHost}:${prefs.targetPort}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = telemetry.streamingState.name,
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Main Transmission Center Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("transmission_center_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Animated Glowing Visualizer Disc
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = if (isStreaming) 0.18f else 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = if (isStreaming) 0.35f else 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isStreaming) Icons.Default.GraphicEq else Icons.Default.Router,
                            contentDescription = "Audio Stream Visualizer",
                            tint = statusColor,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                // Audio Format & Bitrate Chip
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = "${telemetry.format.displayName} • ${telemetry.format.bitrateKbps} kbps",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    if (prefs.ratePacing) {
                        Surface(
                            color = StreamEmerald.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, StreamEmerald.copy(alpha = 0.35f))
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Paced",
                                    tint = StreamEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Audio Source Selector (Internal Audio vs Microphone)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isInternal = prefs.audioSource == AudioSourceType.INTERNAL_AUDIO
                    val isMic = prefs.audioSource == AudioSourceType.MICROPHONE

                    // Internal Audio Button
                    OutlinedButton(
                        onClick = {
                            if (!isStreaming) viewModel.selectAudioSource(AudioSourceType.INTERNAL_AUDIO)
                        },
                        enabled = !isStreaming && viewModel.isInternalAudioSupported,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("source_internal_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isInternal) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isInternal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = "Internal Audio",
                            tint = if (isInternal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Microphone Button
                    OutlinedButton(
                        onClick = {
                            if (!isStreaming) {
                                recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                viewModel.selectAudioSource(AudioSourceType.MICROPHONE)
                            }
                        },
                        enabled = !isStreaming,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("source_mic_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isMic) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isMic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Microphone",
                            tint = if (isMic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Action Buttons: Start / Stop / Reconnect
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isStreaming || isConnecting) {
                        Button(
                            onClick = { viewModel.stopStreaming(context) },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("stop_streaming_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorCoral),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Stop Streaming", fontWeight = FontWeight.Bold, color = Color.Black)
                        }

                        IconButton(
                            onClick = { viewModel.reconnect() },
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("reconnect_button")
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Reconnect",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                if (prefs.audioSource == AudioSourceType.INTERNAL_AUDIO) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                    } else {
                                        Toast.makeText(context, "Internal audio requires Android 10+", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    viewModel.startStreaming(context, 0, null)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("start_streaming_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Start", tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Streaming", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }

        // Remote Receiver Volume & Physical Button Interception Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speaker,
                            contentDescription = "Receiver Volume",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        if (isStreaming) {
                            Surface(
                                color = StreamEmerald.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.PhoneAndroid,
                                        contentDescription = "Phone Muted",
                                        tint = StreamEmerald,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Icon(
                                        Icons.Default.VolumeOff,
                                        contentDescription = "Muted",
                                        tint = StreamEmerald,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = if (prefs.isMuted) "MUTED" else "${prefs.transmissionVolume}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (prefs.isMuted) ErrorCoral else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.toggleMute() },
                        modifier = Modifier.testTag("mute_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (prefs.isMuted) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                            contentDescription = if (prefs.isMuted) "Unmute" else "Mute",
                            tint = if (prefs.isMuted) ErrorCoral else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Slider(
                        value = prefs.transmissionVolume.toFloat(),
                        onValueChange = { viewModel.setVolume(it.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("volume_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        // In-App Audio DSP & Hardware Protection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "DSP",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Switch(
                        checked = prefs.dspEnabled,
                        onCheckedChange = { viewModel.setDspEnabled(it) },
                        modifier = Modifier.testTag("dsp_master_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Hardware Protection: Peak Limiter
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, if (prefs.softLimiterEnabled) StreamEmerald.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Protection",
                                tint = if (prefs.softLimiterEnabled) StreamEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Surface(
                                color = if (prefs.softLimiterEnabled) StreamEmerald.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "0 dBFS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (prefs.softLimiterEnabled) StreamEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Switch(
                            checked = prefs.softLimiterEnabled,
                            onCheckedChange = { viewModel.setSoftLimiter(it) },
                            modifier = Modifier.testTag("soft_limiter_switch")
                        )
                    }
                }

                if (prefs.dspEnabled) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // Bass Boost (Low Frequencies)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Bass",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Slider(
                            value = prefs.bassBoostPercent.toFloat(),
                            onValueChange = { viewModel.setBassBoost(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).testTag("bass_boost_slider")
                        )
                        Text(
                            text = "${prefs.bassBoostPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(36.dp)
                        )
                    }

                    // Treble Clarity (High Frequencies)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "Treble",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Slider(
                            value = prefs.trebleClarityPercent.toFloat(),
                            onValueChange = { viewModel.setTrebleClarity(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).testTag("treble_clarity_slider")
                        )
                        Text(
                            text = "${prefs.trebleClarityPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 5-Band Equalizer Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val presets = listOf(
                            "Flat" to com.example.domain.audio.AudioDspEngine.PRESET_FLAT,
                            "Bass" to com.example.domain.audio.AudioDspEngine.PRESET_BASS_BOOST,
                            "Vocal" to com.example.domain.audio.AudioDspEngine.PRESET_VOCAL,
                            "Acoustic" to com.example.domain.audio.AudioDspEngine.PRESET_ACOUSTIC,
                            "Rock" to com.example.domain.audio.AudioDspEngine.PRESET_ROCK
                        )
                        presets.forEach { (name, gains) ->
                            val isSelected = prefs.eqPresetName.equals(name, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setEqPreset(name, gains) },
                                label = { Text(name, fontSize = 11.sp) },
                                modifier = Modifier.testTag("eq_preset_$name")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 5 Bands
                    val bandFrequencies = listOf("100", "300", "1k", "3.5k", "8k")
                    val currentGains = listOf(prefs.eqBand0, prefs.eqBand1, prefs.eqBand2, prefs.eqBand3, prefs.eqBand4)

                    bandFrequencies.forEachIndexed { index, label ->
                        val gain = currentGains[index]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(36.dp)
                            )
                            Slider(
                                value = gain,
                                onValueChange = { viewModel.setEqBand(index, it) },
                                valueRange = -12f..12f,
                                modifier = Modifier.weight(1f).testTag("eq_band_$index")
                            )
                            Text(
                                text = "${if (gain > 0) "+" else ""}${gain.toInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(32.dp)
                            )
                        }
                    }
                }
            }
        }

        // Live Real-Time Telemetry Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryTile(
                icon = Icons.Default.Speed,
                value = if (isStreaming) "${telemetry.currentBitrateKbps} kbps" else "0 kbps",
                modifier = Modifier.weight(1f)
            )
            TelemetryTile(
                icon = Icons.Default.CloudUpload,
                value = telemetry.formattedDataTransmitted,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryTile(
                icon = Icons.Default.Timer,
                value = telemetry.formattedDuration,
                modifier = Modifier.weight(1f)
            )
            TelemetryTile(
                icon = Icons.Default.GraphicEq,
                value = when (telemetry.captureStatus) {
                    CaptureStatus.CAPTURING -> "PCM"
                    CaptureStatus.SILENCE -> "Silence"
                    CaptureStatus.INITIALIZING -> "Init"
                    CaptureStatus.PAUSED -> "Paused"
                    CaptureStatus.ERROR -> "Error"
                    CaptureStatus.IDLE -> "Standby"
                },
                modifier = Modifier.weight(1f),
                iconTint = if (telemetry.captureStatus == CaptureStatus.CAPTURING) StreamEmerald else MaterialTheme.colorScheme.primary
            )
        }

        if (telemetry.reconnectCount > 0) {
            TelemetryTile(
                icon = Icons.Default.Refresh,
                value = "${telemetry.reconnectCount} retries",
                modifier = Modifier.fillMaxWidth(),
                iconTint = WarningAmber,
                valueColor = WarningAmber
            )
        }

        // Error Banner if present
        if (telemetry.lastError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ErrorCoral.copy(alpha = 0.12f)),
                border = BorderStroke(1.dp, ErrorCoral.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = "Error", tint = ErrorCoral)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = telemetry.lastError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorCoral
                    )
                }
            }
        }
    }
}

@Composable
fun TelemetryTile(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = valueColor
            )
        }
    }
}
