package com.example.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.ViewCompact
import androidx.compose.material.icons.outlined.VolumeDown
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.domain.safety.ActiveStreamProcess
import com.example.model.StreamingState
import com.example.ui.components.StudioCard
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ErrorCoral
import com.example.ui.theme.StreamEmerald
import com.example.ui.theme.TextPrimary
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
    val safetyNotice by viewModel.safetyNotice.collectAsState()

    val currentUrl by viewModel.currentUrl.collectAsState()
    val pageTitle by viewModel.pageTitle.collectAsState()
    val artistName by viewModel.artistName.collectAsState()
    val isSmartArtworkMode by viewModel.isSmartArtworkMode.collectAsState()
    val isLiteMode by viewModel.isLiteMode.collectAsState()
    val isAdBlockEnabled by viewModel.isAdBlockEnabled.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var inputUrlText by remember(currentUrl) { mutableStateOf(currentUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Permission launcher for RECORD_AUDIO (Zero Screen Capture needed for Browser Stream)
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startStreaming(context, 0, null)
        } else {
            Toast.makeText(context, "Direct audio streaming active", Toast.LENGTH_SHORT).show()
            viewModel.startStreaming(context, 0, null)
        }
    }

    val isStreaming = telemetry.streamingState == StreamingState.STREAMING
    val isConnecting = telemetry.streamingState == StreamingState.CONNECTING || telemetry.streamingState == StreamingState.RECONNECTING
    val isBrowserActiveProcess = activeSafetyProcess == ActiveStreamProcess.BROWSER_STREAM && (isStreaming || isConnecting)
    val isSystemActiveProcess = activeSafetyProcess == ActiveStreamProcess.SYSTEM_STREAM && (isStreaming || isConnecting)

    // Animation for vinyl / artwork rotation when active
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_spin")
    val vinylRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinylRotation"
    )

    // Silence physical phone speaker while in dedicated Browser (never leak sound to phone speaker)
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
        // 1. TOP MINIMAL CONNECTION INFO BAR (Clean, no unnecessary noisy tags)
        // =========================================================================
        Surface(
            color = Color(0xFF0D0D12),
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
                            .size(7.dp)
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
                    text = if (isStreaming) "DIRECT STREAM ACTIVE" else "STANDBY (SPEAKER MUTED)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isStreaming) StreamEmerald else Color(0xFF8E8E9E),
                    fontSize = 10.sp
                )
            }
        }

        // =========================================================================
        // 2. CLEAN ADDRESS BAR + TOP-RIGHT BROWSER MENU
        // =========================================================================
        Surface(
            color = Color(0xFF14141B),
            border = BorderStroke(1.dp, Color(0xFF22222E)),
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
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = if (canGoBack) Color.White else Color(0xFF4A4A5A),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    val canGoForward = webViewRef?.canGoForward() == true
                    IconButton(
                        onClick = { if (canGoForward) webViewRef?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (canGoForward) Color.White else Color(0xFF4A4A5A),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    val isHttps = currentUrl.startsWith("https://", ignoreCase = true)
                    OutlinedTextField(
                        value = inputUrlText,
                        onValueChange = { inputUrlText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("browser_url_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF181824),
                            unfocusedContainerColor = Color(0xFF181824),
                            focusedBorderColor = Color(0xFF3B82F6),
                            unfocusedBorderColor = Color(0xFF282836),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        placeholder = {
                            Text("Search or URL…", color = Color(0xFF6E6E82), fontSize = 11.sp, maxLines = 1)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isHttps) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                contentDescription = if (isHttps) "HTTPS" else "HTTP",
                                tint = if (isHttps) StreamEmerald else WarningAmber,
                                modifier = Modifier.size(13.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            keyboardController?.hide()
                            viewModel.navigateTo(inputUrlText)
                            webViewRef?.loadUrl(viewModel.currentUrl.value)
                        }),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (inputUrlText.isNotEmpty()) {
                                    IconButton(
                                        onClick = { inputUrlText = "" },
                                        modifier = Modifier.size(24.dp).testTag("browser_clear_url_button")
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = Color(0xFFA0A0B2), modifier = Modifier.size(13.dp))
                                    }
                                }
                                if (isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF3B82F6))
                                } else {
                                    val isModified = inputUrlText.trim() != currentUrl.trim()
                                    IconButton(
                                        onClick = {
                                            keyboardController?.hide()
                                            viewModel.navigateTo(inputUrlText)
                                            webViewRef?.loadUrl(viewModel.currentUrl.value)
                                        },
                                        modifier = Modifier.size(24.dp).testTag("browser_go_refresh_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isModified) Icons.AutoMirrored.Outlined.ArrowForward else Icons.Outlined.Refresh,
                                            contentDescription = if (isModified) "Go" else "Reload",
                                            tint = if (isModified) Color(0xFF60A5FA) else Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )

                    // 3-dots Browser Menu on top right
                    var browserMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { browserMenuExpanded = true },
                            modifier = Modifier.size(36.dp).testTag("browser_overflow_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "Browser Menu",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = browserMenuExpanded,
                            onDismissRequest = { browserMenuExpanded = false },
                            modifier = Modifier
                                .background(Color(0xFF161622))
                                .border(BorderStroke(1.dp, Color(0xFF2C2C3E)), RoundedCornerShape(12.dp))
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
                                            text = if (isAdBlockEnabled) "Ad Blocker: ON" else "Ad Blocker: OFF",
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

                            // Lite Mode toggle
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(
                                            Icons.Outlined.ElectricBolt,
                                            contentDescription = null,
                                            tint = if (isLiteMode) StreamEmerald else Color(0xFF9CA3AF),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = if (isLiteMode) "Lite Mode: ON" else "Lite Mode: OFF",
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.toggleLiteMode()
                                    browserMenuExpanded = false
                                }
                            )

                            // Compact Artwork Mode toggle
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(
                                            if (isSmartArtworkMode) Icons.Outlined.Language else Icons.Outlined.ViewCompact,
                                            contentDescription = null,
                                            tint = if (isSmartArtworkMode) Color(0xFF60A5FA) else Color(0xFF9CA3AF),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = if (isSmartArtworkMode) "Switch to Web View" else "Compact Artwork Mode",
                                            color = Color.White,
                                            fontSize = 13.sp
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.toggleSmartArtworkMode()
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
                            color = Color(0xFF1A1A24),
                            border = BorderStroke(1.dp, Color(0xFF282836)),
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
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
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
        // 2. MAIN BODY: Smart Artwork Card + Browser View
        // =========================================================================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Embedded WebView (always live so audio playback is preserved)
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadsImagesAutomatically = !isLiteMode
                            blockNetworkImage = isLiteMode
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            databaseEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }

                        // Attach digital audio capture bridge
                        addJavascriptInterface(
                            BrowserAudioBridge { pcmBytes ->
                                viewModel.feedAudioPcm(pcmBytes)
                            },
                            "C3AudioBridge"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val url = request?.url?.toString()
                                if (viewModel.isAdBlockEnabled.value && AdBlockEngine.shouldBlock(url)) {
                                    return AdBlockEngine.createEmptyResponse()
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                viewModel.setLoading(true)
                                // Inject audio capture & phone speaker muting immediately on start
                                view?.evaluateJavascript(BrowserAudioEngine.AUDIO_CAPTURE_AND_MUTE_SCRIPT, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                viewModel.setLoading(false)
                                val title = view?.title ?: ""
                                val current = url ?: ""
                                viewModel.setPageInfo(title, current)

                                // Inject audio capture & phone speaker muting script
                                view?.evaluateJavascript(BrowserAudioEngine.AUDIO_CAPTURE_AND_MUTE_SCRIPT, null)

                                // Inject Audio-First background playback keep-alive script
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                        // Prevent tab visibility throttle on media playback
                                        Object.defineProperty(document, 'hidden', { value: false, writable: false });
                                        Object.defineProperty(document, 'visibilityState', { value: 'visible', writable: false });
                                    })();
                                    """.trimIndent(),
                                    null
                                )

                                // Inject YouTube Music Ad-Skipper & Ad Blocker script if enabled
                                if (viewModel.isAdBlockEnabled.value) {
                                    view?.evaluateJavascript(AdBlockEngine.adSkipperScript, null)
                                }

                                if (viewModel.isLiteMode.value) {
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            var existing = document.getElementById('c3_lite_mode_style');
                                            if (!existing) {
                                                var style = document.createElement('style');
                                                style.id = 'c3_lite_mode_style';
                                                style.innerHTML = 'img, video:not([controls]), .ad-container, [class*="banner"], [class*="sponsor"] { display: none !important; }';
                                                document.head.appendChild(style);
                                            }
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                if (!title.isNullOrBlank()) {
                                    viewModel.setPageInfo(title, view?.url ?: "")
                                }
                            }
                        }

                        loadUrl(viewModel.currentUrl.value)
                        webViewRef = this
                    }
                },
                update = { webView ->
                    webViewRef = webView
                    val lite = isLiteMode
                    webView.settings.loadsImagesAutomatically = !lite
                    webView.settings.blockNetworkImage = lite
                    if (lite) {
                        webView.evaluateJavascript(
                            """
                            (function() {
                                var existing = document.getElementById('c3_lite_mode_style');
                                if (!existing) {
                                    var style = document.createElement('style');
                                    style.id = 'c3_lite_mode_style';
                                    style.innerHTML = 'img, video:not([controls]), .ad-container, [class*="banner"], [class*="sponsor"] { display: none !important; }';
                                    document.head.appendChild(style);
                                }
                            })();
                            """.trimIndent(),
                            null
                        )
                    } else {
                        webView.evaluateJavascript(
                            """
                            (function() {
                                var existing = document.getElementById('c3_lite_mode_style');
                                if (existing) { existing.remove(); }
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("browser_webview")
            )

            // If Smart Artwork mode is toggled ON ("smallest possible" audio artwork view)
            if (isSmartArtworkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // High-end Animated Vinyl Artwork Display
                        Box(
                            modifier = Modifier
                                .size(180.dp)
                                .shadow(16.dp, CircleShape, ambientColor = Color(0xFF3B82F6), spotColor = Color(0xFF3B82F6))
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            Color(0xFF282836),
                                            Color(0xFF14141C),
                                            Color(0xFF0A0A0F)
                                        )
                                    )
                                )
                                .rotate(if (isBrowserActiveProcess) vinylRotation else 0f),
                            contentAlignment = Alignment.Center
                        ) {
                            // Vinyl grooves
                            Box(
                                modifier = Modifier
                                    .size(140.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF161622))
                            )
                            Box(
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF20202F))
                            )
                            // Vinyl Center Label with Pulsing Studio Accents
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF2563EB), Color(0xFF38BDF8))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.GraphicEq,
                                    contentDescription = "Audio Disc",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Smart Track / Title Display
                        Text(
                            text = pageTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = artistName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF60A5FA),
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Audio specs pill
                        Surface(
                            color = Color(0xFF1A1A24),
                            border = BorderStroke(1.dp, Color(0xFF2C2C3A)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "${prefs.audioFormat.displayName} • WMM Voice 0xB8",
                                color = Color(0xFFA0A0B8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Tap to expand web view button
                        Button(
                            onClick = { viewModel.toggleSmartArtworkMode() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22222E)),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF353545))
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Language,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Web Browser View", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. BOTTOM STREAM DOCK: On/Off Button + Volume Control
        // =========================================================================
        Surface(
            color = Color(0xFF0C0C10),
            border = BorderStroke(1.dp, Color(0xFF1E1E28)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Mini metadata preview if not in artwork mode
                if (!isSmartArtworkMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
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
                                    color = Color(0xFF8E8E98),
                                    fontSize = 10.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        // Compact Artwork View Quick Button
                        Surface(
                            color = Color(0xFF1A1A24),
                            border = BorderStroke(1.dp, Color(0xFF282836)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.toggleSmartArtworkMode() }
                        ) {
                            Text(
                                text = "Artwork Mode",
                                fontSize = 10.sp,
                                color = Color(0xFF93C5FD),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
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
                            .background(if (prefs.isMuted) ErrorCoral.copy(alpha = 0.2f) else Color(0xFF1B1B24))
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
                        color = Color(0xFF1B1B24),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF2C2C3A))
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

                // Stream On/Off Main Button (With Mutual Exclusion Safety Interlock)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isBrowserActiveProcess) {
                        // STOP BUTTON (Browser stream active)
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
                        // START BUTTON (Pure Digital Browser Stream - Zero Screen Capture, Zero Mic)
                        Button(
                            onClick = {
                                // Stream directly without any microphone or screen capture prompts
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
}

