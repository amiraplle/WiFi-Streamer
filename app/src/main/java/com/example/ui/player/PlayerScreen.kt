package com.example.ui.player

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
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
import com.example.ui.components.DspModalBottomSheet
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

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

    // SAF File Picker
    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPickedFiles(uris)
            Toast.makeText(context, "Added ${uris.size} tracks", Toast.LENGTH_SHORT).show()
        }
    }

    // SAF Folder Picker
    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.loadFolder(treeUri) { count ->
                Toast.makeText(context, "Loaded $count tracks", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val currentTrack = uiState.currentTrack
    val isPlaying = uiState.playerState == PlayerState.PLAYING
    val isBuffering = uiState.playerState == PlayerState.BUFFERING

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableFloatStateOf(0f) }

    val displayPositionMs = if (isScrubbing) scrubPositionMs.toLong() else uiState.positionMs
    val totalDurationMs = uiState.durationMs.coerceAtLeast(1L)
    val sliderValue = (displayPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =========================================================================
        // 1. CLEAN TOP APP BAR
        // =========================================================================
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Audio Player",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (savedFolder != null) "Folder: $savedFolder" else "Stream directly to ESP32 DAC",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = {
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
                        },
                        modifier = Modifier.testTag("add_files_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Add Files",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            try {
                                pickFolderLauncher.launch(null)
                            } catch (e: Throwable) {
                                Toast.makeText(context, "Folder picker unavailable", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("add_folder_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = "Import Folder",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // =========================================================================
        // 2. MINIMAL MATERIAL 3 NOW PLAYING CARD
        // =========================================================================
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("player_hero_card"),
                shape = RoundedCornerShape(20.dp),
                color = DarkSurface,
                border = BorderStroke(1.dp, Color(0xFF222530))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Artwork & Track Metadata Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Artwork Tile
                        val artBitmap = remember(currentTrack?.id, currentTrack?.artworkBytes) {
                            if (currentTrack != null) ArtworkCache.getOrDecode(currentTrack.id, currentTrack.artworkBytes)
                            else null
                        }

                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF161822))
                                .border(1.dp, Color(0xFF2A2E3D), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (artBitmap != null) {
                                Image(
                                    bitmap = artBitmap,
                                    contentDescription = "Cover Art",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Filled.GraphicEq else Icons.Outlined.Audiotrack,
                                    contentDescription = null,
                                    tint = if (isPlaying) StreamEmerald else Color(0xFF787C90),
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            if (isBuffering) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        // Title & Artist
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTrack?.title ?: "No track selected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentTrack?.artist ?: "Choose a track from the queue below",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Seek Slider & Timestamps
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
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
                                inactiveTrackColor = Color(0xFF262936)
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatDuration(displayPositionMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            Text(
                                text = formatDuration(totalDurationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    // Transport Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.toggleShuffle() },
                            modifier = Modifier.size(44.dp).testTag("player_shuffle_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (uiState.isShuffle) StreamEmerald else Color(0xFF7E8294),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.playPrevious() },
                            modifier = Modifier.size(48.dp).testTag("player_previous_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Prominent Hero Play / Pause Button
                        val playBtnScale by animateFloatAsState(
                            targetValue = if (isPlaying) 1.02f else 1.0f,
                            animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
                            label = "playScale"
                        )
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .scale(playBtnScale)
                                .clip(CircleShape)
                                .background(if (isPlaying) StreamEmerald else Color.White)
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
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.playNext() },
                            modifier = Modifier.size(48.dp).testTag("player_next_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.toggleRepeat() },
                            modifier = Modifier.size(44.dp).testTag("player_repeat_button")
                        ) {
                            val icon = when (uiState.repeatMode) {
                                PlayerRepeatMode.ONE -> Icons.Filled.RepeatOne
                                else -> Icons.Filled.Repeat
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = "Repeat",
                                tint = if (uiState.repeatMode != PlayerRepeatMode.OFF) StreamEmerald else Color(0xFF7E8294),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Bottom Card Row: Output Format Selector & DSP Tweaks Button
                    var formatMenuExpanded by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Format Selector Pill
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                color = Color(0xFF141620),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF262938)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { formatMenuExpanded = true }
                                    .testTag("player_format_selector_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
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
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = uiState.outputFormatInfo,
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Outlined.ArrowDropDown,
                                        contentDescription = "Select Format",
                                        tint = Color(0xFFA0A4B8),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = formatMenuExpanded,
                                onDismissRequest = { formatMenuExpanded = false },
                                modifier = Modifier
                                    .background(Color(0xFF141620))
                                    .border(BorderStroke(1.dp, Color(0xFF2E3244)), RoundedCornerShape(12.dp))
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
                                                    fontSize = 13.sp
                                                )
                                                Text(
                                                    text = format.dacCompatibility,
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF888B9C)
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

                        // DSP Tweaks Button
                        Surface(
                            color = if (prefs.dspEnabled) StreamEmerald.copy(alpha = 0.15f) else Color(0xFF141620),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (prefs.dspEnabled) StreamEmerald.copy(alpha = 0.5f) else Color(0xFF262938)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showDspSheet = true }
                                .testTag("player_dsp_badge_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = "DSP Settings",
                                    tint = if (prefs.dspEnabled) StreamEmerald else Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "DSP",
                                    color = if (prefs.dspEnabled) StreamEmerald else Color.White,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (prefs.dspEnabled) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(StreamEmerald)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. CLEAN QUEUE SECTION HEADER
        // =========================================================================
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Queue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "(${uiState.playlist.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (savedFolder != null) {
                        IconButton(
                            onClick = {
                                viewModel.rescanFolder { added, removed ->
                                    if (added > 0 || removed > 0) {
                                        Toast.makeText(context, "Synced: +$added new, -$removed removed", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Library is up to date", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isRescanning,
                            modifier = Modifier.size(36.dp).testTag("rescan_folder_button")
                        ) {
                            if (isRescanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = StreamEmerald
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Sync,
                                    contentDescription = "Rescan Folder",
                                    tint = StreamEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (uiState.playlist.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearQueue() },
                            modifier = Modifier.size(36.dp).testTag("clear_queue_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Clear Queue",
                                tint = Color(0xFFEF5350),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 4. CLEAN PLAYLIST LIST
        // =========================================================================
        if (uiState.playlist.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    color = DarkSurface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFF222530))
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Audiotrack,
                            contentDescription = null,
                            tint = Color(0xFF5E6274),
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "No tracks in queue",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Tap + to add audio files or tap the folder icon to import music.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            itemsIndexed(
                items = uiState.playlist,
                key = { _, track -> track.id }
            ) { index, track ->
                val isCurrent = index == uiState.currentIndex
                val itemPlaying = isCurrent && isPlaying

                TrackListItem(
                    track = track,
                    index = index,
                    isCurrent = isCurrent,
                    isPlaying = itemPlaying,
                    onClick = { viewModel.playTrack(index) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }

    // DSP Bottom Sheet
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
 * Minimal, clean Material 3 track item row.
 * Eliminates bulky borders, square number frames, and format badge clutter.
 */
@Composable
private fun TrackListItem(
    track: AudioTrack,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowBackground = if (isCurrent) Color(0xFF141722) else Color.Transparent

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("track_item_$index"),
        color = rowBackground,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Track Number or Live Playing Wave Icon
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
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
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent) StreamEmerald else Color(0xFF6E7284)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Title and Artist
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) Color.White else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) Color(0xFFA0A4B8) else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Duration
            Text(
                text = track.durationFormatted,
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) StreamEmerald else TextSecondary
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
