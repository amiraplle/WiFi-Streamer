package com.example.ui.browser

import android.webkit.JavascriptInterface

/**
 * JavaScript Bridge for capturing digital audio PCM directly from WebView.
 * Bypasses system media handlers and physical microphone completely.
 */
class BrowserAudioBridge(
    private val onPcmData: (ByteArray) -> Unit
) {
    @JavascriptInterface
    fun sendAudioPcm(base64Data: String) {
        try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.NO_WRAP)
            if (bytes.isNotEmpty()) {
                onPcmData(bytes)
            }
        } catch (_: Exception) {}
    }
}

object BrowserAudioEngine {
    /**
     * Injected Web Audio API Interceptor & Rock-Solid Real-Time Playback Engine.
     *
     * 1. Re-routes <video> and <audio> HTML elements into Web Audio API.
     * 2. Feeds raw PCM samples at exact hardware sample rate into C3AudioBridge for direct ESP transmission.
     * 3. Prevents media elements from producing sound through physical phone speakers (inaudible 1e-6 gain).
     * 4. Intercepts screen lock / minimize events so playback continues seamlessly with app minimized or screen off.
     * 5. Fast 1024-sample frames (23ms) for ultra-smooth jitter-free delivery.
     */
    fun getScript(targetSampleRate: Int = 44100): String = """
    (function() {
        window._c3_streaming_active = true;
        window._c3_target_sample_rate = $targetSampleRate;

        if (window._c3_audio_installed) return;
        window._c3_audio_installed = true;

        // 1. Ultra-fast Base64 encoder for 16-bit PCM chunks (zero GC churn)
        function uint8ToBase64(u8) {
            var CHUNK = 1024;
            var len = u8.length;
            var str = '';
            for (var i = 0; i < len; i += CHUNK) {
                var end = Math.min(i + CHUNK, len);
                str += String.fromCharCode.apply(null, u8.subarray(i, end));
            }
            return btoa(str);
        }

        // 2. High-precision AudioContext with target hardware sample rate
        var audioCtx = null;
        function getContext() {
            if (!audioCtx) {
                var AC = window.AudioContext || window.webkitAudioContext;
                if (AC) {
                    try {
                        audioCtx = new AC({ sampleRate: window._c3_target_sample_rate || 44100 });
                    } catch(e) {
                        try { audioCtx = new AC(); } catch(e2) {}
                    }
                }
                if (audioCtx) {
                    audioCtx.onstatechange = function() {
                        if (audioCtx.state === 'suspended') {
                            audioCtx.resume().catch(function() {});
                        }
                    };
                }
            }
            if (audioCtx && audioCtx.state === 'suspended') {
                audioCtx.resume().catch(function() {});
            }
            return audioCtx;
        }

        // Auto-resume AudioContext on user interactions
        ['click', 'pointerdown', 'keydown', 'touchstart'].forEach(function(evt) {
            window.addEventListener(evt, function() {
                var ctx = getContext();
                if (ctx && ctx.state === 'suspended') {
                    ctx.resume().catch(function() {});
                }
            }, { capture: true, passive: true });
        });

        // 3. Keep audio graph active without audible speaker output
        var silentGain = null;
        function getSilentGain(ctx) {
            if (!silentGain) {
                silentGain = ctx.createGain();
                silentGain.gain.value = 0.000001;
                silentGain.connect(ctx.destination);
            }
            return silentGain;
        }

        // 4. Hook media element (HTMLVideoElement, HTMLAudioElement)
        function hookMediaElement(el) {
            if (!el || el._c3_captured) return;
            el._c3_captured = true;
            window._c3_last_media = el;
            el.muted = false;
            if (el.volume < 1.0) el.volume = 1.0;

            try {
                var ctx = getContext();
                if (!ctx) return;

                var source = ctx.createMediaElementSource(el);
                // 1024 samples = 23.2ms per callback for smooth, steady sample delivery
                var processor = ctx.createScriptProcessor(1024, 2, 2);

                processor.onaudioprocess = function(evt) {
                    if (!window.C3AudioBridge || !window._c3_streaming_active) return;
                    var input = evt.inputBuffer;
                    var left = input.getChannelData(0);
                    var right = input.numberOfChannels > 1 ? input.getChannelData(1) : left;
                    var len = left.length;

                    var inRate = ctx.sampleRate || 44100;
                    var outRate = window._c3_target_sample_rate || 44100;

                    var pcm16;
                    if (inRate === outRate) {
                        pcm16 = new Int16Array(len * 2);
                        for (var i = 0; i < len; i++) {
                            var l = Math.max(-1, Math.min(1, left[i]));
                            var r = Math.max(-1, Math.min(1, right[i]));
                            pcm16[i * 2] = l < 0 ? (l * 0x8000) : (l * 0x7FFF);
                            pcm16[i * 2 + 1] = r < 0 ? (r * 0x8000) : (r * 0x7FFF);
                        }
                    } else {
                        // High-fidelity linear interpolation resampler
                        var ratio = inRate / outRate;
                        var outLen = Math.floor(len / ratio);
                        pcm16 = new Int16Array(outLen * 2);
                        for (var j = 0; j < outLen; j++) {
                            var srcIdx = j * ratio;
                            var i0 = Math.floor(srcIdx);
                            var i1 = Math.min(i0 + 1, len - 1);
                            var frac = srcIdx - i0;

                            var l = left[i0] + (left[i1] - left[i0]) * frac;
                            var r = right[i0] + (right[i1] - right[i0]) * frac;

                            l = Math.max(-1, Math.min(1, l));
                            r = Math.max(-1, Math.min(1, r));
                            pcm16[j * 2] = l < 0 ? (l * 0x8000) : (l * 0x7FFF);
                            pcm16[j * 2 + 1] = r < 0 ? (r * 0x8000) : (r * 0x7FFF);
                        }
                    }

                    // Always stream continuous PCM so receiver clock and pacing remain perfectly synchronous
                    var u8 = new Uint8Array(pcm16.buffer, 0, pcm16.length * 2);
                    var b64 = uint8ToBase64(u8);
                    window.C3AudioBridge.sendAudioPcm(b64);
                };

                source.connect(processor);
                processor.connect(getSilentGain(ctx));
            } catch(err) {
            }
        }

        // 5. Spoof document visibility state so YouTube Music / web players believe screen is always active
        try {
            Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
            Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
            Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
            if (document.hasFocus) {
                document.hasFocus = function() { return true; };
            }
        } catch(e) {}

        // 6. Block visibilitychange and blur events from pausing playback when minimized
        var blockedEvents = ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide'];
        blockedEvents.forEach(function(evt) {
            window.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
            document.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
        });

        // 7. Background Web Worker keep-alive ticker (unthrottled browser heartbeat)
        try {
            if (!window._c3_worker) {
                var workerBlob = new Blob([
                    'setInterval(function() { postMessage("tick"); }, 40);'
                ], { type: 'application/javascript' });
                window._c3_worker = new Worker(URL.createObjectURL(workerBlob));
                window._c3_worker.onmessage = function() {
                    if (audioCtx && audioCtx.state === 'suspended') {
                        audioCtx.resume().catch(function() {});
                    }
                };
            }
        } catch(e) {}

        // 8. Scan DOM for media elements continuously
        function scanMedia() {
            var items = document.querySelectorAll('video, audio');
            for (var i = 0; i < items.length; i++) {
                var el = items[i];
                if (el) {
                    el.muted = false;
                    if (el.volume < 1.0) el.volume = 1.0;
                    hookMediaElement(el);
                }
            }
        }

        scanMedia();
        setInterval(scanMedia, 500);

        if (document.documentElement) {
            var obs = new MutationObserver(scanMedia);
            obs.observe(document.documentElement, { childList: true, subtree: true });
        }
    })();
    """.trimIndent()
}
