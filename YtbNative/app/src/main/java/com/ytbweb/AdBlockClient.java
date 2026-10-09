package com.ytbweb;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/* ============================================================================
  AdBlockClient.java - TUNED FIREWALL + PAGE-CHANGE EVENTS (v2)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: a Servlet Filter intercepting every request before it reaches
  the network. shouldInterceptRequest() fires for EVERY sub-resource (scripts,
  iframes, tracking pixels). Return an EMPTY response for ad hosts and the ad
  never downloads, never renders, never counts — strictly stronger than CSS
  hiding (which hides ads AFTER they load). The repo you found has NONE of
  this — plain WebView, all ads load.

  TUNING v2 (your request): expanded from 8 hosts to the EasyList-essentials
  for video ads: Google ads + YouTube stats/ads + major VAST providers
  (Innovid, Moat, FreeWheel, SpringServe, SpotX) + trackers. Substring list
  catches path-based endpoints (pagead, /ads/, vast, vmap...). If a new ad
  slips through: find its host in logcat (search "ADBLOCK") and add it here —
  dev loop is save -> push -> rebuild (~5 min).
============================================================================= */
public class AdBlockClient extends WebViewClient {

    /** Observer: MainActivity implements this to refresh the notification. */
    public interface Listener {
        void onPageChanged(String url, String title);
    }

    private Listener listener;

    public void setListener(Listener l) {
        this.listener = l;
    }

    // JAVA: private static final Set<String> BLOCKED = new HashSet<>(...);
    // Host-suffix rules: "doubleclick.net" also kills
    // "googleads.g.doubleclick.net" via endswith check in isAd().
    private static final Set<String> BLOCKED_HOSTS = new HashSet<>(Arrays.asList(
            // Google ad empire
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "google-analytics.com",
            "googletagmanager.com",
            "googletagservices.com",
            "googleadsyndication.com",
            "adservice.google.com",
            // YouTube's own ad/metrics endpoints (page itself stays allowed)
            "ads.youtube.com",
            // VAST / video-ad providers
            "moatads.com",
            "moat.com",
            "innovid.com",
            "freewheel.tv",
            "springserve.com",
            "spotxchange.com",
            "spotx.tv",
            "ads.yahoo.com",
            "advertising.com",
            "pubmatic.com",
            "rubiconproject.com",
            "criteo.com",
            "outbrain.com",
            "taboola.com"
    ));

    // Path/substring rules: catch endpoints like /api/stats/ads, pagead, VAST...
    private static final String[] BLOCKED_SUBSTRINGS = {
            "/api/stats/ads",
            "pagead",
            "/ads/",
            "doubleclick",
            "googlesyndication",
            "googleadservices",
            "googletagservices",
            "moatads",
            ".innovid.",
            "freewheel",
            "springserve",
            "vast",
            "vmap",
            "/ad_break",
            "adservice"
    };

    private static final byte[] EMPTY = new byte[0];

    private boolean isAd(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        boolean hit = false;
        // 1) host suffix match.
        try {
            String host = Uri.parse(lower).getHost();
            if (host != null) {
                for (String blocked : BLOCKED_HOSTS) {
                    if (host.equals(blocked) || host.endsWith("." + blocked)) {
                        hit = true;
                        break;
                    }
                }
            }
        } catch (Exception ignored) { /* malformed URL -> fall through */ }
        // 2) substring match for path-based ad endpoints.
        if (!hit) {
            for (String sub : BLOCKED_SUBSTRINGS) {
                if (lower.contains(sub)) { hit = true; break; }
            }
        }
        // Logcat trail: `adb logcat | grep ADBLOCK` shows every kill.
        // When an ad slips through, its host is the one NOT in this log.
        if (hit) android.util.Log.d("ADBLOCK", "blocked: " + url);
        return hit;
    }

    // New API (Android 5+): the firewall gate. Empty response = ad dies here.
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (isAd(request.getUrl().toString())) {
            // Like `response.setContentLength(0); return;` in a Filter.
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(EMPTY));
        }
        return super.shouldInterceptRequest(view, request);
    }

    // Old API (safety net for ancient WebViews): same check, String form.
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        if (isAd(url)) {
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(EMPTY));
        }
        return super.shouldInterceptRequest(view, url);
    }

    // DOM layer (belt + suspenders): after each page loads, inject CSS hiding +
    // skip-button clicking + title/state push, so overlay ads served from
    // youtube.com itself (which we must NOT block) still die, AND the
    // notification learns the video title without any API key.
    // THEN notify MainActivity (url + title) for the notification update.
    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        String js = "(function(){"
                // 1) hide known ad containers (same selectors as your web app)
                + "var css='.ytp-ad-module,.video-ads,.ytp-ad-overlay-container,"
                + ".ytp-ad-text-overlay,#player-ads{display:none!important}';"
                + "var st=document.createElement('style');"
                + "st.textContent=css;document.head.appendChild(st);"
                // 2) auto-click Skip buttons every second (like a watchdog)
                + "if(!window._ytbSkip){window._ytbSkip=setInterval(function(){"
                + "var b=document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button');"
                + "if(b){b.click();}"
                // 3) push title + playing state to Java (fills notification!)
                + "try{"
                + "var v=document.querySelector('video');"
                + "if(window.YtbNative){window.YtbNative.pushState(document.title,!!v&&!v.paused);}"
                + "}catch(e){}"
                + "},1000);}"
                // 4) immediate first push (no 1s wait)
                + "try{"
                + "var v0=document.querySelector('video');"
                + "if(window.YtbNative){window.YtbNative.pushState(document.title,!!v0&&!v0.paused);}"
                + "}catch(e){}"
                + "})();";
        view.evaluateJavascript(js, null); // like invoking a script engine
        if (listener != null) {
            try {
                listener.onPageChanged(url, view.getTitle());
            } catch (Exception ignored) { }
        }
    }

    // YouTube is a SPA: address bar changes WITHOUT page reloads. Without
    // this, going Home -> Video would never refresh the notification.
    // JAVA: like listening to router events, not just full page loads.
    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        if (listener != null) {
            try {
                listener.onPageChanged(url, view.getTitle());
            } catch (Exception ignored) { }
        }
    }
}
