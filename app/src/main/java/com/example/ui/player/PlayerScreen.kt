package com.example.ui.player

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SurroundSound
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.data.preferences.UserPreferences
import com.example.domain.audio.AudioDspEngine
import com.example.ui.components.DspModalBottomSheet
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioTrack
import com.example.model.PlayerRepeatMode
import com.example.model.PlayerState
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val savedFolder by viewModel.savedFolderName.collectAsState()
    val isRescanning by viewModel.isRescanning.collectAsState()
    val prefs by viewModel.userPreferences.collectAsState()

    var showDspSheet by remember { mutableStateOf(false) }

    // SAF File Picker (Select audio files: OPUS, FLAC, WAV, MP3, M4A, OGG)
    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPickedFiles(uris)
            Toast.makeText(context, "Added ${uris.size} tracks to queue", Toast.LENGTH_SHORT).show()
        }
    }

    // SAF Folder Picker (Select entire music folder)
    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Throwable) {}

                Toast.makeText(context, "Scanning folder for audio...", Toast.LENGTH_SHORT).show()
                viewModel.loadFolder(treeUri) { count ->
                    Toast.makeText(
                        context,
                        if (count > 0) "Loaded $count songs from folder" else "No audio files found in folder",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Throwable) {
                Toast.makeText(context, "Could not load folder: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val currentTrack = uiState.currentTrack
    val isPlaying = uiState.playerState == PlayerState.PLAYING
    val isBuffering = uiState.playerState == PlayerState.BUFFERING

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
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // =========================================================================
        // 1. MINIMAL TOP HEADER
        // =========================================================================
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hi-Res Player",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Direct UDP Stream to ESP32 DAC",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Right: Status Pill
                Surface(
                    color = if (isPlaying) StreamEmerald.copy(alpha = 0.15f) else Color(0xFF1C1C26),
                    border = BorderStroke(1.dp, if (isPlaying) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF2E2E3E)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isPlaying) StreamEmerald
                                    else if (uiState.playerState == PlayerState.PAUSED) WarningAmber
                                    else Color(0xFF6E6E82)
                                )
                        )
                        Text(
                            text = if (isPlaying) "STREAMING" else if (uiState.playerState == PlayerState.PAUSED) "PAUSED" else "STANDBY",
                            color = if (isPlaying) StreamEmerald else if (uiState.playerState == PlayerState.PAUSED) WarningAmber else Color(0xFFA0A0B8),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        // =========================================================================
        // 2. REDESIGNED COMPACT & ATTRACTIVE NOW PLAYING CARD
        // =========================================================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("player_hero_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, if (isPlaying) StreamEmerald.copy(alpha = 0.35f) else DarkCardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Top Section: Compact 74dp Album Artwork + Track Information
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Compact Album Artwork (74dp x 74dp)
                        val albumArtBitmap = remember(currentTrack?.id, currentTrack?.artworkBytes) {
                            ArtworkCache.getOrDecode(currentTrack?.id.orEmpty(), currentTrack?.artworkBytes)
                        }

                        val artScale by animateFloatAsState(
                            targetValue = if (isPlaying) 1.0f else 0.96f,
                            animationSpec = tween(300, easing = FastOutSlowInEasing),
                            label = "artScale"
                        )

                        Box(
                            modifier = Modifier
                                .size(74.dp)
                                .scale(artScale)
                                .shadow(
                                    elevation = if (isPlaying) 10.dp else 4.dp,
                                    shape = RoundedCornerShape(12.dp),
                                    ambientColor = if (isPlaying) StreamEmerald.copy(alpha = 0.4f) else Color.Black,
                                    spotColor = if (isPlaying) StreamEmerald.copy(alpha = 0.5f) else Color.Black
                                )
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF14141E))
                                .border(
                                    1.dp,
                                    if (isPlaying) StreamEmerald.copy(alpha = 0.5f) else Color(0xFF282838),
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (albumArtBitmap != null) {
                                Image(
                                    bitmap = albumArtBitmap,
                                    contentDescription = "Album Artwork for ${currentTrack?.title}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    Color(0xFF262638),
                                                    Color(0xFF151520),
                                                    if (isPlaying) StreamEmerald.copy(alpha = 0.18f) else Color(0xFF1A1A26)
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Audiotrack,
                                        contentDescription = "Music Artwork",
                                        tint = if (isPlaying) StreamEmerald else Color(0xFFA0A0B8),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            // Distinct Format Badge Overlay in Bottom-End (e.g. OPUS, FLAC)
                            val codecName = currentTrack?.formatDisplay ?: "AUDIO"
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                                    .border(0.5.dp, StreamEmerald.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = codecName,
                                    color = StreamEmerald,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            if (isBuffering) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.65f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = StreamEmerald
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Title, Artist, & Format Specs
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = currentTrack?.title ?: "Select a track to play",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentTrack?.artist ?: "Tap any song from the queue below",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Specs & Codec Pill
                            val trackCodecDisplay = if (currentTrack != null) {
                                if (uiState.sourceFormatInfo.isNotBlank() && uiState.sourceFormatInfo != "No audio loaded") {
                                    uiState.sourceFormatInfo
                                } else {
                                    "${currentTrack.formatDisplay} • ${currentTrack.sampleRate / 1000.0} kHz • ${if (currentTrack.channels == 2) "Stereo" else "Mono"}"
                                }
                            } else {
                                "No track selected"
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Surface(
                                    color = Color(0xFF1B1B26),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, Color(0xFF2C2C3E))
                                ) {
                                    Text(
                                        text = trackCodecDisplay,
                                        color = Color(0xFFB0B0C4),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Seek Slider & Timers (Compact, tight spacing)
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
                                .height(22.dp)
                                .testTag("player_seek_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = StreamEmerald,
                                inactiveTrackColor = Color(0xFF2A2A38)
                            )
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatDuration(displayPositionMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                            Text(
                                text = formatDuration(totalDurationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Compact Transport Controls (Shuffle, Prev, Play/Pause, Stop, Next, Repeat)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle Toggle
                        IconButton(
                            onClick = { viewModel.toggleShuffle() },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("player_shuffle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (uiState.isShuffle) StreamEmerald else Color(0xFF6E6E82),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Previous Track
                        IconButton(
                            onClick = { viewModel.playPrevious() },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("player_previous_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Tactile Hero Play / Pause Button (48dp)
                        val playBtnScale by animateFloatAsState(
                            targetValue = if (isPlaying) 1.05f else 1.0f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
                            label = "playBtnScale"
                        )
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .scale(playBtnScale)
                                .shadow(
                                    elevation = if (isPlaying) 12.dp else 6.dp,
                                    shape = CircleShape,
                                    ambientColor = if (isPlaying) StreamEmerald else Color.White,
                                    spotColor = if (isPlaying) StreamEmerald else Color.White
                                )
                                .clip(CircleShape)
                                .background(if (isPlaying) StreamEmerald else Color.White)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = Color.Black.copy(alpha = 0.25f))
                                ) { viewModel.togglePlayPause() }
                                .testTag("player_play_pause_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        // Dedicated Stop Button
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF241418))
                                .border(1.dp, Color(0xFF55222D), CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = Color(0xFFFF5252).copy(alpha = 0.25f))
                                ) { viewModel.stopPlayback() }
                                .testTag("player_stop_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop",
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Next Track
                        IconButton(
                            onClick = { viewModel.playNext() },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("player_next_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Repeat Toggle
                        IconButton(
                            onClick = { viewModel.toggleRepeat() },
                            modifier = Modifier
                                .size(36.dp)
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
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Bottom In-Card Sub-Bar: ESP32 Target Format Selector + Real-Time DSP Button
                    var formatMenuExpanded by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                color = Color(0xFF13131C),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, StreamEmerald.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { formatMenuExpanded = true }
                                    .testTag("player_format_selector_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Podcasts,
                                            contentDescription = null,
                                            tint = StreamEmerald,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "ESP32: ${uiState.outputFormatInfo}",
                                            color = StreamEmerald,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Outlined.ArrowDropDown,
                                        contentDescription = "Select Format",
                                        tint = StreamEmerald,
                                        modifier = Modifier.size(16.dp)
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

                        // Prominent Real-Time DSP Button in Player Card
                        Surface(
                            color = if (prefs.dspEnabled) StreamEmerald.copy(alpha = 0.16f) else Color(0xFF14141E),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (prefs.dspEnabled) StreamEmerald.copy(alpha = 0.6f) else Color(0xFF2A2A3A)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showDspSheet = true }
                                .testTag("player_dsp_badge_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = "Real-Time DSP",
                                    tint = if (prefs.dspEnabled) StreamEmerald else Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (prefs.dspEnabled) "DSP ACTIVE" else "DSP TWEAKS",
                                    color = if (prefs.dspEnabled) StreamEmerald else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. ACTION BUTTONS ROW (PROMINENTLY PLACED DIRECTLY BELOW NOW PLAYING CARD)
        // =========================================================================
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Add Files Button
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            pickAudioLauncher.launch(
                                arrayOf(
                                    "audio/*",
                                    "application/ogg",
                                    "audio/ogg",
                                    "audio/opus",
                                    "audio/x-opus+ogg",
                                    "application/x-flac"
                                )
                            )
                        }
                        .testTag("add_files_button"),
                    color = Color(0xFF1B1B27),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF2F2F44))
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            tint = StreamEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Add Audio Files",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Add Folder Button
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            try {
                                pickFolderLauncher.launch(null)
                            } catch (e: Throwable) {
                                Toast.makeText(context, "Folder picker unavailable", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .testTag("add_folder_button"),
                    color = Color(0xFF1B1B27),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF2F2F44))
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = StreamEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Import Folder",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // =========================================================================
        // 4. QUEUE HEADER & PLAYLIST SECTION (With Folder Sync & Rescan)
        // =========================================================================
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Playlist Queue (${uiState.playlist.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            softWrap = false
                        )

                        if (savedFolder != null) {
                            Surface(
                                color = Color(0xFF1E1E28),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0xFF2E2E3E))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FolderOpen,
                                        contentDescription = null,
                                        tint = StreamEmerald,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = savedFolder ?: "",
                                        color = StreamEmerald,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 95.dp)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Manual Rescan Button (🔄)
                        if (savedFolder != null) {
                            Surface(
                                color = Color(0xFF1A1A22),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF2C2C3C)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isRescanning) {
                                        viewModel.rescanFolder { added, removed ->
                                            if (added > 0 || removed > 0) {
                                                Toast.makeText(context, "Synced: +$added new, -$removed removed", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Folder library is up to date", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    .testTag("rescan_folder_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isRescanning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(11.dp),
                                            color = StreamEmerald,
                                            strokeWidth = 1.5.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.Sync,
                                            contentDescription = "Rescan Folder",
                                            tint = StreamEmerald,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Text(
                                        text = if (isRescanning) "Scanning…" else "Rescan",
                                        color = StreamEmerald,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        if (uiState.playlist.isNotEmpty()) {
                            Surface(
                                color = Color(0xFF1A1A22),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF2C2C3C)),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewModel.clearQueue() }
                                    .testTag("clear_queue_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteOutline,
                                        contentDescription = "Clear Queue",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Clear",
                                        color = TextSecondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 5. BUTTER-SMOOTH PLAYLIST ITEMS (Using stable key and decoupled TrackItemRow)
        // =========================================================================
        if (uiState.playlist.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
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
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "No songs in queue",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Add Opus, FLAC, WAV, or MP3 files or import a whole folder to begin streaming directly to your ESP32 DAC.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            // Using stable key = { track.id } ensures 60/120fps fluid scrolling without layout thrashing
            itemsIndexed(
                items = uiState.playlist,
                key = { _, track -> track.id }
            ) { index, track ->
                val isCurrent = index == uiState.currentIndex
                val itemPlaying = isCurrent && isPlaying

                TrackItemRow(
                    track = track,
                    index = index,
                    isCurrent = isCurrent,
                    isPlaying = itemPlaying,
                    onClick = { viewModel.playTrack(index) }
                )
            }
        }

        // Bottom padding to clear bottom navigation and mini player
        item {
            Spacer(modifier = Modifier.height(72.dp))
        }
    }

    // =========================================================================
    // REAL-TIME DSP BOTTOM SHEET OVERLAY
    // =========================================================================
    if (showDspSheet) {
        DspModalBottomSheet(
            onDismiss = { showDspSheet = false },
            prefs = prefs,
            onDspEnabledChange = { viewModel.updateDspEnabled(it) },
            onSoftLimiterChange = { viewModel.updateSoftLimiter(it) },
            onLimiterTrimChange = { viewModel.updateLimiterTrim(it) },
            onAirBandEnabledChange = { viewModel.updateAirBandEnabled(it) },
            onAirBandGainChange = { viewModel.updateAirBandGain(it) },
            onBassPunchEnabledChange = { viewModel.updateBassPunchEnabled(it) },
            onBassPunchIntensityChange = { viewModel.updateBassPunchIntensity(it) },
            onSubBassCutChange = { viewModel.updateSubBassCut(it) },
            onStereoWidenerChange = { viewModel.updateStereoWidener(it) },
            onStereoWidthChange = { viewModel.updateStereoWidth(it) },
            onOpenFullSettings = {
                showDspSheet = false
                onNavigateToSettings()
            }
        )
    }
}

/**
 * Isolated, high-performance playlist item composable.
 * Reads artwork from ArtworkCache (O(1) memory lookup) preventing UI thread stalls.
 */
@Composable
private fun TrackItemRow(
    track: AudioTrack,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Instant cached artwork thumbnail
                val itemArtBitmap = remember(track.id, track.artworkBytes) {
                    ArtworkCache.getOrDecode(track.id, track.artworkBytes)
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isCurrent) StreamEmerald.copy(alpha = 0.2f) else Color(0xFF1A1A22))
                        .border(
                            1.dp,
                            if (isCurrent) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF282834),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (itemArtBitmap != null) {
                        Image(
                            bitmap = itemArtBitmap,
                            contentDescription = "Track Artwork",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isPlaying) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = "Playing",
                                    tint = StreamEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        if (isPlaying) {
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
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCurrent) Color.White else TextPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // Format Badge (e.g. OPUS, FLAC, WAV)
                        Surface(
                            color = if (isCurrent) StreamEmerald.copy(alpha = 0.2f) else Color(0xFF1E1E28),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = track.formatDisplay,
                                color = if (isCurrent) StreamEmerald else Color(0xFF8E8E9E),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // Right: Duration
            Text(
                text = track.durationFormatted,
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) StreamEmerald else TextSecondary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun RealTimeDspBottomSheetContent(
    prefs: UserPreferences,
    onDspEnabledChange: (Boolean) -> Unit,
    onSoftLimiterChange: (Boolean) -> Unit,
    onLimiterTrimChange: (Float) -> Unit,
    onAirBandEnabledChange: (Boolean) -> Unit,
    onAirBandGainChange: (Float) -> Unit,
    onBassPunchEnabledChange: (Boolean) -> Unit,
    onBassPunchIntensityChange: (Int) -> Unit,
    onSubBassCutChange: (Boolean) -> Unit,
    onStereoWidenerChange: (Boolean) -> Unit,
    onStereoWidthChange: (Int) -> Unit,
    onOpenFullSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (prefs.dspEnabled) StreamEmerald.copy(alpha = 0.2f) else Color(0xFF1E1E28)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "DSP",
                        tint = if (prefs.dspEnabled) StreamEmerald else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Real-Time DSP Tweaks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (prefs.dspEnabled) "Live 64-bit PCM audio filtering active" else "DSP engine is currently bypassed",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (prefs.dspEnabled) StreamEmerald else TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Switch(
                checked = prefs.dspEnabled,
                onCheckedChange = onDspEnabledChange,
                modifier = Modifier.testTag("modal_dsp_master_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = StreamEmerald,
                    uncheckedThumbColor = Color(0xFF888898),
                    uncheckedTrackColor = Color(0xFF22222E)
                )
            )
        }

        HorizontalDivider(color = Color(0xFF22222E))

        // 1. Air Band High-Shelf (14 kHz)
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.airBandEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Waves, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Air Band (14 kHz)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Silky shimmer & open air without sibilance", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    Switch(
                        checked = prefs.airBandEnabled,
                        onCheckedChange = onAirBandEnabledChange,
                        modifier = Modifier.testTag("modal_air_band_switch"),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                    )
                }

                if (prefs.airBandEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Gain", fontSize = 10.sp, color = TextSecondary)
                        Slider(
                            value = prefs.airBandGainDb,
                            onValueChange = onAirBandGainChange,
                            valueRange = 0f..6f,
                            steps = 11,
                            modifier = Modifier.weight(1f).testTag("modal_air_band_slider"),
                            colors = SliderDefaults.colors(thumbColor = StreamEmerald, activeTrackColor = StreamEmerald, inactiveTrackColor = Color(0xFF2B2B3C))
                        )
                        Text("+${String.format("%.1f", prefs.airBandGainDb)} dB", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StreamEmerald)
                    }
                }
            }
        }

        // 2. Tight Bass Punch & Mud-Cut
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.bassPunchEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Bolt, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Tight Bass Punch & Mud-Cut", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("65 Hz kick snap + 280 Hz boxy mud notch", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    Switch(
                        checked = prefs.bassPunchEnabled,
                        onCheckedChange = onBassPunchEnabledChange,
                        modifier = Modifier.testTag("modal_bass_punch_switch"),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                    )
                }

                if (prefs.bassPunchEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Punch", fontSize = 10.sp, color = TextSecondary)
                        Slider(
                            value = prefs.bassPunchIntensity.toFloat(),
                            onValueChange = { onBassPunchIntensityChange(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).testTag("modal_bass_punch_slider"),
                            colors = SliderDefaults.colors(thumbColor = StreamEmerald, activeTrackColor = StreamEmerald, inactiveTrackColor = Color(0xFF2B2B3C))
                        )
                        Text("${prefs.bassPunchIntensity}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StreamEmerald)
                    }
                }
            }
        }

        // 3. Sub-Bass Infrasonic Rumble Cut (28 Hz HPF)
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.subBassCutEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.VolumeOff, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                    Column {
                        Text("Sub-Bass Rumble Cut (28 Hz)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Saves DAC headroom & stops small cone flutter", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                    }
                }
                Switch(
                    checked = prefs.subBassCutEnabled,
                    onCheckedChange = onSubBassCutChange,
                    modifier = Modifier.testTag("modal_sub_bass_cut_switch"),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                )
            }
        }

        // 4. Split-Band Stereo Widener
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.stereoWidenerEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.SurroundSound, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Split-Band Stereo Widener", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Mono sub-150Hz bass + wide high soundstage", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    Switch(
                        checked = prefs.stereoWidenerEnabled,
                        onCheckedChange = onStereoWidenerChange,
                        modifier = Modifier.testTag("modal_stereo_widener_switch"),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                    )
                }

                if (prefs.stereoWidenerEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Width", fontSize = 10.sp, color = TextSecondary)
                        Slider(
                            value = prefs.stereoWidthPercent.toFloat(),
                            onValueChange = { onStereoWidthChange(it.toInt()) },
                            valueRange = 100f..150f,
                            modifier = Modifier.weight(1f).testTag("modal_stereo_width_slider"),
                            colors = SliderDefaults.colors(thumbColor = StreamEmerald, activeTrackColor = StreamEmerald, inactiveTrackColor = Color(0xFF2B2B3C))
                        )
                        Text("${prefs.stereoWidthPercent}%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StreamEmerald)
                    }
                }
            }
        }

        // 5. Soft-Knee Peak Limiter & Headroom Trim
        Surface(
            color = Color(0xFF161622),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (prefs.softLimiterEnabled) StreamEmerald.copy(alpha = 0.4f) else Color(0xFF262636)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Compress, contentDescription = null, tint = StreamEmerald, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Soft-Knee Peak Limiter", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Anti-clipping protection at -1.36 dBFS", style = MaterialTheme.typography.bodySmall, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                    Switch(
                        checked = prefs.softLimiterEnabled,
                        onCheckedChange = onSoftLimiterChange,
                        modifier = Modifier.testTag("modal_soft_limiter_switch"),
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = StreamEmerald)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Trim", fontSize = 10.sp, color = TextSecondary)
                    Slider(
                        value = prefs.limiterTrimDb,
                        onValueChange = onLimiterTrimChange,
                        valueRange = -6f..6f,
                        steps = 23,
                        modifier = Modifier.weight(1f).testTag("modal_limiter_trim_slider"),
                        colors = SliderDefaults.colors(thumbColor = StreamEmerald, activeTrackColor = StreamEmerald, inactiveTrackColor = Color(0xFF2B2B3C))
                    )
                    Surface(
                        color = Color(0xFF1F1F2C),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { onLimiterTrimChange(0f) }
                    ) {
                        Text(
                            text = if (prefs.limiterTrimDb > 0f) "+${String.format("%.1f", prefs.limiterTrimDb)} dB"
                                   else if (prefs.limiterTrimDb < 0f) "${String.format("%.1f", prefs.limiterTrimDb)} dB"
                                   else "0 dB",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (prefs.limiterTrimDb != 0f) StreamEmerald else Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Link to Settings
        Button(
            onClick = onOpenFullSettings,
            modifier = Modifier.fillMaxWidth().testTag("modal_open_settings_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2C)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Full DSP Settings", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
