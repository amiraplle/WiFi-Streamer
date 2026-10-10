package com.example.ui.home

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.network.NetworkUtils
import com.example.domain.audio.AudioDspEngine
import com.example.model.AudioSourceType
import com.example.model.CaptureStatus
import com.example.model.ProtocolMode
import com.example.model.StreamingState
import com.example.ui.components.StudioCard
import com.example.ui.components.StudioSwitch
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import com.example.ui.theme.DarkBackground
import com.example.domain.safety.ActiveStreamProcess
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
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
    val activeSafetyProcess by viewModel.activeSafetyProcess.collectAsState()
    val safetyNotice by viewModel.safetyNotice.collectAsState()
    val localIp = remember { NetworkUtils.getLocalIpAddress(context) ?: "Checking Wi-Fi..." }

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
            // Even if denied or in preview emulator, proceed with direct streaming
            viewModel.startStreaming(context, 0, null)
        }
    }

    val isStreaming = telemetry.streamingState == StreamingState.STREAMING
    val isConnecting = telemetry.streamingState == StreamingState.CONNECTING || telemetry.streamingState == StreamingState.RECONNECTING
    val isBrowserActive = activeSafetyProcess == ActiveStreamProcess.BROWSER_STREAM && (isStreaming || isConnecting)

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
        targetValue = if (isStreaming) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // =========================================================================
        // 1. ACTIVE RECEIVER TILE (Compact)
        // =========================================================================
        StudioCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("receiver_status_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToReceivers() }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E1E26)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Router,
                                contentDescription = "Receiver",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            val deviceName = when {
                                prefs.targetHost.contains("s3", ignoreCase = true) -> "ESP32-S3"
                                prefs.targetHost.contains("c3", ignoreCase = true) -> "ESP32-C3"
                                else -> "ESP32 Target"
                            }
                            val titleText = "$deviceName UDP (${prefs.targetHost}:${prefs.targetPort})"
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Phone IP", localIp)
                                        clipboard?.setPrimaryClip(clip)
                                        Toast.makeText(context, "IP copied: $localIp", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Text(
                                    text = "IP: $localIp",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "(Copy)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = Color(0xFFA0A0B8)
                                )
                            }
                        }
                    }

                    // Sleek status pill badge
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = telemetry.streamingState.name,
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Quick Single-Stream Hardware Switcher: ESP32-S3 vs ESP32-C3
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isS3Active = prefs.targetHost == "s3music.local"
                    val isC3Active = prefs.targetHost == "c3music.local"

                    Surface(
                        color = if (isS3Active) Color.White else Color(0xFF1B1B24),
                        border = BorderStroke(1.dp, if (isS3Active) Color.White else Color(0xFF2C2C3A)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !isStreaming) {
                                viewModel.updateTarget("s3music.local", 50005)
                                Toast.makeText(context, "Target set to ESP32-S3 (s3music.local)", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isS3Active) Color.Black else Color(0xFF6E6E82))
                            )
                            Text(
                                text = "ESP32-S3",
                                color = if (isS3Active) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isS3Active) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }

                    Surface(
                        color = if (isC3Active) Color.White else Color(0xFF1B1B24),
                        border = BorderStroke(1.dp, if (isC3Active) Color.White else Color(0xFF2C2C3A)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !isStreaming) {
                                viewModel.updateTarget("c3music.local", 50005)
                                Toast.makeText(context, "Target set to ESP32-C3 (c3music.local)", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isC3Active) Color.Black else Color(0xFF6E6E82))
                            )
                            Text(
                                text = "ESP32-C3",
                                color = if (isC3Active) Color.Black else Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isC3Active) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Find / Scan Local Devices button
                    Surface(
                        color = Color(0xFF1B1B24),
                        border = BorderStroke(1.dp, Color(0xFF2C2C3A)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onNavigateToReceivers() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                tint = Color(0xFF60A5FA),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Find Devices",
                                color = Color(0xFF93C5FD),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 2. TRANSMISSION CENTER (Compact Visualizer, Dropdown, Source & Actions)
        // =========================================================================
        StudioCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("transmission_center_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Compact Glowing Visualizer Disc
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = if (isStreaming) 0.16f else 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = if (isStreaming) 0.30f else 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isStreaming) Icons.Outlined.GraphicEq else Icons.Outlined.Router,
                            contentDescription = "Audio Stream Visualizer",
                            tint = statusColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Material 3 Dropdown for Audio Format & Rate Pacing
                var formatDropdownExpanded by remember { mutableStateOf(false) }
                val supportedFormats = viewModel.supportedFormatCapabilities

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.wrapContentSize(Alignment.Center)) {
                        Surface(
                            color = Color(0xFF1B1B24),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (formatDropdownExpanded) Color.White else Color(0xFF2A2A36)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !isStreaming && !isConnecting) {
                                    formatDropdownExpanded = true
                                }
                                .testTag("audio_format_dropdown_trigger")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Audiotrack,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "${prefs.audioFormat.displayName} • ${prefs.audioFormat.bitrateKbps}k",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    fontSize = 12.sp
                                )
                                if (!isStreaming && !isConnecting) {
                                    Icon(
                                        imageVector = Icons.Outlined.ArrowDropDown,
                                        contentDescription = "Select Format",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = formatDropdownExpanded,
                            onDismissRequest = { formatDropdownExpanded = false },
                            modifier = Modifier
                                .background(Color(0xFF141418))
                                .border(BorderStroke(1.dp, Color(0xFF2C2C38)), RoundedCornerShape(14.dp))
                                .clip(RoundedCornerShape(14.dp))
                                .padding(6.dp)
                                .testTag("audio_format_dropdown_menu")
                        ) {
                            supportedFormats.forEach { cap ->
                                val isSelected = prefs.audioFormat == cap.format
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) Color.White else Color.Transparent)
                                        .clickable {
                                            viewModel.updateAudioFormat(cap.format)
                                            formatDropdownExpanded = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                        .testTag("format_option_${cap.format.sampleRate}_${cap.format.channelCount}")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = cap.format.displayName,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.Black else Color.White
                                            )
                                            Text(
                                                text = cap.format.dacCompatibility,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Normal,
                                                color = if (isSelected) Color(0xFF444444) else TextSecondary
                                            )
                                        }
                                        Text(
                                            text = "${cap.format.bitrateKbps}k",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color(0xFF333333) else TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (prefs.ratePacing) {
                        Surface(
                            color = StreamEmerald.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, StreamEmerald.copy(alpha = 0.35f))
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ElectricBolt,
                                    contentDescription = "Paced",
                                    tint = StreamEmerald,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                // Audio Source Selector: System Audio (Default) vs Microphone
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isInternal = prefs.audioSource == AudioSourceType.INTERNAL_AUDIO
                    val isMic = prefs.audioSource == AudioSourceType.MICROPHONE || prefs.audioSource == AudioSourceType.DIRECT_AUDIO

                    // 1. System Audio (Android OS Screen Cast) - Default
                    OutlinedButton(
                        onClick = {
                            if (!isStreaming) viewModel.selectAudioSource(AudioSourceType.INTERNAL_AUDIO)
                        },
                        enabled = !isStreaming && viewModel.isInternalAudioSupported,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("source_internal_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isInternal) Color(0xFF1E293B) else Color.Transparent
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isInternal) Color(0xFF60A5FA) else Color(0xFF282834)
                        )
                    ) {
                        Icon(
                            Icons.Outlined.PhoneAndroid,
                            contentDescription = "System Audio",
                            tint = if (isInternal) Color(0xFF60A5FA) else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "System Audio",
                            fontSize = 12.sp,
                            fontWeight = if (isInternal) FontWeight.Bold else FontWeight.Normal,
                            color = if (isInternal) Color.White else TextSecondary
                        )
                    }

                    // 2. Microphone / Line-In
                    OutlinedButton(
                        onClick = {
                            if (!isStreaming) viewModel.selectAudioSource(AudioSourceType.MICROPHONE)
                        },
                        enabled = !isStreaming,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("source_mic_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isMic) Color(0xFF22222E) else Color.Transparent
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isMic) Color.White else Color(0xFF282834)
                        )
                    ) {
                        Icon(
                            Icons.Outlined.Mic,
                            contentDescription = "Mic",
                            tint = if (isMic) Color.White else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Microphone",
                            fontSize = 12.sp,
                            fontWeight = if (isMic) FontWeight.Bold else FontWeight.Normal,
                            color = if (isMic) Color.White else TextSecondary
                        )
                    }
                }

                if (prefs.audioSource == AudioSourceType.INTERNAL_AUDIO) {
                    Text(
                        text = "📱 System Audio: Transmits audio from music and media apps playing on your phone.",
                        fontSize = 11.sp,
                        color = StreamEmerald,
                        modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                    )
                } else {
                    Text(
                        text = "🎤 Microphone: Transmits ambient room audio through the physical phone microphone.",
                        fontSize = 11.sp,
                        color = WarningAmber,
                        modifier = Modifier.padding(top = 2.dp, start = 4.dp)
                    )
                }

                // Silence Phone Speaker Toggle (Compact row with crisp switch)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1A1A22))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (prefs.mutePhoneWhileStreaming) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                            contentDescription = "Silence Phone",
                            tint = if (prefs.mutePhoneWhileStreaming) Color.White else TextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = "Silence Phone Speaker",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontSize = 12.sp
                        )
                    }
                    StudioSwitch(
                        checked = prefs.mutePhoneWhileStreaming,
                        onCheckedChange = { viewModel.setMutePhoneWhileStreaming(it) },
                        modifier = Modifier.testTag("home_silence_phone_switch")
                    )
                }

                // Action Buttons: Start / Stop / Reconnect (Pill-shaped, sleek)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isStreaming || isConnecting) {
                        Button(
                            onClick = { viewModel.stopStreaming(context) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("stop_streaming_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = ErrorCoral),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = "Stop", tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Stream", fontWeight = FontWeight.Bold, color = Color.Black)
                        }

                        IconButton(
                            onClick = { viewModel.reconnect() },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF22222E))
                                .testTag("reconnect_button")
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "Reconnect",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                if (prefs.audioSource == AudioSourceType.INTERNAL_AUDIO) {
                                    val hasAudioPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (!hasAudioPermission) {
                                        recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                        return@Button
                                    }

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                                    } else {
                                        Toast.makeText(context, "Internal audio requires Android 10+", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    // Microphone mode
                                    val hasAudioPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        android.Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                    if (!hasAudioPermission) {
                                        recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                        return@Button
                                    }
                                    viewModel.startStreaming(context, 0, null)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("start_streaming_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isBrowserActive) WarningAmber else Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isBrowserActive) Icons.Outlined.Security else Icons.Outlined.PlayArrow,
                                contentDescription = "Start",
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isBrowserActive) {
                                    "Switch Stream (Auto-stops Browser)"
                                } else {
                                    "Start Streaming"
                                },
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. REMOTE RECEIVER VOLUME & INTERCEPTION (Compact)
        // =========================================================================
        StudioCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Receiver Volume",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (prefs.isMuted) "MUTED" else "${prefs.transmissionVolume}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (prefs.isMuted) ErrorCoral else Color.White
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.toggleMute() },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("mute_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (prefs.isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                            contentDescription = if (prefs.isMuted) "Unmute" else "Mute",
                            tint = if (prefs.isMuted) ErrorCoral else TextSecondary,
                            modifier = Modifier.size(18.dp)
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
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF4A4A58),
                            inactiveTrackColor = Color(0xFF22222A)
                        )
                    )
                }
            }
        }

        // =========================================================================
        // 4. IN-APP AUDIO DSP & EQUALIZER (Compact)
        // =========================================================================
        StudioCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "DSP",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Audio DSP & Equalizer",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    StudioSwitch(
                        checked = prefs.dspEnabled,
                        onCheckedChange = { viewModel.setDspEnabled(it) },
                        modifier = Modifier.testTag("dsp_master_switch")
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Hardware Protection: Peak Limiter
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1A1A22),
                    border = BorderStroke(1.dp, if (prefs.softLimiterEnabled) StreamEmerald.copy(alpha = 0.35f) else Color(0xFF262630))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                imageVector = Icons.Outlined.Security,
                                contentDescription = "Protection",
                                tint = if (prefs.softLimiterEnabled) StreamEmerald else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Soft Peak Limiter (0 dBFS)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                        }
                        StudioSwitch(
                            checked = prefs.softLimiterEnabled,
                            onCheckedChange = { viewModel.setSoftLimiter(it) },
                            modifier = Modifier.testTag("soft_limiter_switch")
                        )
                    }
                }

                if (prefs.dspEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))

                    // Bass Boost
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Bass",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary,
                            modifier = Modifier.width(38.dp)
                        )
                        Slider(
                            value = prefs.bassBoostPercent.toFloat(),
                            onValueChange = { viewModel.setBassBoost(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).testTag("bass_boost_slider"),
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF4A4A58), inactiveTrackColor = Color(0xFF22222A))
                        )
                        Text(
                            text = "${prefs.bassBoostPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.width(32.dp)
                        )
                    }

                    // Treble Clarity
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Treble",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary,
                            modifier = Modifier.width(38.dp)
                        )
                        Slider(
                            value = prefs.trebleClarityPercent.toFloat(),
                            onValueChange = { viewModel.setTrebleClarity(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).testTag("treble_clarity_slider"),
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF4A4A58), inactiveTrackColor = Color(0xFF22222A))
                        )
                        Text(
                            text = "${prefs.trebleClarityPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.width(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 5-Band Presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val presets = listOf(
                            "Flat" to AudioDspEngine.PRESET_FLAT,
                            "Bass" to AudioDspEngine.PRESET_BASS_BOOST,
                            "Vocal" to AudioDspEngine.PRESET_VOCAL,
                            "Acoustic" to AudioDspEngine.PRESET_ACOUSTIC,
                            "Rock" to AudioDspEngine.PRESET_ROCK
                        )
                        presets.forEach { (name, gains) ->
                            val isSelected = prefs.eqPresetName.equals(name, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setEqPreset(name, gains) },
                                label = { Text(name, fontSize = 10.sp) },
                                modifier = Modifier.testTag("eq_preset_$name"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF252532),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 5 Bands Sliders
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
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary,
                                modifier = Modifier.width(32.dp)
                            )
                            Slider(
                                value = gain,
                                onValueChange = { viewModel.setEqBand(index, it) },
                                valueRange = -12f..12f,
                                modifier = Modifier.weight(1f).testTag("eq_band_$index"),
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF4A4A58), inactiveTrackColor = Color(0xFF22222A))
                            )
                            Text(
                                text = "${if (gain > 0) "+" else ""}${gain.toInt()}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.width(28.dp)
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 5. LIVE TELEMETRY (Wrapped inside one big StudioCard with header label)
        // =========================================================================
        StudioCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("live_telemetry_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Main Header Label
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Speed,
                        contentDescription = "Telemetry",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Live Telemetry",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                // 2x2 Grid of Telemetry inside the big card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryTile(
                        icon = Icons.Outlined.Speed,
                        value = if (isStreaming) "${telemetry.currentBitrateKbps} kbps" else "0 kbps",
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryTile(
                        icon = Icons.Outlined.CloudUpload,
                        value = telemetry.formattedDataTransmitted,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TelemetryTile(
                        icon = Icons.Outlined.Timer,
                        value = telemetry.formattedDuration,
                        modifier = Modifier.weight(1f)
                    )
                    TelemetryTile(
                        icon = Icons.Outlined.GraphicEq,
                        value = when (telemetry.captureStatus) {
                            CaptureStatus.CAPTURING -> "PCM"
                            CaptureStatus.SILENCE -> "Silence"
                            CaptureStatus.INITIALIZING -> "Init"
                            CaptureStatus.PAUSED -> "Paused"
                            CaptureStatus.ERROR -> "Error"
                            CaptureStatus.IDLE -> "Standby"
                        },
                        modifier = Modifier.weight(1f),
                        iconTint = if (telemetry.captureStatus == CaptureStatus.CAPTURING) StreamEmerald else TextSecondary
                    )
                }

                if (telemetry.reconnectCount > 0) {
                    TelemetryTile(
                        icon = Icons.Outlined.Refresh,
                        value = "${telemetry.reconnectCount} retries",
                        modifier = Modifier.fillMaxWidth(),
                        iconTint = WarningAmber,
                        valueColor = WarningAmber
                    )
                }

                // Dynamic Status Banners
                if (isStreaming && telemetry.streamingState == StreamingState.STREAMING) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("streaming_connected_banner"),
                        color = StreamEmerald.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, StreamEmerald.copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = "Connected", tint = StreamEmerald, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (telemetry.connectedClientAddress != null) {
                                    "Connected (${telemetry.connectedClientAddress}) • Transmitting Audio"
                                } else {
                                    "Connected • Transmitting Audio"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = StreamEmerald,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else if (isStreaming && telemetry.streamingState == StreamingState.CONNECTING) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("streaming_connecting_banner"),
                        color = Color(0xFF1E1E26),
                        border = BorderStroke(1.dp, Color(0xFF333342)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Router, contentDescription = "Listening", tint = WarningAmber, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Listening on port ${prefs.targetPort} • Waiting for connection...",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = WarningAmber,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else if (telemetry.lastError != null && telemetry.streamingState != StreamingState.STREAMING) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("streaming_error_banner"),
                        color = ErrorCoral.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ErrorCoral.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Warning, contentDescription = "Error", tint = ErrorCoral, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = telemetry.lastError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorCoral,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun TelemetryTile(
    icon: ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.White,
    valueColor: Color = TextPrimary
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color(0x30A0A0B8),
                spotColor = Color(0x20FFFFFF)
            ),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131318)),
        border = BorderStroke(
            1.2.dp,
            Brush.verticalGradient(
                listOf(Color(0xFF3E3E50), Color(0xFF22222E))
            )
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = valueColor,
                fontSize = 12.sp
            )
        }
    }
}
