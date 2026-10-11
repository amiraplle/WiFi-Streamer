package com.example.ui.browser

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * Custom WebView that prevents Android and Chromium from pausing media playback,
 * audio rendering, Web Audio API, and JavaScript execution when the app is minimized,
 * the screen is locked, or the user navigates between Compose screens.
 */
class BackgroundAudioWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    override fun onWindowVisibilityChanged(visibility: Int) {
        // Always pass View.VISIBLE to Chromium's internal AwContents / WebContents
        // so it never calls mWebContents.onHide(), which pauses background audio & video!
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        super.dispatchWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        // Always report true so web players do not pause on window focus loss
        super.onWindowFocusChanged(true)
    }

    override fun onPause() {
        // Intentionally no-op: calling webView.onPause() pauses WebGL, timers, and audio!
    }

    override fun pauseTimers() {
        // Intentionally no-op: never freeze JavaScript timers for background audio streaming!
    }
}
