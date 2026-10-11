package com.example.ui.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * High-performance ad and tracking blocker engine for the In-App Web Browser.
 * Intercepts network requests and suppresses advertising scripts, banners, video pre-roll
 * trackers, and analytics telemetry (specifically engineered for zero-ad streaming on
 * YouTube Music, YouTube, SoundCloud, and web media portals).
 */
object AdBlockEngine {

    // Known ad servers, tracking networks, and YouTube ad delivery endpoints
    private val blockedDomainKeywords = listOf(
        // Google / YouTube Ad Networks & Telemetry
        "googleads.g.doubleclick.net",
        "ad.doubleclick.net",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "static.doubleclick.net",
        "doubleclick.net",
        "pagead2.googlesyndication.com",
        "pagead2.google.com",
        "adservice.google.",
        "stats.g.doubleclick.net",
        "youtube.com/pagead",
        "youtube.com/api/stats/ads",
        "youtube.com/ptracking",
        "youtube.com/get_midroll_info",
        "googlesyndication.com",
        "ads.youtube.com",
        "ad.youtube.com",
        "googletagservices.com",
        "googletagmanager.com",
        "google-analytics.com",
        "fundingchoicesmessages.google.com",
        
        // General Ad Networks & Aggressive Trackers
        "scorecardresearch.com",
        "adnxs.com",
        "advertising.com",
        "adsystem.com",
        "moatads.com",
        "adroll.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "quantserve.com",
        "pubmatic.com",
        "rubiconproject.com",
        "amazon-adsystem.com",
        "analytics.twitter.com",
        "facebook.com/tr",
        "connect.facebook.net",
        "hotjar.com",
        "branch.io",
        "chartbeat.com",
        "innovid.com",
        "smartadserver.com",
        "casalemedia.com"
    )

    // Fast URL path matches specifically for video / music audio ad calls
    private val blockedPathPatterns = listOf(
        "/pagead/",
        "/ptracking",
        "/api/stats/ads",
        "/get_midroll_info",
        "/pcs/activeview",
        "/generate_204?target=ad",
        "/doubleclick/",
        "/ad_break",
        "/youtubei/v1/att/",
        "/api/stats/atr",
        "adformat=",
        "ad_type=",
        "adunit=",
        "ctier=A"
    )

    /**
     * Determines whether a given URL should be blocked as an ad or tracker.
     */
    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lowerUrl = url.lowercase()

        // Fast check on path patterns
        for (pattern in blockedPathPatterns) {
            if (lowerUrl.contains(pattern)) return true
        }

        // Domain checks
        return try {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            blockedDomainKeywords.any { domain ->
                host == domain || host.endsWith(".$domain") || lowerUrl.contains(domain)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns a blank response (200 OK with empty body) to silently drop the blocked request
     * without causing page errors or breaking subsequent fetch promises in the DOM.
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * CSS injection that completely hides ad banners, overlays, sponsor teasers,
     * promo popups, and YouTube Music subscription upsells.
     */
    val adHidingCss: String = """
        ytmusic-mealbar-promo-renderer,
        ytmusic-banner-promo-renderer,
        ytmusic-guide-promo-entry-renderer,
        ytmusic-popup-container tp-yt-paper-dialog,
        .ytmusic-mealbar-promo-renderer,
        .ad-showing,
        .ad-container,
        .ad-div,
        .video-ads,
        .ytp-ad-module,
        .ytp-ad-overlay-container,
        .ytp-ad-text,
        .ytp-ad-preview-container,
        .ytp-ad-skip-button-container,
        .ytp-ad-skip-button-slot,
        #player-ads,
        #masthead-ad,
        ytd-promoted-sparkles-web-renderer,
        ytd-banner-promo-renderer,
        ytd-ad-slot-renderer,
        ytd-in-feed-ad-layout-renderer,
        ytd-popup-container,
        tp-yt-paper-dialog[aria-label*="Premium"],
        tp-yt-paper-dialog[aria-label*="Music Premium"],
        .ytmusic-upsell-dialog-renderer,
        ytmusic-you-there-renderer,
        [class*="ad-box"],
        [class*="banner-ad"],
        [class*="sponsored-post"],
        [id*="google_ads"],
        [id*="ad-slot"],
        iframe[src*="doubleclick"],
        iframe[src*="googleads"] {
            display: none !important;
            visibility: hidden !important;
            height: 0 !important;
            width: 0 !important;
            opacity: 0 !important;
            pointer-events: none !important;
        }
    """.trimIndent().replace("\n", " ")

    /**
     * JavaScript engine that intercepts YouTube ad responses, auto-skips video & audio ads,
     * strips promo popups, and provides a completely clean ad-free playback experience.
     */
    val adSkipperScript: String = """
        (function() {
            // 1. Inject or update Ad-Hiding CSS
            var existingStyle = document.getElementById('c3_adblock_style');
            if (!existingStyle) {
                var style = document.createElement('style');
                style.id = 'c3_adblock_style';
                style.innerHTML = '$adHidingCss';
                (document.head || document.documentElement).appendChild(style);
            }

            // 2. Strip YouTube ad placements from client player data if available
            if (window.ytInitialPlayerResponse) {
                try {
                    window.ytInitialPlayerResponse.adPlacements = [];
                    window.ytInitialPlayerResponse.playerAds = [];
                    window.ytInitialPlayerResponse.adSlots = [];
                } catch(e) {}
            }

            // 3. Intercept fetch to sanitize player responses before playback
            try {
                if (!window.__c3_fetch_hooked && window.fetch) {
                    window.__c3_fetch_hooked = true;
                    var originalFetch = window.fetch;
                    window.fetch = function() {
                        var args = arguments;
                        var url = (args && args[0]) ? args[0].toString() : '';
                        return originalFetch.apply(this, args).then(function(response) {
                            if (url.indexOf('youtubei/v1/player') !== -1 || url.indexOf('youtubei/v1/next') !== -1) {
                                return response.clone().json().then(function(data) {
                                    if (data) {
                                        if (data.adPlacements) data.adPlacements = [];
                                        if (data.playerAds) data.playerAds = [];
                                        if (data.adSlots) data.adSlots = [];
                                    }
                                    return new Response(JSON.stringify(data), {
                                        status: response.status,
                                        statusText: response.statusText,
                                        headers: response.headers
                                    });
                                }).catch(function() {
                                    return response;
                                });
                            }
                            return response;
                        });
                    };
                }
            } catch(e) {}

            // 4. Ultra-fast Ad-Skipper & Promo Dismissal Loop
            function eliminateAds() {
                try {
                    var player = document.querySelector('#movie_player, .html5-video-player, ytmusic-player');
                    var isAdShowing = document.querySelector('.ad-showing, .ad-interrupting, .video-ads, [class*="ytp-ad"]') ||
                                      (player && player.classList && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')));
                    var video = document.querySelector('video');

                    if (isAdShowing && video) {
                        video.muted = true;
                        if (isFinite(video.duration) && video.duration > 0) {
                            video.currentTime = video.duration;
                        }
                        video.playbackRate = 16.0;
                    }

                    // Auto-click Skip Ad buttons instantly
                    var skipButtons = document.querySelectorAll(
                        '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-slot, [id*="skip-button"], .videoAdUiSkipButton, .ytp-ad-overlay-close-button'
                    );
                    for (var i = 0; i < skipButtons.length; i++) {
                        var btn = skipButtons[i];
                        if (btn && typeof btn.click === 'function') {
                            btn.click();
                        }
                    }

                    // Auto-dismiss mealbar promos and Premium upsell modals
                    var dismissButtons = document.querySelectorAll(
                        'ytmusic-mealbar-promo-renderer #dismiss-button, ytmusic-mealbar-promo-renderer button, tp-yt-paper-dialog #dismiss-button, tp-yt-paper-button#dismiss-button, ytd-popup-container #dismiss-button, [aria-label*="Dismiss"], [aria-label*="No thanks"]'
                    );
                    for (var j = 0; j < dismissButtons.length; j++) {
                        var dBtn = dismissButtons[j];
                        if (dBtn && typeof dBtn.click === 'function') {
                            dBtn.click();
                        }
                    }
                } catch(e) {}
            }

            if (!window.__c3_ad_interval) {
                window.__c3_ad_interval = setInterval(eliminateAds, 50);
            }

            if (document.documentElement && !window.__c3_ad_observer) {
                window.__c3_ad_observer = new MutationObserver(eliminateAds);
                window.__c3_ad_observer.observe(document.documentElement, { childList: true, subtree: true });
            }
        })();
    """.trimIndent()
}
