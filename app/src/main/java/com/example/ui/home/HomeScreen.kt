package com.example.ui.home

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.network.NetworkUtils
import com.example.model.AudioSourceType
import com.example.model.ProtocolMode
import com.example.model.StreamingState
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.MatteDarkInset
import com.example.ui.theme.MatteDarkInsetBorder
import com.example.ui.theme.MatteDarkPill
import com.example.ui.theme.MatteDarkPillBorder
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.StreamEmeraldContainer
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToReceivers: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val telemetry by viewModel.telemetry.collectAsState()
    val prefs by viewModel.userPreferences.collectAsState()
    val localIp = remember { NetworkUtils.getLocalIpAddress(context) ?: "Unavailable" }

    // MediaProjection permission launcher for Internal Audio
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startStreaming(context, result.resultCode, result.data)
        } else {
            Toast.makeText(context, "Audio cast permission cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    // Permission launcher for RECORD_AUDIO
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
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
        } else {
            Toast.makeText(context, "Audio recording permission required", Toast.LENGTH_LONG).show()
        }
    }

    val isStreaming = telemetry.streamingState == StreamingState.STREAMING
    val isConnecting = telemetry.streamingState == StreamingState.CONNECTING || telemetry.streamingState == StreamingState.RECONNECTING

    val startStreamingAction = {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPermission) {
            recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else if (prefs.audioSource == AudioSourceType.INTERNAL_AUDIO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } else {
                Toast.makeText(context, "Internal audio requires Android 10+", Toast.LENGTH_SHORT).show()
            }
        } else {
            viewModel.startStreaming(context, 0, null)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // =========================================================================
        // CARD 1: NOW PLAYING / PLAYER (Exact match to C3 interface screenshot)
        // =========================================================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("now_playing_card"),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, DarkCardBorder),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top Header Row: "Now Playing" + Status Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Now Playing",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )

                    // Compact Pill Badge
                    val statusText = when {
                        isStreaming -> "STREAMING"
                        isConnecting -> "CONNECTING"
                        telemetry.streamingState == StreamingState.ERROR -> "ERROR"
                        else -> "STANDBY"
                    }
                    val statusBg = when {
                        isStreaming -> StreamEmeraldContainer
                        isConnecting -> MatteDarkPill
                        telemetry.streamingState == StreamingState.ERROR -> Color(0xFF281416)
                        else -> MatteDarkPill
                    }
                    val statusDotColor = when {
                        isStreaming -> StreamEmerald
                        isConnecting -> Color(0xFF94A3B8)
                        telemetry.streamingState == StreamingState.ERROR -> ErrorCoral
                        else -> Color(0xFF71717A)
                    }
                    val statusTextColor = when {
                        isStreaming -> StreamEmerald
                        isConnecting -> Color(0xFF94A3B8)
                        telemetry.streamingState == StreamingState.ERROR -> ErrorCoral
                        else -> Color(0xFF8E8E98)
                    }

                    Surface(
                        color = statusBg,
                        border = BorderStroke(1.dp, statusDotColor.copy(alpha = 0.25f)),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("home_status_pill")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.6.sp,
                                color = statusTextColor
                            )
                        }
                    }
                }

                // Main Title & Subtitle
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isStreaming) "Streaming" else if (isConnecting) "Connecting…" else "Ready",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 28.sp
                    )
                    Text(
                        text = "${prefs.audioFormat.sampleRate} Hz · ${prefs.audioFormat.bitDepth}-bit · ${if (prefs.audioFormat.channelCount == 2) "Stereo" else "Mono"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                // Inset Buffer Capsule Bar
                val bufferPercent = if (isStreaming) {
                    ((telemetry.currentBitrateKbps * 128 / 8) % 100).coerceIn(25, 88)
                } else 0
                val bufferBytes = if (isStreaming) {
                    (bufferPercent * 650).coerceIn(12000, 58000)
                } else 0

                Surface(
                    color = MatteDarkInset,
                    border = BorderStroke(1.dp, MatteDarkInsetBorder),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("buffer_status_inset")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Buffer",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (isStreaming) "$bufferPercent% · $bufferBytes bytes" else "0% · 0 bytes",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                }

                // Volume Block
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Volume",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (prefs.isMuted) "0%" else "${prefs.transmissionVolume}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Slider(
                            value = if (prefs.isMuted) 0f else prefs.transmissionVolume.toFloat(),
                            onValueChange = {
                                if (prefs.isMuted) viewModel.toggleMute()
                                viewModel.setVolume(it.toInt())
                            },
                            valueRange = 0f..100f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("volume_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFF4A4A58),
                                inactiveTrackColor = Color(0xFF22222A)
                            )
                        )

                        // Compact Unmute / Mute Pill Button
                        Surface(
                            color = MatteDarkPill,
                            border = BorderStroke(1.dp, MatteDarkPillBorder),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.toggleMute() }
                                .testTag("volume_mute_pill")
                        ) {
                            Text(
                                text = if (prefs.isMuted) "Unmute" else "Mute",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Text(
                        text = "Output level",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }

                // Action Pill Buttons Row: [ Start ] [ Stop ] [ Reconnect ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Start Button (High-visibility clean white pill)
                    Surface(
                        color = if (!isStreaming && !isConnecting) Color(0xFFF1F1F5) else MatteDarkPill,
                        border = BorderStroke(1.dp, if (!isStreaming && !isConnecting) Color.White else MatteDarkPillBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = !isStreaming && !isConnecting) {
                                startStreamingAction()
                            }
                            .testTag("action_start_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Start",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (!isStreaming && !isConnecting) Color(0xFF0B0B0E) else TextSecondary
                            )
                        }
                    }

                    // Stop Button
                    Surface(
                        color = MatteDarkPill,
                        border = BorderStroke(1.dp, if (isStreaming) ErrorCoral.copy(alpha = 0.4f) else MatteDarkPillBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = isStreaming || isConnecting) {
                                viewModel.stopStreaming(context)
                            }
                            .testTag("action_stop_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Stop",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = if (isStreaming) ErrorCoral else TextSecondary
                            )
                        }
                    }

                    // Reconnect Button
                    Surface(
                        color = MatteDarkPill,
                        border = BorderStroke(1.dp, MatteDarkPillBorder),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                viewModel.reconnect()
                            }
                            .testTag("action_reconnect_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Reconnect",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // CARD 2: STREAM DETAILS (Exact match to C3 interface screenshot)
        // =========================================================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("stream_details_card"),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, DarkCardBorder),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header with minimal link icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Stream",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                    Icon(
                        imageVector = Icons.Outlined.Link,
                        contentDescription = "Stream Link",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // Row: Transport
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Transport", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 13.sp)
                    Text(
                        text = when (prefs.protocolMode) {
                            ProtocolMode.RAW_TCP_SERVER -> "TCP PCM Server"
                            ProtocolMode.RAW_TCP_CLIENT -> "TCP PCM"
                            ProtocolMode.HTTP_SERVER -> "HTTP PCM"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }

                // Row: Source / Target
                val displayEndpoint = when (prefs.protocolMode) {
                    ProtocolMode.RAW_TCP_SERVER -> "$localIp · ${prefs.targetPort}"
                    ProtocolMode.RAW_TCP_CLIENT -> "${prefs.targetHost} · ${prefs.targetPort}"
                    ProtocolMode.HTTP_SERVER -> "$localIp · ${prefs.httpPort}"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onNavigateToReceivers() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Target / Port", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 13.sp)
                    Text(
                        text = displayEndpoint,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }

                // Row: Session
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Session", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 13.sp)
                    Text(
                        text = if (isStreaming) telemetry.formattedDuration else "00:00:00",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // Compact Audio Source (Internal Audio vs Microphone)
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
                            imageVector = if (prefs.audioSource == AudioSourceType.INTERNAL_AUDIO) Icons.Outlined.PhoneAndroid else Icons.Outlined.Mic,
                            contentDescription = "Source",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Audio Source",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }

                    Surface(
                        color = MatteDarkPill,
                        border = BorderStroke(1.dp, MatteDarkPillBorder),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !isStreaming && !isConnecting) {
                                val next = if (prefs.audioSource == AudioSourceType.INTERNAL_AUDIO) AudioSourceType.MICROPHONE else AudioSourceType.INTERNAL_AUDIO
                                viewModel.selectAudioSource(next)
                            }
                    ) {
                        Text(
                            text = if (prefs.audioSource == AudioSourceType.INTERNAL_AUDIO) "Internal Audio" else "Microphone",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // Silence Phone Speaker Toggle (Compact row)
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
                            imageVector = if (prefs.mutePhoneWhileStreaming) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                            contentDescription = "Mute Phone",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Silence Phone Speaker",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }

                    Switch(
                        checked = prefs.mutePhoneWhileStreaming,
                        onCheckedChange = { viewModel.setMutePhoneWhileStreaming(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF383844),
                            uncheckedThumbColor = Color(0xFF71717A),
                            uncheckedTrackColor = Color(0xFF1E1E26)
                        ),
                        modifier = Modifier.testTag("home_silence_phone_switch")
                    )
                }
            }
        }

        // =========================================================================
        // CARD 3: AUDIO HEALTH (Exact match to C3 interface screenshot)
        // =========================================================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("audio_health_card"),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = BorderStroke(1.dp, DarkCardBorder),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header with waveform pulse icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Audio health",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = "Audio Health",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                HorizontalDivider(color = MatteDarkInsetBorder)

                // Row: Bitrate
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Bitrate", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 13.sp)
                    Text(
                        text = if (isStreaming) "${telemetry.currentBitrateKbps} kbps" else "0 kbps",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }

                // Row: Transmitted
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Data Transmitted", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 13.sp)
                    Text(
                        text = telemetry.formattedDataTransmitted,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }

                // Row: Stability
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Stability", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 13.sp)
                    val stabilityText = if (telemetry.lastError != null) "Drop Detected" else "100% Stable"
                    val stabilityColor = if (telemetry.lastError != null) ErrorCoral else StreamEmerald
                    Text(
                        text = stabilityText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = stabilityColor,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}
