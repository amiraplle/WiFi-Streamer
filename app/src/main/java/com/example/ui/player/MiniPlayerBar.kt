package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PlayerState
import com.example.model.PlayerUiState
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MiniPlayerBar(
    uiState: PlayerUiState,
    onBarClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack
    val isVisible = track != null && (uiState.playerState == PlayerState.PLAYING || uiState.playerState == PlayerState.PAUSED || uiState.playerState == PlayerState.BUFFERING)

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        if (track == null) return@AnimatedVisibility

        val isPlaying = uiState.playerState == PlayerState.PLAYING

        // Rotating disc animation when playing
        val infiniteTransition = rememberInfiniteTransition(label = "disc_rotate")
        val rotationAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = if (isPlaying) 360f else 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotation"
        )

        val progress = if (uiState.durationMs > 0) {
            (uiState.positionMs.toFloat() / uiState.durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(14.dp), ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                .clip(RoundedCornerShape(14.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White.copy(alpha = 0.15f))
                ) { onBarClick() }
                .testTag("mini_player_bar"),
            color = Color(0xFF16161E),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF2C2C3E))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Spinning disc icon + Title & Artist
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF22222E)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Audiotrack,
                                contentDescription = null,
                                tint = if (isPlaying) StreamEmerald else Color.White,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(if (isPlaying) rotationAngle else 0f)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• ${uiState.outputFormatInfo.substringBefore(" (")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = StreamEmerald
                                )
                            }
                        }
                    }

                    // Right: Quick Play/Pause & Next Buttons
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .testTag("mini_player_play_pause")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = onNext,
                            modifier = Modifier
                                .size(34.dp)
                                .testTag("mini_player_next")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Micro sleek linear progress bar at bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .background(Color(0xFF22222E))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progress)
                            .height(2.5.dp)
                            .background(StreamEmerald)
                    )
                }
            }
        }
    }
}
