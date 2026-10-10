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
     * Injected Web Audio API Interceptor script.
     * 1. Re-routes <video> and <audio> HTML elements into Web Audio API.
     * 2. Feeds raw 48kHz 16-bit PCM samples into C3AudioBridge for direct ESP transmission.
     * 3. Prevents media elements from connecting to audioCtx.destination, guaranteeing
     *    the audio NEVER plays out through the phone's physical speakers!
     */
    val AUDIO_CAPTURE_AND_MUTE_SCRIPT = """
    (function() {
        if (window._c3_audio_installed) return;
        window._c3_audio_installed = true;

        var audioCtx = null;
        function getContext() {
            if (!audioCtx) {
                var AC = window.AudioContext || window.webkitAudioContext;
                if (AC) {
                    audioCtx = new AC({ sampleRate: 48000 });
                }
            }
            if (audioCtx && audioCtx.state === 'suspended') {
                audioCtx.resume();
            }
            return audioCtx;
        }

        var zeroGain = null;
        function getZeroGain(ctx) {
            if (!zeroGain) {
                zeroGain = ctx.createGain();
                zeroGain.gain.value = 0.0;
                zeroGain.connect(ctx.destination);
            }
            return zeroGain;
        }

        function hookMediaElement(el) {
            if (!el || el._c3_captured) return;
            el._c3_captured = true;

            try {
                var ctx = getContext();
                if (!ctx) return;

                // Re-route media element audio into Web Audio graph.
                // In standard Web Audio API, once createMediaElementSource is called,
                // the element's output is disconnected from the system default output (speakers)!
                var source = ctx.createMediaElementSource(el);
                var processor = ctx.createScriptProcessor(2048, 2, 2);

                processor.onaudioprocess = function(evt) {
                    if (!window.C3AudioBridge) return;
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
                        var u8 = new Uint8Array(pcm16.buffer);
                        var str = '';
                        var blen = u8.byteLength;
                        for (var j = 0; j < blen; j++) {
                            str += String.fromCharCode(u8[j]);
                        }
                        window.C3AudioBridge.sendAudioPcm(btoa(str));
                    }
                };

                source.connect(processor);
                // Connect to a 0.0 gain node to keep processor active without producing speaker sound
                processor.connect(getZeroGain(ctx));
            } catch(err) {
                // If createMediaElementSource is restricted by cross-origin, mute the element so it never plays out loud
                el.muted = true;
            }
        }

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
