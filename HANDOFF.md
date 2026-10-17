# HANDOFF.md — YTB Web: Full Project Handoff for a New AI Agent

> **Read this entire file before touching anything.** It contains the product
> requirements, architecture, hard-won lessons (each one a bug we paid for),
> the current live state, and the next steps. The user is a **Java developer
> who does NOT know JavaScript/React** — every code comment in this repo
> draws Java analogies, and you must keep doing the same when writing code
> and explanations. Plain English + Java comparisons. No frameworks.

---

## 1. PRODUCT GOAL (the original ask)

Build a personal **YouTube alternative for mobile** with:

1. **Background playback** when the phone screen is locked (the #1 feature).
2. **Ad blocking** (network-level where possible).
3. Real YouTube UX: browse, search, suggestions — NOT "copy-paste a URL to
   play one video" (user explicitly rejected that as impractical).

### Hard rules from the user (never violate)
- **Monolith.** No modules, no microservices, no multi-project Gradle, no
  state management libraries. One app module. Simple.
- **No JS/React knowledge assumed.** All JS (if any) must be heavily
  commented with Java analogies (localStorage = .properties file, app state
  = object fields, DOM observer = WatchService, etc.).
- **No external CSS frameworks** (no Tailwind etc.). Single plain CSS file
  if a web UI exists.
- **No heavy setup.** User is on macOS, has NO Android Studio, and refuses
  heavy installs. Builds happen in **GitHub Actions cloud**. Keep it that way.
- **No Firebase / telemetry phone-home / tracking** (a reference repo the
  user found, github.com/92ganesh/YTbackground, was rejected partly for
  this). Our own diagnostics go to the USER'S OWN Mac server only.

---

## 2. HOW THE APP WORKS TODAY (architecture, current = v4)

The app is a **native Android shell around real YouTube** in a WebView.

### Why native instead of the original web app?
The user originally wanted a pure web app (index.html/styles.css/app.js,
history share/login all built). Then they discovered the **YTbackground**
Android app (github.com/92ganesh/YTbackground) that DOES lock-screen
playback. Investigation of its source (MainActivity.java's `MyWebView`,
ForegroundService.java) showed it uses two **native-only privileges**:

1. `WebView.onWindowVisibilityChanged()` override that **swallows the
   GONE/INVISIBLE signal** so YouTube's page never learns it was hidden →
   Google's self-pause never fires.
2. `Service.startForeground(notification)` → Android's task killer spares
   the app; Spotify-style persistent notification.

A Chrome tab can do **neither** (cross-origin iframe, tab suspension by
design). Hence: native shell. We rebuilt YTbackground's trick **cleaner**
(no Firebase) and **stronger** (added the ad-blocker YTbackground lacks).

**Every web-only workaround was tried and VERIFIED DEAD (Oct 2026):**
- MediaSession-only on a YouTube iframe → Google self-pauses on lock.
- Tiny 2nd YT player (4x4px) + watchdog re-play → dies same way.
- Piped/Invidious raw-audio streaming → ALL public mirrors blocked by
  YouTube (HTTP 500 SignInConfirmNotBot / 502 / timeout).
- yt-dlp from user's Mac → YouTube bot wall (SABR + PO tokens required).
Do NOT re-attempt these; they waste cycles. Native WebView is the answer.

### Runtime flow (Java analogies included)

```
MainActivity (the JFrame / controller)
 |- KeepAliveWebView   (swallows hide signals = no-op setVisible(false))
 |- AdBlockClient      (servlet-Filter firewall + injected JS watchdog)
 |- NowPlaying         (model; JS bridge @JavascriptInterface)
 |- PlaybackService    (foreground Service: rich notification)
 |- ActionReceiver     (notification Pause/Play + Next buttons)
 |- FullscreenHandler  (onShowCustomView/onHideCustomView, landscape)
 '- Telemetry          (queue -> POST JSON batches to Mac :8081)
```

- **Start page**: `https://m.youtube.com`. User browses/searches/plays
  normally; login works (DomStorage enabled).
- **Fullscreen**: overlay card + landscape lock; Back exits fullscreen first,
  then walks WebView history.
- **Share target**: manifest registers ACTION_SEND; Share->YTB Web opens the
  video (or searches) in-app.
- **Notification**: real title (JS bridge pushes document.title), URL
  subtitle, thumbnail bitmap (async from i.ytimg.com), Pause/Play button
  that clicks the page's biggest <video> via evaluateJavascript.
- **Ad-block (3 layers)**:
  1. Network firewall (`shouldInterceptRequest`): ~23 ad hosts + path
     substrings (pagead, vast, vmap, /ads/). Empty response = ad dies.
  2. Injected CSS hides ad containers (must be head-null-safe:
     m.youtube.com first load is a REDIRECT STUB with no document.head —
     an early crash here killed skip-clicking AND title push; baee847).
  3. Injected JS watchdog (1s, per-page guard `window._ytbSkip`): clicks
     any Skip variant; mutes+seeks past unskippable ads (ad badge +
     duration <240s); "stall-kick" nudges a player stuck at time 0 >4s
     (black-screen-ad fix, f2b4cfb).

### Telemetry (the debugging workhorse)
- `Telemetry.java`: bounded queue (500, drop-newest), ONE daemon thread,
  batched POSTs (<=50 events, 2s flush) to `http://<MAC_IP>:8081/log`.
  1.5s timeouts + catch-all: server down = events dropped, never crashes.
- `telemetry_server.py` (stdlib only): prints live + appends
  `telemetry.log` (JSON-lines). Run: `python3 telemetry_server.py`.
- Event types: `adblock` (firewall kill), `js` (bridge pushes + sentinels
  `__skip_clicked`, `__ad_seek`, `__ad_visible`, `__stall_kick`), `nav`
  (page changes, fullscreen, app-start ping, test ping), `notif` (every
  notification post with title/subtitle/hasArt), `error`.
- In-app menu: **YTB Diagnostics** (diag.html from Mac server), **Send test
  ping** (verifies pipeline in ~2s).
- **Log hygiene (user requirement)**: telemetry.log rotates on every APK
  build (CI moves it to `telemetry-history/`, git-ignored). Confirm you are
  reading CURRENT-APK lines (look for `nav app started`).

---

## 3. PROJECT FILES (repo: https://github.com/92ganesh/YTB-web)

Local path: `~/Documents/Projects/YTB Web`. Key files:

- `HANDOFF.md` — this file (authoritative doc; `YtbNative/README.md` is OLDER, trust this).
- `index.html`, `styles.css`, `app.js` — the ORIGINAL web app. Now LEGACY: only used if `USE_DEV_PAGE=true` or after the FINAL bundle flip.
- `diag.html` — in-app diagnostics page (probes live YouTube DOM, PASS/FAIL per subsystem), served from the Mac during dev.
- `telemetry_server.py` — log collector (port 8081, stdlib only).
- `.github/workflows/build-apk.yml` — the ENTIRE build system (see §5).
- `YtbNative/` — the Android app, single Gradle module:
  - `build.gradle` / `settings.gradle`: AGP 8.5.2; `pluginManagement` + `dependencyResolutionManagement` with `google()` (REQUIRED — AGP not on plugin portal).
  - `gradle/wrapper/gradle-wrapper.properties`: Gradle 8.7; wrapper jar NOT committed (CI generates it).
  - `app/build.gradle`: minSdk 26, targetSdk 34, **ZERO external dependencies**.
  - `app/src/main/AndroidManifest.xml`: INTERNET + FGS permissions; MainActivity with SEND intent-filter + `configChanges` incl. `screenLayout`; PlaybackService (mediaPlayback type); ActionReceiver.
  - `app/src/main/res/layout/activity_main.xml`: 2-card FrameLayout — `webview` (KeepAliveWebView) + `fullscreenContainer` (black overlay, gone by default).
  - `app/src/main/java/com/ytbweb/`: MainActivity, KeepAliveWebView (background hack; MUST stay top-level — layout inflates by class name), AdBlockClient (firewall + injected JS), NowPlaying (JS bridge model), PlaybackService (rich notification), ActionReceiver (Pause/Play + Next), FullscreenHandler, Telemetry.

### Dev/final switch (IMPORTANT)
`MainActivity`: `HOME_URL = "https://m.youtube.com"`, `DEV_URL = "http://<MAC_IP>:8000"`, `USE_DEV_PAGE = false`. Diag menu loads from DEV_URL.
**FINAL bundle mode** (when user says "ship it"): copy the 4 HTML/JS/CSS files to `app/src/main/assets/www/`, flip START to `file:///android_asset/www/index.html`, rebuild.

**WARNING — Mac IP is DHCP-rolled**: was `.35`, now **192.168.1.34**. If telemetry or diag goes silent: `ipconfig getifaddr en0`, update BOTH `Telemetry.ENDPOINT` and `MainActivity.DEV_URL`, rebuild. This burned a whole debugging session (commit 782aec1).

---

## 4. CURRENT STATE (as of commit 5825f6f, main branch)

**Working & Verified Architecture:**
- Real YouTube in-app: browse, search, login, suggestions, history.
- Background playback on screen lock.
- Network ad-blocker kills DoubleClick/pagead/tracking.
- Telemetry pipeline phone→Mac via `telemetry_server.py`.
- Share-to-app, Home/Desktop-site menu, Diagnostics page, test ping.
- Fullscreen video (landscape + immersive overlay).

**Comprehensive Root-Cause Fixes in Commit 5825f6f (Tailored for Modern Android / Pixel):**
1. **Notification Missing on Android 13+ (Pixel / API 33+)**:
   - Added `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />` in `AndroidManifest.xml`.
   - Added explicit runtime permission request in `MainActivity.onCreate()` on API 33+.
   - Added `VISIBILITY_PUBLIC` on notification so lock-screen hides nothing.
   - Fixed null `Icon` crash on `Notification.Action.Builder`.
   - Added explicit `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` to `startForeground()` call for Android 14+.
2. **Black-Screen Ad Stall Eliminated**:
   - Removed the 4-second delay hack.
   - Added an allowlist in `AdBlockClient.isAd` (`/videoplayback`, `m.youtube.com/watch`, `i.ytimg.com`) so video streams/chunks are never blocked.
   - Increased watchdog frequency from 1000ms to 150ms.
   - When an ad is detected (`.ad-showing`, `.ytp-ad-player-overlay`), immediately mute + set `playbackRate = 16.0` + seek to end + trigger skip buttons. Ads finish in <100ms.
   - Recover any paused video element immediately if stalled.
3. **Background Service Survives Activity Destruction**:
   - Removed `stopService(PlaybackService)` from `MainActivity.onDestroy()`. Android cleans up background activities to save RAM; the foreground service now remains running uninterrupted.
4. **Real "Next" Button in Notification**:
   - Replaced dummy `webView.goForward()` with YouTube's player API `document.getElementById('movie_player').nextVideo()` and `.ytp-next-button` selector.
5. **Autoplay Art & Video ID Sync**:
   - `pollOnce()` now passes `curUrl` to `nowPlaying.onUrl(curUrl)` whenever a change is detected, keeping thumbnail art and video ID updated on autoplay.
6. **Fullscreen Media Suspension Prevented**:
   - Stopped calling `browserView().setVisibility(View.GONE)` in `FullscreenHandler.java`. The black overlay covers the browser without triggering Chromium view detachment.

---

## 5. BUILD & DEV WORKFLOW (no Android Studio, cloud builds)

- Every `git push` triggers GitHub Actions → "Build debug APK" (~5–8 min cold, ~3–4 min warm). Artifact `YTB-Web-debug-apk` → `app-debug.apk` (debug = auto-signed, directly installable).
- **Workflow quirks you MUST NOT regress** (each cost a failed build):
  - `android-actions/setup-android@v3` is BROKEN (calls removed 'tools' package) → workflow installs cmdline-tools manually (URL 11076708) + platform-tools, android-34, build-tools 34.0.0.
  - Ubuntu apt gradle is v4 (too old for dependencyResolutionManagement) → workflow downloads real Gradle 8.7 from the **github release URL directly** (`services.gradle.org` 307-redirects; curl WITHOUT -L saves a 169-byte HTML stub → "not a zipfile". Use `curl -fSL --retry 3`).
  - `gradle-wrapper.jar` NOT committed; CI generates via the downloaded gradle (`gradle wrapper --gradle-version 8.7 -p "$GITHUB_WORKSPACE/YtbNative"`).
  - `settings.gradle` must keep `google()` in both pluginManagement and dependencyResolutionManagement (fdc79b2).
  - YAML step names must be single-line (540f37c).
  - CI also rotates telemetry.log into `telemetry-history/` per build (0764384).
- Local commands (from repo root):
  - `python3 -m http.server 8000` — web file server (diag.html in dev).
  - `python3 telemetry_server.py` — log collector on :8081.
  - `ipconfig getifaddr en0` — check Mac IP if telemetry silent.
- Install: download artifact → allow unknown apps → install. For notification-channel changes: UNINSTALL first, then install fresh.

---

## 6. LESSONS PAID (each = hours; don't repeat)

1. `m.youtube.com` first paint is a redirect stub with **no document.head** → injected JS crashed before ANY watchdog/CSS ran (killed skip+title push silently). Always head-null-guard injected JS.
2. YouTube is a SPA: address changes without reload → notification must listen to `doUpdateVisitedHistory`, not just `onPageFinished`.
3. `display:none` on a WebView/iframes suspends media — fullscreen overlay uses visibility swaps, never display:none for the playing view.
4. Notification **channel importance is set once per install** — changing it in code does nothing for existing installs. Always uninstall+reinstall when testing channel fixes.
5. Hardcoded LAN IPs rot with DHCP. Symptom: silent telemetry, zero logs, "everything broken" that is actually just blind.
6. YouTube titles are `"<Name> - YouTube"` — strip the suffix.
7. `evaluateJavascript` returns JSON-quoted strings; strip quotes before parsing.
8. `PendingIntent` on Android 12+ REQUIRES FLAG_IMMUTABLE.
9. JS bridge (@JavascriptInterface) callbacks run on a background thread — fields volatile; UI/service calls need post/handler.
10. Adblock over-blocking can STALL the player (black screen) — blocking ad assets ≠ player skipping the ad slot. Mitigate with stall-kick, not by unblocking ad hosts.
11. Never commit telemetry.log (rewrites every second while server runs); *.log + telemetry-history/ are git-ignored.
12. User rejected web-only background play after testing; don't propose web-only lock-screen-audio solutions again (see §2 dead list).

---

## 7. NEXT STEPS (priority order)

1. **USER TASK (do first)**: fresh-install build ≥ f2b4cfb (uninstall old), restart telemetry server, play videos, report:
   - notification banner appears? (channel fix validation)
   - black-screen still? (read `__ad_visible` / `__stall_kick` sightings in telemetry.log to decide: selector tuning vs stall-kick tuning)
2. If notification still invisible with `notif posted` in logs: likely OEM battery/autostart restriction (Xiaomi/MIUI, Samsung, OnePlus kill or hide FGS notifications) — walk user through settings; consider real MediaSession (proper token + setSessionToken) as the robust v5.
3. Next-track button is naive (history-forward). Real fix = parse YouTube's "up next"/queue via JS and loadVideoById.
4. When user says **"ship it"**: do the FINAL bundle flip (§3 switch), copy web files to assets/www, rebuild, confirm standalone works with Mac off.
5. Optional polish: PiP, notification seek bar via MediaSession, per-video ad-sighting reports.

## 8. COMMUNICATION STYLE (user preference, observed)

- User is direct, impatient with fluff, calls out mistakes immediately ("nope not working", "you said X but..."). Respond with: **root cause first, then fix, then test steps**. No apologies beyond one line. Show the log/code line that proves the diagnosis.
- They test everything themselves and report tersely — use the telemetry to verify their claims instead of asking them to re-explain.
- When pushing commits: always state **commit hash + what changed + exact reinstall instructions** (they rebuild via CI and install manually).
- They appreciate verifying claims with live commands (curl the API, lsof the port, ipconfig) before proposing fixes.
- Their instinct is good — when they propose a theory ("probably the ad was playing but not visible"), test it seriously; their theories have been right multiple times.

— end of handoff.


