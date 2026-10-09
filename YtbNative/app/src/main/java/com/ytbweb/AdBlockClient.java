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
  AdBlockClient.java - NETWORK FIREWALL + DOM BACKUP (the APK's killer feature)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: a Servlet Filter intercepting every request before it reaches
  the network. shouldInterceptRequest() fires for EVERY sub-resource (scripts,
  iframes, tracking pixels). Return an EMPTY response for ad hosts and the ad
  never downloads, never renders, never counts — strictly stronger than the
  CSS hiding in your web version (which hides ads AFTER they load).
  The repo you found has NONE of this — plain WebView, all ads load.
============================================================================= */
public class AdBlockClient extends WebViewClient {

    // JAVA: private static final Set<String> BLOCKED = new HashSet<>(...);
    // Host-suffix rules: "doubleclick.net" also kills
    // "googleads.g.doubleclick.net" via endswith check in isAd().
    private static final Set<String> BLOCKED_HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "google-analytics.com",
            "ads.youtube.com",
            "moatads.com",
            "innovid.com",
            "ads.yahoo.com"
    ));

    // Path/substring rules: catch endpoints like /api/stats/ads, pagead, ...
    private static final String[] BLOCKED_SUBSTRINGS = {
            "/api/stats/ads",
            "pagead",
            "/ads/",
            "doubleclick",
            "googlesyndication",
            "googleadservices",
            "moatads",
            ".innovid."
    };

    private static final byte[] EMPTY = new byte[0];

    private boolean isAd(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        // 1) host suffix match.
        try {
            String host = Uri.parse(lower).getHost();
            if (host != null) {
                for (String blocked : BLOCKED_HOSTS) {
                    if (host.equals(blocked) || host.endsWith("." + blocked)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) { /* malformed URL -> fall through */ }
        // 2) substring match for path-based ad endpoints.
        for (String sub : BLOCKED_SUBSTRINGS) {
            if (lower.contains(sub)) return true;
        }
        return false;
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

    // DOM layer (belt + suspenders): after each page loads, inject the same
    // CSS/JS hiding your web app.js uses, so overlay ads served from
    // youtube.com itself (which we must NOT block) still get hidden/skipped.
    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        String js = "(function(){"
                + "var css='.ytp-ad-module,.video-ads,.ytp-ad-overlay-container,"
                + "#player-ads{display:none!important}';"
                + "var st=document.createElement('style');"
                + "st.textContent=css;document.head.appendChild(st);"
                + "setInterval(function(){"
                + "var b=document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button');"
                + "if(b){b.click();}"
                + "},1000);"
                + "})();";
        view.evaluateJavascript(js, null); // like invoking a script engine
    }
}
