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
     * Injected Web Audio API Interceptor & Lockscreen Continuous Playback Engine.
     *
     * 1. Re-routes <video> and <audio> HTML elements into Web Audio API.
     * 2. Feeds raw PCM samples at exact hardware sample rate into C3AudioBridge for direct ESP transmission.
     * 3. Prevents media elements from producing sound through physical phone speakers (inaudible 1e-6 gain).
     * 4. Intercepts screen lock / background pause events so playback continues seamlessly with screen off.
     * 5. Uses Web Worker keep-alive ticker and fast chunked Base64 encoding for rock-solid stability.
     */
    fun getScript(targetSampleRate: Int = 44100): String = """
    (function() {
        window._c3_streaming_active = true;
        window._c3_target_sample_rate = $targetSampleRate;

        if (window._c3_audio_installed) return;
        window._c3_audio_installed = true;

        // 1. Fast Base64 encoder for 16-bit PCM chunks (eliminates GC pauses)
        function uint8ToBase64(u8) {
            var CHUNK_SIZE = 8192;
            var index = 0;
            var length = u8.length;
            var result = '';
            while (index < length) {
                var slice = u8.subarray(index, Math.min(index + CHUNK_SIZE, length));
                result += String.fromCharCode.apply(null, slice);
                index += CHUNK_SIZE;
            }
            return btoa(result);
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

        // 3. Keep audio graph active without audible speaker output
        // Setting microscopic gain (1e-6) prevents Blink from discarding the AudioNode on screen-off
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

            try {
                var ctx = getContext();
                if (!ctx) return;

                var source = ctx.createMediaElementSource(el);
                var processor = ctx.createScriptProcessor(2048, 2, 2);

                processor.onaudioprocess = function(evt) {
                    if (!window.C3AudioBridge || !window._c3_streaming_active) return;
                    var input = evt.inputBuffer;
                    var left = input.getChannelData(0);
                    var right = input.numberOfChannels > 1 ? input.getChannelData(1) : left;
                    var len = left.length;

                    var hasAudio = false;
                    var pcm16 = new Int16Array(len * 2);
                    for (var i = 0; i < len; i++) {
                        var l = left[i];
                        var r = right[i];
                        if (l !== 0 || r !== 0) hasAudio = true;
                        l = Math.max(-1, Math.min(1, l));
                        r = Math.max(-1, Math.min(1, r));
                        pcm16[i * 2] = l < 0 ? l * 0x8000 : l * 0x7FFF;
                        pcm16[i * 2 + 1] = r < 0 ? r * 0x8000 : r * 0x7FFF;
                    }

                    if (hasAudio) {
                        var b64 = uint8ToBase64(new Uint8Array(pcm16.buffer));
                        window.C3AudioBridge.sendAudioPcm(b64);
                    }
                };

                source.connect(processor);
                processor.connect(getSilentGain(ctx));
            } catch(err) {
                // If createMediaElementSource is restricted by cross-origin, keep reference for auto-play
            }

            // Auto-resume if screen lock causes browser to trigger pause on media element
            el.addEventListener('pause', function() {
                if (window._c3_streaming_active && !el.ended && !window._c3_user_pause) {
                    setTimeout(function() {
                        if (el.paused && !window._c3_user_pause) {
                            el.play().catch(function() {});
                        }
                    }, 50);
                }
            });
        }

        // 5. Intercept HTMLMediaElement.prototype.pause to block background auto-pauses
        var originalPause = HTMLMediaElement.prototype.pause;
        HTMLMediaElement.prototype.pause = function() {
            if (window._c3_streaming_active && !this.ended && !window._c3_user_pause) {
                return; // Discard automated pause from Android background/lockscreen
            }
            return originalPause.apply(this, arguments);
        };

        // User intentional pause detector
        window.addEventListener('pointerdown', function() {
            window._c3_user_pause = true;
            setTimeout(function() { window._c3_user_pause = false; }, 350);
        }, true);

        // 6. Fake visibility state so YouTube Music / web players think screen is always ON
        try {
            Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
            Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
            Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
        } catch(e) {}

        // 7. Block visibilitychange and blur events from stopping playback
        var blockedEvents = ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide'];
        blockedEvents.forEach(function(evt) {
            window.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
            document.addEventListener(evt, function(e) { e.stopImmediatePropagation(); }, true);
        });

        // 8. Continuous Web Worker keep-alive (unthrottled background heartbeat)
        try {
            if (!window._c3_worker) {
                var workerBlob = new Blob([
                    'setInterval(function() { postMessage("tick"); }, 100);'
                ], { type: 'application/javascript' });
                window._c3_worker = new Worker(URL.createObjectURL(workerBlob));
                window._c3_worker.onmessage = function() {
                    if (audioCtx && audioCtx.state === 'suspended') {
                        audioCtx.resume().catch(function() {});
                    }
                    if (window._c3_last_media && window._c3_last_media.paused && !window._c3_last_media.ended && !window._c3_user_pause) {
                        window._c3_last_media.play().catch(function() {});
                    }
                };
            }
        } catch(e) {}

        // 9. Scan DOM for existing and new media elements
        function scanMedia() {
            var items = document.querySelectorAll('video, audio');
            for (var i = 0; i < items.length; i++) {
                hookMediaElement(items[i]);
            }
        }

        scanMedia();
        setInterval(scanMedia, 800);

        if (document.documentElement) {
            var obs = new MutationObserver(scanMedia);
            obs.observe(document.documentElement, { childList: true, subtree: true });
        }
    })();
    """.trimIndent()
}
