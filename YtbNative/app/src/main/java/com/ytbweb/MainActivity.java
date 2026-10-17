package com.ytbweb;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
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
    private static final String DEV_URL = "http://192.168.1.34:8000";
    // private static final String START_URL = "file:///android_asset/www/index.html";

    // Set true to open your custom page instead of YouTube (dev/testing).
    private static final boolean USE_DEV_PAGE = false;

    private static String startUrl() {
        return USE_DEV_PAGE ? DEV_URL : HOME_URL;
    }

    static KeepAliveWebView webView; // static so ActionReceiver can reach it
    static boolean isPlaying = false;
    static final NowPlaying nowPlaying = new NowPlaying(); // shared model
    static MainActivity instance = null; // set in onCreate, cleared in onDestroy

    // Structured poller: every 2s, re-read the page state and refresh the
    // notification ONLY if something changed (dirty-flag check, not blind).
    // JAVA: like a ScheduledExecutorService polling a model for dirty flags.
    private final android.os.Handler pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String lastPushedTitle = "";
    private boolean lastPushedPlaying = false;
    private String lastPushedVideoId = "";
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            try {
                pollOnce();
            } catch (Exception ignored) { }
            pollHandler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
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
        // Telemetry self-test: ONE ping at startup proves the whole pipeline
        // (queue -> network -> Mac server). If this line never appears on the
        // Mac, the endpoint IP is stale or the server isn't running — check
        // `ipconfig getifaddr en0` on the Mac. (Like a @PostConstruct log line
        // confirming your appender reached its collector.)
        Telemetry.send("nav", "app started: " + startUrl());
        pollHandler.postDelayed(pollTask, 2000); // start structured poller
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

    // 3-dot menu (no XML needed): Home / Diagnostics / Desktop-site / Ping.
    // JAVA: like building a JMenuBar in code instead of a .form file.
    private static final int MENU_HOME = 1;
    private static final int MENU_DIAG = 2;
    private static final int MENU_DESKTOP = 3;
    private static final int MENU_PING = 4;
    private boolean desktopMode = false;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_HOME, 0, "Home");
        menu.add(0, MENU_DIAG, 1, "YTB Diagnostics");
        menu.add(0, MENU_DESKTOP, 2, "Desktop site: OFF");
        menu.add(0, MENU_PING, 3, "Send test ping");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (webView == null) return super.onOptionsItemSelected(item);
        int id = item.getItemId();
        if (id == MENU_HOME) {
            webView.loadUrl(HOME_URL);
            return true;
        } else if (id == MENU_PING) {
            // Manual telemetry check: watch the Mac terminal — a "test ping"
            // line must appear within ~2s. If not, endpoint IP is stale.
            Telemetry.send("nav", "test ping (manual, from menu)");
            android.widget.Toast.makeText(this,
                    "Ping sent — check Mac terminal", android.widget.Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == MENU_DIAG) {
            // diag.html is bundled? No — served from DEV server in dev mode.
            // In FINAL mode copy diag.html to assets/www too (see README).
            webView.loadUrl(USE_DEV_PAGE ? DEV_URL + "/diag.html"
                    : "http://192.168.1.34:8000/diag.html");
            return true;
        } else if (id == MENU_DESKTOP) {
            // Desktop UA = youtube.com desktop layout (some users prefer it;
            // desktop markup uses different ad selectors — tell me if used).
            desktopMode = !desktopMode;
            WebSettings s = webView.getSettings();
            s.setUserAgentString(desktopMode
                    ? "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
                      + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
                    : null); // null = back to WebView default (mobile)
            item.setTitle("Desktop site: " + (desktopMode ? "ON" : "OFF"));
            webView.reload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // Fired on page finish + title/URL change: update model + notification.
    // v4: hookVideoEvents() attaches media listeners for INSTANT push on
    // play/pause (no waiting). The 2s structured poller (below) covers
    // everything else by re-reading state and pushing only on CHANGE.
    void onWebPageChanged(String url, String title) {
        nowPlaying.onUrl(url); // video ID + thumbnail URL (fallback path)
        hookVideoEvents();     // media listeners (idempotent per page)
        pushToService(title);  // immediate attempt (poller dedupes anyway)
    }

    // Structured poll: ask the page for its CURRENT title + playing state,
    // compare with last pushed values, and refresh the notification ONLY on
    // change. This replaces the old blind 1/sec debounce (which dropped
    // rapid play/pause flips) and guarantees eventual consistency even if a
    // bridge push was lost (new video loaded while screen locked, etc).
    // JAVA: dirty-flag check before repaint — skip work when model unchanged.
    private void pollOnce() {
        if (webView == null) return;
        // Delimiter "|||" separates title from state. Titles never contain
        // it in practice; even if one did, worst case = one extra refresh.
        final String pollJs = "(function(){"
                + "var vv=document.querySelector('#movie_player video,video');"
                + "var t='';try{t=document.title||'';}catch(e){}"
                + "var p=false;try{p=!!vv&&!vv.paused;}catch(e){}"
                + "return t+'|||'+(p?'1':'0');"
                + "})();";
        try {
            webView.evaluateJavascript(pollJs, value -> {
                try {
                    if (value == null) return;
                    // evaluateJavascript wraps strings in JSON quotes.
                    String unquoted = value;
                    if (unquoted.length() >= 2 && unquoted.startsWith("\"")
                            && unquoted.endsWith("\"")) {
                        unquoted = unquoted.substring(1, unquoted.length() - 1);
                    }
                    int sep = unquoted.lastIndexOf("|||");
                    if (sep < 0) return;
                    String title = unquoted.substring(0, sep);
                    boolean playing = unquoted.substring(sep + 3).startsWith("1");
                    String curUrl = webView.getUrl();
                    String vid = "";
                    if (curUrl != null) {
                        String found = NowPlaying.extractId(curUrl);
                        if (found != null) vid = found;
                    }
                    boolean changed = !title.equals(lastPushedTitle)
                            || playing != lastPushedPlaying
                            || !vid.equals(lastPushedVideoId);
                    if (changed) {
                        lastPushedTitle = title;
                        lastPushedPlaying = playing;
                        lastPushedVideoId = vid;
                        isPlaying = playing;
                        nowPlaying.playing = playing;
                        pushToService(title);
                    }
                } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    // Attach play/pause/ended listeners to the page's <video> (once per page).
    // JAVA: addEventListener("play", cb) = button.addActionListener(cb), but
    // for media events. Callbacks run on a background thread — posting the
    // service intent from there is fine (like sending from any thread).
    private void hookVideoEvents() {
        if (webView == null) return;
        final String hook =
                "(function(){"
                + "var v=document.querySelector('#movie_player video,video');"
                + "if(!v||v._ytbHooked||!window.YtbNative)return;"
                + "v._ytbHooked=true;"
                + "var push=function(){"
                + "try{window.YtbNative.pushState(document.title,!v.paused);}catch(e){}};"
                + "v.addEventListener('play',push);"
                + "v.addEventListener('pause',push);"
                + "v.addEventListener('ended',push);"
                + "push();"
                + "})();";
        try {
            webView.evaluateJavascript(hook, null);
        } catch (Exception ignored) { }
    }

    // Send current model -> PlaybackService (which re-posts the notification).
    // JAVA: notifyObservers(model) — service is the observer.
    void pushToService(String title) {
        if (title != null && !title.isEmpty()) {
            nowPlaying.pushState(title, nowPlaying.playing);
        }
        // Telemetry: navigation trail (which page, what title Java saw).
        // If url never contains watch?v=, user never reached a video page.
        Telemetry.send("nav", "update: " + nowPlaying.subtitle,
                "{\"title\":" + Telemetry.q(nowPlaying.title)
                + ",\"videoId\":" + Telemetry.q(nowPlaying.videoId == null ? "" : nowPlaying.videoId) + "}");
        sendUpdateToService();
    }

    // Immediate path used by the JS bridge (NowPlaying.pushState) when fresh
    // truth arrives: skip debounce bookkeeping, just sync + notify.
    void pushToServiceNow() {
        try {
            if (webView != null && webView.getUrl() != null) {
                nowPlaying.onUrl(webView.getUrl());
            }
        } catch (Exception ignored) { }
        sendUpdateToService();
    }

    // Shared intent builder: model -> PlaybackService ACTION_UPDATE.
    private void sendUpdateToService() {
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
        pollHandler.removeCallbacks(pollTask); // stop poller (avoid leaks)
        instance = null;
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
