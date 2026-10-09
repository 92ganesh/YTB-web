package com.ytbweb;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;

/* ============================================================================
  MainActivity.java - THE ONLY SCREEN (monolith: UI host + bridge)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: think `public class MainFrame extends JFrame` containing one
  giant browser panel. Your index.html/css/js ARE the UI — this class is just
  the frame around them plus three superpowers a Chrome tab never gets:
    1. KeepAliveWebView hack -> YouTube never learns the screen locked, so it
       never self-pauses (concept from the YTbackground repo, tracking removed)
    2. AdBlockClient firewall (below) -> ad hosts get an EMPTY response before
       they load (stronger than CSS hiding: ads never download)
    3. PlaybackService bridge -> persistent notification tells Android
       "user-visible work happening, do NOT kill me" (like Spotify)
============================================================================= */
public class MainActivity extends Activity {

    /* -- DEV vs FINAL switch: the one line from our plan. --------------------
       DEV (now):      your Mac serves files, edits refresh in seconds.
       FINAL (later):  comment the http line, uncomment the file:/// line,
                       and copy the 3 web files into app/src/main/assets/www/.
       JAVA: like a `static final String BASE_URL` constant choosing profiles. */
    private static final String START_URL = "http://192.168.1.35:8000";
    // private static final String START_URL = "file:///android_asset/www/index.html";

    static KeepAliveWebView webView; // static so ActionReceiver can reach it
    static boolean isPlaying = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // activity_main.xml = one full-screen WebView (like new JFrame + add()).
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);      // your app.js needs this (default OFF)
        s.setDomStorageEnabled(true);      // localStorage = your history file
        s.setMediaPlaybackRequiresUserGesture(false); // allow JS-driven play

        webView.setWebViewClient(new AdBlockClient()); // firewall (part 2 file)
        webView.setWebChromeClient(new android.webkit.WebChromeClient());

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL);
        }
        startPlaybackService(); // "do not kill me" notification
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, PlaybackService.class));
        super.onDestroy();
    }

    private void startPlaybackService() {
        Intent i = new Intent(this, PlaybackService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i); // Android 8+: required (else crash)
        } else {
            startService(i);
        }
    }

    // Called by ActionReceiver (notification pause/play button).
    static void toggleFromNotification(Context ctx) {
        if (webView == null) return;
        // Must run on UI thread (like SwingUtilities.invokeLater).
        webView.post(() -> webView.evaluateJavascript(
                "(function(){var b=document.getElementById('playPauseBtn');"
                + "if(b){b.click();return b.textContent;}return '';})();",
                value -> { /* page button already toggled playback */ }));
    }
}
