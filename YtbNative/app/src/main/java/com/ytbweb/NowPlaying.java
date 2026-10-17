package com.ytbweb;

import android.text.TextUtils;
import android.webkit.JavascriptInterface;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/* ============================================================================
  NowPlaying.java - TITLE + THUMBNAIL TRACKER (fills the notification)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: a Model class holding "what is playing right now". Two inputs:
    1. @JavascriptInterface push (primary): the page's own JS calls
       window.YtbNative.pushState(title, playing) on every play/pause event,
       like firing a PropertyChangeEvent to a registered listener.
    2. URL parsing (fallback): extract video ID from youtube.com/watch?v=...
       and build the thumbnail URL i.ytimg.com/vi/ID/hqdefault.jpg directly —
       no API, no key (thumbnails are public static files, like favicons).
============================================================================= */
public class NowPlaying {

    // Matches v=VIDEOID in watch URLs (also youtu.be/ID, /shorts/ID, /embed/ID).
    private static final Pattern ID_PATTERNS[] = {
            Pattern.compile("[?&]v=([a-zA-Z0-9_-]{11})"),
            Pattern.compile("youtu\\.be/([a-zA-Z0-9_-]{11})"),
            Pattern.compile("/shorts/([a-zA-Z0-9_-]{11})"),
            Pattern.compile("/embed/([a-zA-Z0-9_-]{11})"),
    };

    volatile String title = "YTB Web";
    volatile String subtitle = "Open YouTube and play - tap to open";
    volatile String artUrl = null;
    volatile boolean playing = false;
    volatile String videoId = null;

    /** Called FROM JAVASCRIPT: window.YtbNative.pushState(title, isPlaying).
        Runs on a background thread — fields are volatile (like synchronized
        setters) so the service thread always sees fresh values. */
    @JavascriptInterface
    public void pushState(String pageTitle, boolean isPlaying) {
        // Sentinel events from the ad watchdog (not real titles — report, don't store).
        if ("__skip_clicked".equals(pageTitle)) {
            Telemetry.send("js", "skip button CLICKED by watchdog");
            return;
        }
        if ("__ad_seek".equals(pageTitle)) {
            Telemetry.send("js", "unskippable ad SEEK (muted + jumped to end)");
            return;
        }
        boolean titleChanged = false;
        if (!TextUtils.isEmpty(pageTitle)) {
            // YouTube titles come as "Video Name - YouTube"; strip the suffix.
            String clean = pageTitle.replaceAll("\\s*-\\s*YouTube\\s*$", "").trim();
            if (!clean.isEmpty() && !clean.equals(title)) {
                title = clean;
                titleChanged = true;
            }
        }
        boolean stateChanged = (playing != isPlaying);
        playing = isPlaying;
        // Telemetry: every bridge push = proof the JS watchdog is alive AND
        // the bridge object is registered. If these stop, injection died.
        Telemetry.send("js", "pushState: playing=" + isPlaying,
                "{\"title\":" + Telemetry.q(title) + "}");
        // INSTANT notification: bridge push = fresh truth from the page, so
        // tell the service NOW instead of waiting for the 2s poller. This is
        // the fix for "video plays but notification stays empty".
        // JAVA: property-change listener that repaints immediately on event.
        if ((titleChanged || stateChanged) && MainActivity.instance != null) {
            try {
                MainActivity.instance.pushToServiceNow();
            } catch (Exception ignored) { }
        }
    }

    /** Called from Java on every page load / URL change (fallback + art). */
    void onUrl(String url) {
        String id = extractId(url);
        if (id == null) return; // not a watch page (home, search, channel...)
        if (id.equals(videoId) && artUrl != null) return; // same video, keep art
        videoId = id;
        artUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        subtitle = "youtube.com/watch?v=" + id;
    }

    static String extractId(String url) {
        if (url == null) return null;
        for (Pattern p : ID_PATTERNS) {
            Matcher m = p.matcher(url);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}
