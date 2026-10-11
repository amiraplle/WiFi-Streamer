package com.example.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.safety.ActiveStreamProcess
import com.example.model.StreamingState
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val telemetry by viewModel.telemetry.collectAsState()
    val prefs by viewModel.userPreferences.collectAsState()
    val activeSafetyProcess by viewModel.activeSafetyProcess.collectAsState()

    val currentUrl by viewModel.currentUrl.collectAsState()
    val pageTitle by viewModel.pageTitle.collectAsState()
    val artistName by viewModel.artistName.collectAsState()
    val isAdBlockEnabled by viewModel.isAdBlockEnabled.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var inputUrlText by remember(currentUrl) { mutableStateOf(currentUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Physical back button navigates web history first
    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    val isStreaming = telemetry.streamingState == StreamingState.STREAMING
    val isConnecting = telemetry.streamingState == StreamingState.CONNECTING || telemetry.streamingState == StreamingState.RECONNECTING
    val isBrowserActiveProcess = activeSafetyProcess == ActiveStreamProcess.BROWSER_STREAM && (isStreaming || isConnecting)
    val isSystemActiveProcess = activeSafetyProcess == ActiveStreamProcess.SYSTEM_STREAM && (isStreaming || isConnecting)

    val infiniteTransition = rememberInfiniteTransition(label = "audio_spin")
    val vinylRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinylRotation"
    )

    // Silence physical phone speaker while in dedicated Browser
    DisposableEffect(Unit) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val originalVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        try {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (_: Exception) {}

        onDispose {
            try {
                if (originalVol >= 0) {
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVol, 0)
                }
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // =========================================================================
        // 1. TOP MINIMAL RECEIVER STATUS BAR
        // =========================================================================
        Surface(
            color = Color(0xFF0F1017),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isStreaming) StreamEmerald else Color(0xFF71717A))
                    )
                    Text(
                        text = "Receiver: ${prefs.targetHost}:${prefs.targetPort}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isStreaming) StreamEmerald else TextSecondary,
                        fontSize = 11.sp
                    )
                }

                Text(
                    text = if (isStreaming) "STREAMING ACTIVE (CONTINUOUS)" else "STANDBY (SPEAKER MUTED)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isStreaming) StreamEmerald else Color(0xFF8E8E9E),
                    fontSize = 10.sp
                )
            }
        }

        // =========================================================================
        // 2. MODERN, HIGH-CONTRAST ADDRESS BAR + BROWSER NAVIGATION
        // =========================================================================
        Surface(
            color = Color(0xFF141520),
            border = BorderStroke(1.dp, Color(0xFF222332)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val canGoBack = webViewRef?.canGoBack() == true
                    IconButton(
                        onClick = { if (canGoBack) webViewRef?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = if (canGoBack) Color.White else Color(0xFF4A4B5E),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    val canGoForward = webViewRef?.canGoForward() == true
                    IconButton(
                        onClick = { if (canGoForward) webViewRef?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (canGoForward) Color.White else Color(0xFF4A4B5E),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Modern, highly visible and readable URL box
                    val isHttps = currentUrl.startsWith("https://", ignoreCase = true)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("browser_url_input_box"),
                        shape = RoundedCornerShape(23.dp),
                        color = Color(0xFF1D1F2D),
                        border = BorderStroke(1.dp, Color(0xFF383A50))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isHttps) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                contentDescription = if (isHttps) "HTTPS" else "HTTP",
                                tint = if (isHttps) StreamEmerald else WarningAmber,
                                modifier = Modifier.size(15.dp)
                            )

                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (inputUrlText.isEmpty()) {
                                    Text(
                                        text = "Search or web address…",
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 14.sp,
                                        maxLines = 1
                                    )
                                }
                                BasicTextField(
                                    value = inputUrlText,
                                    onValueChange = { inputUrlText = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("browser_url_input"),
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(Color(0xFF60A5FA)),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go
                                    ),
                                    keyboardActions = KeyboardActions(onGo = {
                                        keyboardController?.hide()
                                        viewModel.navigateTo(inputUrlText)
                                        webViewRef?.loadUrl(viewModel.currentUrl.value)
                                    })
                                )
                            }

                            if (inputUrlText.isNotEmpty()) {
                                IconButton(
                                    onClick = { inputUrlText = "" },
                                    modifier = Modifier.size(24.dp).testTag("browser_clear_url_button")
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "Clear",
                                        tint = Color(0xFFA1A1AA),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }

                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF3B82F6)
                                )
                            } else {
                                val isModified = inputUrlText.trim() != currentUrl.trim()
                                IconButton(
                                    onClick = {
                                        keyboardController?.hide()
                                        viewModel.navigateTo(inputUrlText)
                                        webViewRef?.loadUrl(viewModel.currentUrl.value)
                                    },
                                    modifier = Modifier.size(28.dp).testTag("browser_go_refresh_button")
                                ) {
                                    Icon(
                                        imageVector = if (isModified) Icons.AutoMirrored.Outlined.ArrowForward else Icons.Outlined.Refresh,
                                        contentDescription = if (isModified) "Go" else "Reload",
                                        tint = if (isModified) Color(0xFF60A5FA) else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 3-dots Browser Menu
                    var browserMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { browserMenuExpanded = true },
                            modifier = Modifier.size(38.dp).testTag("browser_more_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "Menu",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = browserMenuExpanded,
                            onDismissRequest = { browserMenuExpanded = false },
                            modifier = Modifier.background(Color(0xFF1E202E))
                        ) {
                            // Ad Blocker toggle
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(
                                            Icons.Outlined.Security,
                                            contentDescription = null,
                                            tint = if (isAdBlockEnabled) Color(0xFF818CF8) else Color(0xFF9CA3AF),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = if (isAdBlockEnabled) "Ad Blocker: ACTIVE" else "Ad Blocker: OFF",
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.toggleAdBlock()
                                    browserMenuExpanded = false
                                }
                            )

                            // Reload
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Text("Reload Page", color = Color.White, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    webViewRef?.reload()
                                    browserMenuExpanded = false
                                }
                            )

                            // Clear Cache
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                                        Text("Clear Cache & Storage", color = Color(0xFFEF4444), fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    viewModel.clearCache(webViewRef)
                                    Toast.makeText(context, "Browser cache cleared", Toast.LENGTH_SHORT).show()
                                    browserMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                if (isLoading) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = Color(0xFF3B82F6),
                        trackColor = Color.Transparent
                    )
                }

                // Quick Music Destinations (Chips)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    viewModel.quickLinks.forEach { link ->
                        Surface(
                            color = Color(0xFF1B1D2A),
                            border = BorderStroke(1.dp, Color(0xFF2B2E42)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.navigateTo(link.url)
                                    inputUrlText = link.url
                                    webViewRef?.loadUrl(link.url)
                                }
                                .testTag("quick_link_${link.title.replace(" ", "_").lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(text = link.iconEmoji, fontSize = 11.sp)
                                Text(
                                    text = link.title,
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. FULL WEB VIEW (BackgroundAudioWebView - continues playing when minimized / locked)
        // =========================================================================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AndroidView(
                factory = { ctx ->
                    val webView = viewModel.getOrCreateWebView(ctx)
                    val parent = webView.parent as? ViewGroup
                    parent?.removeView(webView)
                    webViewRef = webView
                    webView
                },
                update = { webView ->
                    webViewRef = webView
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("browser_webview")
            )
        }

        // =========================================================================
        // 4. BOTTOM STREAM DOCK: Track Info, Volume Control & One-Tap Stream Button
        // =========================================================================
        Surface(
            color = Color(0xFF0F1017),
            border = BorderStroke(1.dp, Color(0xFF222332)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Track Info & Destination Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2563EB))
                            .rotate(if (isBrowserActiveProcess) vinylRotation else 0f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.GraphicEq,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pageTitle,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "$artistName • ${prefs.targetHost}:${prefs.targetPort}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9CA3AF),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }

                // Volume Control Row (Slider + Mute + Percentage)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Mute / Unmute Toggle Button
                    IconButton(
                        onClick = { viewModel.toggleMute() },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (prefs.isMuted) ErrorCoral.copy(alpha = 0.2f) else Color(0xFF1B1D2A))
                            .testTag("browser_mute_button")
                    ) {
                        Icon(
                            imageVector = if (prefs.isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                            contentDescription = if (prefs.isMuted) "Unmute" else "Mute",
                            tint = if (prefs.isMuted) ErrorCoral else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Volume slider
                    Slider(
                        value = prefs.transmissionVolume.toFloat(),
                        onValueChange = { viewModel.setVolume(it.toInt()) },
                        valueRange = 0f..100f,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("browser_volume_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF3B82F6),
                            inactiveTrackColor = Color(0xFF282836)
                        )
                    )

                    // Volume percentage pill
                    Surface(
                        color = Color(0xFF1B1D2A),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF2C2E40))
                    ) {
                        Text(
                            text = if (prefs.isMuted) "MUTE" else "${prefs.transmissionVolume}%",
                            color = if (prefs.isMuted) ErrorCoral else Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Stream Button (Start / Stop)
                if (isBrowserActiveProcess) {
                    Button(
                        onClick = { viewModel.stopStreaming(context) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("browser_stop_stream_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorCoral),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Stop,
                            contentDescription = "Stop",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Stop Browser Stream",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.startStreaming(context, 0, null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("browser_start_stream_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSystemActiveProcess) WarningAmber else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = if (isSystemActiveProcess) Icons.Outlined.Security else Icons.Outlined.PlayArrow,
                            contentDescription = "Start",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSystemActiveProcess) {
                                "Switch to Browser Stream (Auto-stops System)"
                            } else {
                                "Stream Audio to ${prefs.targetHost}"
                            },
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
