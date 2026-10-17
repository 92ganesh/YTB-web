package com.ytbweb;

import android.view.View;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;

/* ============================================================================
  FullscreenHandler.java - LANDSCAPE FULLSCREEN VIDEO (YouTube fullscreen btn)
  ----------------------------------------------------------------------------
  WHY IT WAS BROKEN: YouTube enters fullscreen by calling
  WebChromeClient.onShowCustomView(videoView, callback). The default
  WebChromeClient IGNORES it (empty no-op), so the button silently did
  nothing. This handler implements the standard Android pattern:
    ENTER: hide WebView, add YouTube's video View to a black overlay covering
           the whole screen (like swapping JFrame content to a video panel),
           force LANDSCAPE, hide system bars (immersive).
    EXIT:  remove overlay, restore WebView, back to PORTRAIT, show bars,
           notify YouTube via onCustomViewHidden (else its player stays stuck).
  JAVA ANALOGY: a CardLayout with two cards ("browser" and "video") plus an
  orientation lock — show("video") on enter, show("browser") on exit.
  Telemetry: enter/exit/back-press events so the Mac log shows the flow.
============================================================================= */
public class FullscreenHandler extends WebChromeClient {

    /** Host activity must implement this (MainActivity does). */
    public interface Host {
        android.app.Activity activity();
        FrameLayout decorRoot();
        View browserView();
        void onFullscreenChanged(boolean fullscreen);
    }

    private final Host host;
    private View customView = null;              // YouTube's video view
    private CustomViewCallback customCallback;   // YouTube's exit handshake

    public FullscreenHandler(Host host) {
        this.host = host;
    }

    public boolean isFullscreen() {
        return customView != null;
    }

    // YouTube calls this when its fullscreen button is tapped.
    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (customView != null) { // already fullscreen: reject 2nd request
            try { callback.onCustomViewHidden(); } catch (Exception ignored) { }
            return;
        }
        customView = view;
        customCallback = callback;
        try {
            // NOTE: Keep browserView VISIBLE behind the overlay. Calling View.GONE
            // on WebView halts media/video rendering in Chromium on modern Android.
            // fullscreenContainer is an opaque black (#000000) frame that fully covers it.
            FrameLayout root = host.decorRoot();
            root.addView(customView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            root.setVisibility(View.VISIBLE);
            // Lock landscape + immersive (like setExtendedState(MAXIMIZED)).
            host.activity().setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideBars();
            Telemetry.send("nav", "fullscreen ENTER");
        } catch (Exception e) {
            Telemetry.send("error", "fullscreen enter failed: " + e);
            exitCleanup();
        }
        try { host.onFullscreenChanged(true); } catch (Exception ignored) { }
    }

    // YouTube calls this on its own exit (button, gesture) — same as Back.
    @Override
    public void onHideCustomView() {
        exitFullscreen();
    }

    /** Phone Back button should exit fullscreen FIRST (browser back second). */
    public boolean onBackPressed() {
        if (isFullscreen()) {
            exitFullscreen();
            return true; // consumed (like KeyEvent.consume())
        }
        return false;
    }

    private void exitFullscreen() {
        if (customView == null) return;
        try {
            Telemetry.send("nav", "fullscreen EXIT");
        } catch (Exception ignored) { }
        exitCleanup();
        try { host.onFullscreenChanged(false); } catch (Exception ignored) { }
    }

    // Shared teardown: remove overlay, restore browser, portrait, bars, ack.
    private void exitCleanup() {
        try {
            FrameLayout root = host.decorRoot();
            if (customView != null) root.removeView(customView);
            root.setVisibility(View.GONE); // Hide the black overlay so WebView is visible
        } catch (Exception ignored) { }
        customView = null;
        try {
            host.activity().setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            showBars();
        } catch (Exception ignored) { }
        // Handshake: REQUIRED, else YouTube thinks it's still fullscreen.
        try {
            if (customCallback != null) customCallback.onCustomViewHidden();
        } catch (Exception ignored) { }
        customCallback = null;
    }

    // Immersive: hide status + nav bars (sticky = they peek on swipe, re-hide).
    private void hideBars() {
        try {
            View decor = host.activity().getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            decor.setSystemUiVisibility(flags);
        } catch (Exception ignored) { }
    }

    private void showBars() {
        try {
            View decor = host.activity().getWindow().getDecorView();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Exception ignored) { }
    }
}
