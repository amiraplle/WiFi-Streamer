package com.example.ui.player

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioTrack
import com.example.model.PlayerState
import com.example.model.PlayerRepeatMode
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.StreamEmerald
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onNavigateToSettings: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // SAF File Picker (Select multiple audio files: FLAC, WAV, MP3, M4A, etc.)
    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPickedFiles(uris)
            Toast.makeText(context, "Added ${uris.size} tracks to queue", Toast.LENGTH_SHORT).show()
        }
    }

    // SAF Folder Picker (Select an entire music directory)
    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            Toast.makeText(context, "Scanning folder for music...", Toast.LENGTH_SHORT).show()
            viewModel.loadFolder(treeUri) { count ->
                Toast.makeText(context, "Loaded $count songs from folder", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val currentTrack = uiState.currentTrack
    val isPlaying = uiState.playerState == PlayerState.PLAYING
    val isBuffering = uiState.playerState == PlayerState.BUFFERING

    // Disc rotation animation
    val infiniteTransition = rememberInfiniteTransition(label = "disc_anim")
    val discRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isPlaying) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Pulsing halo glow when playing
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = if (isPlaying) 1.05f else 0.98f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo"
    )

    // Scrubbing slider position
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableFloatStateOf(0f) }

    val displayPositionMs = if (isScrubbing) scrubPositionMs.toLong() else uiState.positionMs
    val totalDurationMs = uiState.durationMs.coerceAtLeast(1L)
    val sliderValue = (displayPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // =========================================================================
        // 1. TOP HEADER & QUICK IMPORT BUTTONS
        // =========================================================================
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onNavigateBack != null) {
                        Surface(
                            color = Color(0xFF1E1E2A),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF2E2E3E)),
                            modifier = Modifier
                                .clickable { onNavigateBack() }
                                .testTag("player_back_to_home_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "Back to Home",
                                    tint = StreamEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Home",
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Column {
                        Text(
                            text = "Hi-Res Player",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Direct UDP Stream to ESP32 DAC",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                // Add Music Actions
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = Color(0xFF1E1E28),
                        border = BorderStroke(1.dp, Color(0xFF323246)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                pickAudioLauncher.launch(
                                    arrayOf(
                                        "audio/*",
                                        "application/ogg",
                                        "application/x-flac"
                                    )
                                )
                            }
                            .testTag("add_files_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Text("Files", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Surface(
                        color = Color(0xFF1E1E28),
                        border = BorderStroke(1.dp, Color(0xFF323246)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { pickFolderLauncher.launch(null) }
                            .testTag("add_folder_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Text("Folder", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 2. HERO NOW PLAYING DISC & PLAYER CONTROLS
        // =========================================================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("player_hero_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkCardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Animated Vinyl Disc / Waveform Visualizer
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        if (isPlaying) StreamEmerald.copy(alpha = 0.25f) else Color(0xFF22222E),
                                        Color(0xFF0F0F14)
                                    )
                                )
                            )
                            .border(
                                1.5.dp,
                                if (isPlaying) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF2E2E3E),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        // Concentric grooves
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .border(1.dp, Color(0xFF252532), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF16161E))
                                    .border(1.dp, Color(0xFF38384C), CircleShape)
                                    .rotate(if (isPlaying) discRotation else 0f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isBuffering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = StreamEmerald
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.MusicNote,
                                        contentDescription = "Music Disc",
                                        tint = if (isPlaying) StreamEmerald else Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Track Title & Artist
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = currentTrack?.title ?: "Select a track to play",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentTrack?.artist ?: "Tap any song from the queue below",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Format Badge Row (Source Audio -> ESP32 Output Format)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Source Format Badge
                        Surface(
                            color = Color(0xFF1A1A24),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF2E2E3E))
                        ) {
                            Text(
                                text = "Source: ${uiState.sourceFormatInfo}",
                                color = Color(0xFFA0A0B8),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Target ESP32 Format Badge with direct in-place Dropdown
                        var formatMenuExpanded by remember { mutableStateOf(false) }

                        Box {
                            Surface(
                                color = StreamEmerald.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, StreamEmerald.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { formatMenuExpanded = true }
                                    .testTag("player_format_selector_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "ESP32: ${uiState.outputFormatInfo}",
                                        color = StreamEmerald,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Icon(
                                        imageVector = Icons.Outlined.ArrowDropDown,
                                        contentDescription = "Change Format",
                                        tint = StreamEmerald,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = formatMenuExpanded,
                                onDismissRequest = { formatMenuExpanded = false },
                                modifier = Modifier
                                    .background(Color(0xFF16161E))
                                    .border(BorderStroke(1.dp, Color(0xFF2E2E3E)), RoundedCornerShape(12.dp))
                                    .clip(RoundedCornerShape(12.dp))
                            ) {
                                viewModel.availableFormats.forEach { format ->
                                    val isSelected = uiState.outputFormatInfo.contains("${format.sampleRate / 1000.0}") && uiState.outputFormatInfo.contains("${format.bitDepth}-bit")
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = format.displayName,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) StreamEmerald else Color.White,
                                                    fontSize = 12.sp
                                                )
                                                Text(
                                                    text = format.dacCompatibility,
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF888898)
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.updateAudioFormat(format)
                                            formatMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Seek Slider & Timers
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = sliderValue,
                            onValueChange = { fraction ->
                                isScrubbing = true
                                scrubPositionMs = fraction * totalDurationMs
                            },
                            onValueChangeFinished = {
                                isScrubbing = false
                                viewModel.seekTo(scrubPositionMs.toLong())
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .testTag("player_seek_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = StreamEmerald,
                                inactiveTrackColor = Color(0xFF2A2A38)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatDuration(displayPositionMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                            Text(
                                text = formatDuration(totalDurationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Main Transport Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle Toggle
                        IconButton(
                            onClick = { viewModel.toggleShuffle() },
                            modifier = Modifier
                                .size(42.dp)
                                .testTag("player_shuffle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (uiState.isShuffle) StreamEmerald else Color(0xFF6E6E82),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Previous Track
                        IconButton(
                            onClick = { viewModel.playPrevious() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_previous_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Play / Pause tactile hero button (64dp)
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .shadow(elevation = 10.dp, shape = CircleShape, ambientColor = Color.White, spotColor = Color.White)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = Color.Black.copy(alpha = 0.2f))
                                ) { viewModel.togglePlayPause() }
                                .testTag("player_play_pause_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Next Track
                        IconButton(
                            onClick = { viewModel.playNext() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_next_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Repeat Toggle
                        IconButton(
                            onClick = { viewModel.toggleRepeat() },
                            modifier = Modifier
                                .size(42.dp)
                                .testTag("player_repeat_button")
                        ) {
                            val icon = when (uiState.repeatMode) {
                                PlayerRepeatMode.ONE -> Icons.Filled.RepeatOne
                                else -> Icons.Filled.Repeat
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = "Repeat",
                                tint = if (uiState.repeatMode != PlayerRepeatMode.OFF) StreamEmerald else Color(0xFF6E6E82),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // =========================================================================
                    // DIRECT TRANSMISSION CONTROLLER & LIVE STATUS
                    // =========================================================================
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .testTag("player_direct_transmission_card"),
                        color = if (isPlaying) StreamEmerald.copy(alpha = 0.10f) else Color(0xFF14141E),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isPlaying) StreamEmerald.copy(alpha = 0.45f) else Color(0xFF282838)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isPlaying) StreamEmerald
                                                else if (uiState.playerState == PlayerState.PAUSED) WarningAmber
                                                else Color(0xFF6E6E82)
                                            )
                                    )
                                    Text(
                                        text = when {
                                            isPlaying -> "TRANSMISSION: ACTIVE"
                                            uiState.playerState == PlayerState.PAUSED -> "TRANSMISSION: PAUSED"
                                            uiState.playerState == PlayerState.BUFFERING -> "TRANSMISSION: BUFFERING…"
                                            else -> "TRANSMISSION: STANDBY"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        letterSpacing = 0.5.sp,
                                        color = if (isPlaying) StreamEmerald
                                                else if (uiState.playerState == PlayerState.PAUSED) WarningAmber
                                                else Color(0xFFA0A0B8)
                                    )
                                }

                                Surface(
                                    color = if (isPlaying) StreamEmerald.copy(alpha = 0.2f) else Color(0xFF222230),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "DIRECT UDP",
                                        color = if (isPlaying) StreamEmerald else Color(0xFF8E8E9E),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Start / Stop Transmission Button
                            Button(
                                onClick = {
                                    if (isPlaying) {
                                        viewModel.stopTransmission()
                                    } else {
                                        viewModel.startTransmission()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .testTag("player_toggle_transmission_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPlaying) ErrorCoral else StreamEmerald
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Outlined.Stop else Icons.Outlined.Podcasts,
                                    contentDescription = if (isPlaying) "Stop Transmission" else "Start Transmission",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPlaying) "Stop Transmission" else "Start Direct Transmission",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. QUEUE HEADER & PLAYLIST SECTION
        // =========================================================================
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playlist Queue (${uiState.playlist.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                if (uiState.playlist.isNotEmpty()) {
                    Surface(
                        color = Color.Transparent,
                        modifier = Modifier.clickable { viewModel.clearQueue() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Text("Clear", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 4. PLAYLIST ITEMS
        // =========================================================================
        if (uiState.playlist.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    color = Color(0xFF14141A),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF242432))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Audiotrack,
                            contentDescription = null,
                            tint = Color(0xFF6E6E82),
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No songs in queue",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Add audio files from storage or scan a folder to begin direct streaming to your ESP32.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = {
                                pickAudioLauncher.launch(arrayOf("audio/*", "application/ogg"))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Choose Songs", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            itemsIndexed(uiState.playlist) { index, track ->
                val isCurrent = index == uiState.currentIndex
                val itemPlaying = isCurrent && isPlaying

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { viewModel.playTrack(index) }
                        .testTag("track_item_$index"),
                    color = if (isCurrent) Color(0xFF1E1E2A) else Color(0xFF121217),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (isCurrent) StreamEmerald.copy(alpha = 0.5f) else Color(0xFF22222E)
                    )
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
                            // Left badge / track number or playing indicator
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (isCurrent) StreamEmerald.copy(alpha = 0.2f) else Color(0xFF1A1A22)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (itemPlaying) {
                                    Icon(
                                        imageVector = Icons.Filled.GraphicEq,
                                        contentDescription = "Playing",
                                        tint = StreamEmerald,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrent) StreamEmerald else TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) Color.White else TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = track.artist,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Right: Duration
                        Text(
                            text = track.durationFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCurrent) StreamEmerald else TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        // Bottom breathing space for smooth scrolling above mini player and bottom nav
        item {
            Spacer(modifier = Modifier.height(70.dp))
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
