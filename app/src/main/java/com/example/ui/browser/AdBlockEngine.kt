package com.example.ui.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * High-performance ad and tracking blocker engine for the In-App Web Browser.
 * Intercepts network requests and suppresses advertising scripts, banners, video pre-roll
 * trackers, and analytics telemetry (especially targeting YouTube Music, YouTube, and web streaming portals).
 */
object AdBlockEngine {

    // Known ad servers, tracking networks, and YouTube ad delivery endpoints
    private val blockedDomainKeywords = listOf(
        // Google / YouTube Ad Networks
        "googleads.g.doubleclick.net",
        "ad.doubleclick.net",
        "pagead2.googlesyndication.com",
        "adservice.google.com",
        "stats.g.doubleclick.net",
        "youtube.com/pagead",
        "youtube.com/api/stats/ads",
        "youtube.com/ptracking",
        "youtube.com/get_midroll_info",
        "googlesyndication.com",
        
        // General Ad Servers & Trackers
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
        "connect.facebook.net/en_US/fbevents.js",
        "hotjar.com",
        "branch.io"
    )

    // Fast URL path matches specifically for video / music audio ad calls
    private val blockedPathPatterns = listOf(
        "/pagead/",
        "/ptracking",
        "/api/stats/ads",
        "/get_midroll_info",
        "/pcs/activeview",
        "/generate_204?target=ad",
        "/doubleclick/"
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
        /* YouTube Music & YouTube Ad containers */
        ytmusic-mealbar-promo-renderer,
        ytmusic-banner-promo-renderer,
        ytmusic-guide-promo-entry-renderer,
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
        #player-ads,
        #masthead-ad,
        ytd-promoted-sparkles-web-renderer,
        ytd-banner-promo-renderer,
        ytd-ad-slot-renderer,
        ytd-in-feed-ad-layout-renderer,
        tp-yt-paper-dialog[aria-label*="Premium"],
        /* General Web Ad Classes */
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
     * JavaScript code that auto-skips YouTube / YouTube Music video and audio ads instantly,
     * mutes video ad elements while playing, and clicks dismiss buttons on promos.
     */
    val adSkipperScript: String = """
        (function() {
            // Guard against multiple registrations
            if (window.__c3_adblock_installed) return;
            window.__c3_adblock_installed = true;

            // 1. Inject Ad-hiding CSS
            var style = document.createElement('style');
            style.id = 'c3_adblock_style';
            style.innerHTML = '$adHidingCss';
            (document.head || document.documentElement).appendChild(style);

            // 2. Continuous ad monitoring & auto-skip engine
            function autoSkipAds() {
                try {
                    // Check if player is currently in an ad state
                    var adShowing = document.querySelector('.ad-showing, .ad-interrupting');
                    var video = document.querySelector('video');

                    if (adShowing && video) {
                        // Fast forward ad immediately to its end and unmute
                        if (!isNaN(video.duration) && video.duration > 0) {
                            video.currentTime = video.duration;
                        }
                        video.playbackRate = 16.0; // Play remaining milliseconds at 16x speed
                    }

                    // Click native Skip Ad buttons automatically
                    var skipButtons = document.querySelectorAll(
                        '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, [id*="skip-button"], button.ytmusic-banner-promo-renderer[aria-label*="Dismiss"], tp-yt-paper-button#dismiss-button'
                    );
                    skipButtons.forEach(function(btn) {
                        if (btn && typeof btn.click === 'function') {
                            btn.click();
                        }
                    });

                    // Dismiss mealbar promos ("Get Premium", "Try Music Premium")
                    var mealbarDismiss = document.querySelector('ytmusic-mealbar-promo-renderer #dismiss-button');
                    if (mealbarDismiss) {
                        mealbarDismiss.click();
                    }
                } catch(e) {}
            }

            // Run scanner at frequent intervals (every 250ms) to eliminate ads the instant they appear
            setInterval(autoSkipAds, 250);
        })();
    """.trimIndent()
}
