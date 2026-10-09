package com.ytbweb;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;

/* ============================================================================
  MainActivity.java - YOUTUBE BROWSER SHELL (monolith: browser + bridge)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: `public class MainFrame extends JFrame` with one giant browser
  panel. BIG CHANGE from v1: instead of your custom index.html, we open REAL
  YouTube (m.youtube.com) — browse, search, suggestions, playlists, login all
  work natively. Our code adds three superpowers a Chrome tab never gets:
    1. KeepAliveWebView hack -> YouTube never learns the screen locked, so it
       never self-pauses (concept from the YTbackground repo, tracking removed)
    2. AdBlockClient firewall -> ad hosts get an EMPTY response before they
       load (stronger than CSS hiding: ads never download)
    3. PlaybackService bridge -> persistent notification (title + thumbnail +
       Pause/Play) tells Android "do NOT kill me" (like Spotify)
  BACK/FORWARD: phone Back button walks WebView history first (like a real
  browser); only exits when no history left. Pull-to-refresh NOT included —
  use YouTube's own UI. Desktop-mode toggle lives in the menu (see below).
============================================================================= */
public class MainActivity extends Activity {

    /* -- START PAGE -----------------------------------------------------------
       m.youtube.com = mobile site: fastest, touch-first, designed for phones.
       Your old custom page is still reachable via DEV_URL for testing the
       bundled-vs-server switch, but YouTube is now the default home. */
    private static final String HOME_URL = "https://m.youtube.com";
    private static final String DEV_URL = "http://192.168.1.35:8000";
    // private static final String START_URL = "file:///android_asset/www/index.html";

    // Set true to open your custom page instead of YouTube (dev/testing).
    private static final boolean USE_DEV_PAGE = false;

    private static String startUrl() {
        return USE_DEV_PAGE ? DEV_URL : HOME_URL;
    }

    static KeepAliveWebView webView; // static so ActionReceiver can reach it
    static boolean isPlaying = false;
    static final NowPlaying nowPlaying = new NowPlaying(); // shared model

    // Debounce: don't spam the service with an UPDATE per keystroke.
    private long lastUpdateSent = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // activity_main.xml = one full-screen WebView (like new JFrame + add()).
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);      // YouTube needs this (default OFF)
        s.setDomStorageEnabled(true);      // login sessions + prefs need this
        s.setMediaPlaybackRequiresUserGesture(false); // background resume
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);

        // Bridge: page JS -> Java model (title + playing state for notif).
        // JAVA: addJavascriptInterface(obj, name) = registering an event
        // listener the page can call: window.YtbNative.pushState(t, playing).
        webView.addJavascriptInterface(nowPlaying, "YtbNative");

        AdBlockClient client = new AdBlockClient();
        // Callback: AdBlockClient tells us URL/title changes (Observer).
        client.setListener(new AdBlockClient.Listener() {
            @Override
            public void onPageChanged(String url, String title) {
                onWebPageChanged(url, title);
            }
        });
        webView.setWebViewClient(client);
        webView.setWebChromeClient(new android.webkit.WebChromeClient());

        handleIntent(getIntent()); // shared video? (Share -> YTB Web)
        if (webView.getUrl() == null && savedInstanceState == null) {
            webView.loadUrl(startUrl());
        }
        startPlaybackService(); // "do not kill me" notification
    }

    // New intent while running (singleTask not set, but SEND may re-deliver).
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // SHARE TARGET: "Share -> YTB Web" from any app opens the shared text.
    // If it contains a YouTube URL/ID, load it directly; else search YouTube.
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null || text.trim().isEmpty()) return;
            String id = NowPlaying.extractId(text.trim());
            if (id != null) {
                webView.loadUrl("https://m.youtube.com/watch?v=" + id);
            } else {
                try {
                    String q = java.net.URLEncoder.encode(text.trim(), "UTF-8");
                    webView.loadUrl("https://m.youtube.com/results?search_query=" + q);
                } catch (Exception e) {
                    webView.loadUrl(startUrl());
                }
            }
        }
    }

    // Phone Back = browser back (like a real browser). Exit only at history root.
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // Fired on page finish + title/URL change: update model + notification.
    // Debounced to ~1/sec (YouTube fires many events per navigation).
    void onWebPageChanged(String url, String title) {
        nowPlaying.onUrl(url); // video ID + thumbnail URL (fallback path)
        long now = System.currentTimeMillis();
        if (now - lastUpdateSent < 1000) return;
        lastUpdateSent = now;
        pushToService(title);
    }

    // Send current model -> PlaybackService (which re-posts the notification).
    // JAVA: notifyObservers(model) — service is the observer.
    void pushToService(String title) {
        if (title != null && !title.isEmpty()) {
            nowPlaying.pushState(title, nowPlaying.playing);
        }
        Intent u = new Intent(this, PlaybackService.class);
        u.setAction(PlaybackService.ACTION_UPDATE);
        u.putExtra("title", nowPlaying.title);
        u.putExtra("subtitle", nowPlaying.subtitle);
        u.putExtra("artUrl", nowPlaying.artUrl == null ? "" : nowPlaying.artUrl);
        u.putExtra("playing", isPlaying);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(u);
        } else {
            startService(u);
        }
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
    // WORKS ON REAL YOUTUBE: clicks the biggest <video> element directly.
    // (Old version clicked our custom page's #playPauseBtn — that page is
    // gone as the home screen, so this now drives YouTube's own player.)
    static void toggleFromNotification(Context ctx) {
        if (webView == null) return;
        // Must run on UI thread (like SwingUtilities.invokeLater).
        webView.post(() -> webView.evaluateJavascript(
                "(function(){"
                + "var vs=document.querySelectorAll('video');"
                + "if(!vs.length)return 'no-video';"
                + "var v=vs[0];"
                + "for(var i=1;i<vs.length;i++){"
                + "if(vs[i].clientWidth*vs[i].clientHeight>"
                + "v.clientWidth*v.clientHeight){v=vs[i];}}"
                + "if(v.paused){v.play();return 'playing';}"
                + "else{v.pause();return 'paused';}"
                + "})();",
                value -> {
                    // value is "playing"/"paused" (quoted JSON string).
                    boolean playing = value != null && value.contains("playing");
                    isPlaying = playing;
                    nowPlaying.playing = playing;
                    // Refresh notification label immediately (no debounce).
                    if (ctx != null) {
                        Intent u = new Intent(ctx, PlaybackService.class);
                        u.setAction(PlaybackService.ACTION_UPDATE);
                        u.putExtra("title", nowPlaying.title);
                        u.putExtra("subtitle", nowPlaying.subtitle);
                        u.putExtra("artUrl", nowPlaying.artUrl == null ? "" : nowPlaying.artUrl);
                        u.putExtra("playing", playing);
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                ctx.startForegroundService(u);
                            } else {
                                ctx.startService(u);
                            }
                        } catch (Exception ignored) { }
                    }
                }));
    }

    // Called by ActionReceiver NEXT button: naive "next" = history forward.
    // (True YouTube-up-next would need playlist parsing; forward-or-reload
    // is the honest v1. Playlist/autoplay support can come later.)
    static void nextFromNotification(Context ctx) {
        if (webView == null) return;
        webView.post(() -> {
            if (webView.canGoForward()) webView.goForward();
            else webView.reload();
        });
    }
}
