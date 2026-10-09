/* ============================================================================
  app.js - THE CONTROLLER (all app logic in one file, monolithic)
  ----------------------------------------------------------------------------
  JAVA ANALOGY FOR THE WHOLE FILE:
  Imagine ONE Java class:
      public class App {
          private String currentVideoId;      // <-- "app state" = object fields
          private List<Video> history;        // <-- in-memory cache
          private YT.Player player;           // <-- video player object
          public static void main(...) { ... }// <-- init() at the bottom
      }
  - "App state" below = private fields holding what screen shows right now.
  - localStorage = a permanent `app.properties` file the browser keeps for
    you, so history SURVIVES page reloads (like writing to disk).
  - Every function is commented with its Java equivalent. Read top to bottom.

  FEATURE MAP (your 5 requirements):
    1. YouTube Iframe Player  -> createPlayer() + onYouTubeIframeAPIReady()
    2. Background playback    -> setupMediaSession() + visibility handling
    3. Basic Ad-Blocker       -> startAdBlocker() [CSS in styles.css + JS here]
    4. Video History          -> loadHistory() / saveHistory() / renderHistory()
    5. Share Feature          -> shareCurrentVideo() via navigator.share
    6. Minimal Login (mock)   -> mockLogin() / mockLogout() + Google OAuth hook
============================================================================= */

'use strict';

/* ----------------------------------------------------------------------------
  SECTION 0: APP STATE (like `private` fields in a Java class)
---------------------------------------------------------------------------- */
// Current YouTube video ID being played. null = nothing loaded yet.
let currentVideoId = null;

// In-memory history list. Each item: { id, url, time }.
// JAVA: ArrayList<Video> history = new ArrayList<>();
let history = [];

// The YouTube player object (created once the YouTube API script loads).
// JAVA: private YT.Player player; // initially null, like uninitialized field
let player = null;

// Is the player ready? (avoids NullPointerException-style bugs)
let isPlayerReady = false;

// localStorage KEYS - like constants for keys in a .properties file.
const HISTORY_KEY = 'ytb_history_v1'; // where we store the history array
const USER_KEY = 'ytb_user_v1';       // where we store the mock logged-in user

/* ----------------------------------------------------------------------------
  SECTION 1: SMALL HELPERS (like private utility methods)
---------------------------------------------------------------------------- */

// Show a short message at the bottom (like System.out.println for the UI).
function setStatus(msg) {
  document.getElementById('statusMsg').textContent = msg;
}

// Extract video ID from raw ID or any YouTube URL.
// JAVA: public static String parseVideoId(String input) { ... }
// Accepts: "aqz-KE-bpKQ", "https://youtu.be/aqz-KE-bpKQ",
//          "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
function parseVideoId(input) {
  input = (input || '').trim();
  if (!input) return null;
  // Raw 11-char ID (letters, numbers, - and _) - return as-is.
  if (/^[a-zA-Z0-9_-]{11}$/.test(input)) return input;
  // Try ?v=VIDEOID pattern.
  let m = input.match(/[?&]v=([a-zA-Z0-9_-]{11})/);
  if (m) return m[1];
  // Try youtu.be/VIDEOID or /embed/VIDEOID or /shorts/VIDEOID pattern.
  m = input.match(/(?:youtu\.be\/|embed\/|shorts\/)([a-zA-Z0-9_-]{11})/);
  if (m) return m[1];
  return null; // Could not understand input.
}
/* ----------------------------------------------------------------------------
  SECTION 2: VIDEO HISTORY (localStorage = permanent .properties file)
  ----------------------------------------------------------------------------
  JAVA MENTAL MODEL:
    Properties props = new Properties();
    props.load(new FileInputStream("app.properties"));   // loadHistory()
    props.store(new FileOutputStream(...));              // saveHistory()
  Browser version:
    localStorage.getItem(KEY)  == reading the file
    localStorage.setItem(KEY)  == writing the file (value must be a String,
                                   so we use JSON.stringify = serialization)
---------------------------------------------------------------------------- */

// Load history from disk (localStorage) into memory. Call once at startup.
function loadHistory() {
  try {
    const raw = localStorage.getItem(HISTORY_KEY); // like props.getProperty()
    history = raw ? JSON.parse(raw) : [];          // deserialize JSON -> Array
    if (!Array.isArray(history)) history = [];     // safety: corrupted file
  } catch (e) {
    history = []; // If file corrupt, start fresh (like catching IOException).
  }
}

// Save memory -> disk. Call every time history changes.
function saveHistory() {
  try {
    // JSON.stringify = Java Serialization (object -> string for storage).
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
  } catch (e) {
    console.warn('Could not save history', e);
  }
}

// Add a video to history (newest first, max 50, no back-to-back duplicates).
// JAVA: void addToHistory(Video v) { history.add(0, v); ... }
function addToHistory(videoId) {
  const entry = {
    id: videoId,
    url: 'https://www.youtube.com/watch?v=' + videoId,
    time: Date.now(),
  };
  // Avoid pushing the same video twice in a row (like checking list.get(0)).
  if (history.length > 0 && history[0].id === videoId) return;
  history.unshift(entry);                   // add to FRONT (newest first)
  if (history.length > 50) history.length = 50; // cap size: bounded queue
  saveHistory();                            // persist to "disk"
  renderHistory();                          // refresh the <ul> on screen
}

// Draw the history array into <ul id="historyList">.
// JAVA: like refreshing a JList after listModel.addElement(...).
function renderHistory() {
  const ul = document.getElementById('historyList');
  ul.innerHTML = ''; // clear old rows
  if (history.length === 0) {
    ul.innerHTML = '<li>No history yet - play a video!</li>';
    return;
  }
  history.forEach((item) => {
    const li = document.createElement('li');
    const label = document.createElement('span');
    label.textContent = item.id + ' | ' + new Date(item.time).toLocaleString();
    label.title = item.url; // hover shows full URL (like setToolTipText)

    const replayBtn = document.createElement('button');
    replayBtn.textContent = 'Replay';
    // Closure captures item.id - like an anonymous ActionListener in Java.
    replayBtn.onclick = () => loadVideo(item.id);

    li.appendChild(label);
    li.appendChild(replayBtn);
    ul.appendChild(li);
  });
}

/* ----------------------------------------------------------------------------
  SECTION 3: YOUTUBE IFRAME PLAYER
  ----------------------------------------------------------------------------
  The <script src="https://www.youtube.com/iframe_api"> in index.html loads
  Google's player code. When done it calls the GLOBAL function
  onYouTubeIframeAPIReady() (required name - like an interface callback
  onReady() you must implement). Inside, we do new YT.Player(...).
---------------------------------------------------------------------------- */

// REQUIRED global callback - YouTube API calls this when ready.
// JAVA: @Override public void onReady() { player = new Player(...); }
function onYouTubeIframeAPIReady() {
  // Default demo video so the app is not empty on first open.
  const defaultId = 'aqz-KE-bpKQ'; // Big Buck Bunny (common test video)
  createPlayer(defaultId);
}

// Build the YT.Player inside the <div id="player"> placeholder.
function createPlayer(videoId) {
  // new YT.Player(elementId, {options}) is like new JFrame(config).
  player = new YT.Player('player', {
    height: '100%',
    width: '100%',
    videoId: videoId,
    // playerVars = constructor flags, like a Config object passed in.
    playerVars: {
      autoplay: 0,      // wait for user to press Play (mobile policy)
      playsinline: 1,   // CRITICAL for iOS: play inside page, not fullscreen
      rel: 0,           // fewer related videos at the end
      modestbranding: 1,// minimal YouTube logo
      origin: window.location.origin, // security: lock player to our domain
    },
    events: {
      onReady: onPlayerReady,             // callback when player is usable
      onStateChange: onPlayerStateChange, // callback on play/pause/ended
    },
  });
  currentVideoId = videoId;
}

// Called once the player object is usable (like constructor finished).
function onPlayerReady() {
  isPlayerReady = true;
  setStatus('Player ready. Press Play.');
  setupMediaSession(); // lock-screen controls (Section 4)
  startAdBlocker();    // hide/skip ads (Section 5)
  renderHistory();
  updateNowPlaying();
}

// Called on every play / pause / end event.
function onPlayerStateChange(event) {
  // YT.PlayerState.PLAYING == 1 - like an enum constant PLAYING.
  if (event.data === YT.PlayerState.PLAYING) {
    document.getElementById('playPauseBtn').textContent = 'Pause';
    if (currentVideoId) addToHistory(currentVideoId);
    setupMediaSession(); // refresh lock-screen title for new video
    updateNowPlaying();
  } else if (event.data === YT.PlayerState.PAUSED) {
    document.getElementById('playPauseBtn').textContent = 'Play';
  } else if (event.data === YT.PlayerState.ENDED) {
    document.getElementById('playPauseBtn').textContent = 'Replay';
  }
}

// Load (or cue) a new video ID in the existing player.
// JAVA: void loadVideo(String id) { player.load(id); currentVideo = id; }
function loadVideo(videoId) {
  if (!videoId) {
    setStatus('Could not understand that URL/ID.');
    return;
  }
  currentVideoId = videoId;
  if (player && isPlayerReady) {
    player.loadVideoById(videoId); // reuse same player (no page reload)
  } else {
    createPlayer(videoId); // player not ready yet - create with this video
  }
  addToHistory(videoId);
  setupMediaSession();
  updateNowPlaying();
  setStatus('Loading: ' + videoId);
}

function updateNowPlaying() {
  const el = document.getElementById('nowPlaying');
  el.textContent = currentVideoId
    ? 'Now playing: https://youtu.be/' + currentVideoId
    : 'No video loaded yet.';
}

/* ----------------------------------------------------------------------------
  SECTION 4: LOCK-SCREEN REALITY + WHAT WE DO INSTEAD
  ----------------------------------------------------------------------------
  STRAIGHT TRUTH (verified Oct 2026 by testing every workaround):
  - YouTube's iframe self-pauses on screen lock (cross-origin, no override).
    A 2nd tiny YouTube player dies the same way - tested, removed.
  - Raw audio URLs (Piped/Invidious/yt-dlp) are blocked by YouTube bot
    checks (SABR + PO tokens). Public mirrors return HTTP 500/502.
  - TRUE locked-screen audio from youtube.com in a plain page is currently
    NOT achievable. Anyone promising it is selling you something.
  WHAT ACTUALLY HELPS (below):
  (a) Media Session -> lock-screen title/art/controls while OS allows audio
  (b) Keep-awake toggle (Wake Lock) -> screen stays on, never pauses. Best
      for desk listening. One tap, no server, no API. (Section 4B)
  (c) Floating window button (Picture-in-Picture) for app-switching.
---------------------------------------------------------------------------- */

function setupMediaSession() {
  // Guard: older browsers lack navigator.mediaSession (like instanceof check).
  if (!('mediaSession' in navigator) || !currentVideoId) return;
  try {
    // Tells the lock screen what is playing (like setting JFrame title).
    navigator.mediaSession.metadata = new MediaMetadata({
      title: 'YTB Web | ' + currentVideoId,
      artist: 'YouTube via YTB Web',
      album: 'Personal Player',
      artwork: [
        // YouTube thumbnail - used as lock-screen album art. Free win.
        { src: 'https://i.ytimg.com/vi/' + currentVideoId + '/hqdefault.jpg', sizes: '480x360', type: 'image/jpeg' },
      ],
    });
    // Wire lock-screen / headphone buttons to our player (like key bindings).
    navigator.mediaSession.setActionHandler('play', () => player && player.playVideo());
    navigator.mediaSession.setActionHandler('pause', () => player && player.pauseVideo());
    navigator.mediaSession.setActionHandler('previoustrack', () => playFromHistory(1));
    navigator.mediaSession.setActionHandler('nexttrack', () => playFromHistory(-1));
  } catch (e) {
    console.warn('MediaSession not supported', e);
  }
}

// Helper for lock-screen next/previous: walk the history array.
function playFromHistory(offset) {
  // offset +1 = older video; -1 has no "newer" so just replay current.
  if (offset > 0 && history.length > 1) loadVideo(history[1].id);
}

// Do NOT pause when tab hidden. The YT iframe obeys Google's own rules;
// our job is simply not to add an extra pause on top (common tutorial bug).
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    console.log('[YTB] Tab hidden - leaving player alone.');
    setupMediaSession(); // refresh metadata so OS keeps session alive
    // Re-acquire wake lock if it was on (locking drops it on some browsers).
    if (wakeLockOn) requestWakeLock(true);
  } else if (wakeLockOn) {
    requestWakeLock(true); // visible again -> make sure lock is held
  }
});
/* ----------------------------------------------------------------------------
  SECTION 4B: KEEP-AWAKE (Wake Lock) + FLOATING WINDOW (PiP)
  ----------------------------------------------------------------------------
  WAKE LOCK: navigator.wakeLock.request('screen') asks the OS "do not turn
  the screen off while this page is visible". Listening on a desk? Turn it
  on and playback never pauses (screen stays on, OS may dim it).
  JAVA: like acquiring a Lock - must release() when done, and the OS can
  revoke it anytime (we listen for its 'release' event and update the UI).
---------------------------------------------------------------------------- */

let wakeLock = null;    // the held lock object (null = not held)
let wakeLockOn = false; // what the TOGGLE wants (persists across re-locks)

async function requestWakeLock(silent) {
  // Guard: older browsers (iOS Safari < 16.4) lack wakeLock entirely.
  if (!('wakeLock' in navigator)) {
    if (!silent) setStatus('Keep-awake not supported here. Try Chrome Android.');
    updateWakeUI();
    return;
  }
  try {
    wakeLock = await navigator.wakeLock.request('screen');
    wakeLockOn = true;
    // OS can release anytime (battery saver, call). Reflect it in UI.
    wakeLock.addEventListener('release', () => {
      console.log('[YTB] wake lock released by OS');
      wakeLock = null;
      updateWakeUI(); // wakeLockOn stays = user's wish; handler re-locks
    });
    updateWakeUI();
    if (!silent) setStatus('Keep-awake ON - screen stays on while here.');
  } catch (e) {
    // NotAllowedError = battery saver / background tab / no gesture.
    if (!silent) setStatus('Keep-awake blocked (' + (e.name || 'error') + '). Stay on tab, retry.');
    updateWakeUI();
  }
}

async function releaseWakeLock() {
  wakeLockOn = false;
  try { if (wakeLock) await wakeLock.release(); } catch (e) {}
  wakeLock = null;
  updateWakeUI();
  setStatus('Keep-awake OFF.');
}

function updateWakeUI() {
  const btn = document.getElementById('wakeBtn');
  if (!btn) return;
  const held = !!wakeLock;
  btn.textContent = held ? 'Keep-awake: ON' : 'Keep-awake: OFF';
  document.getElementById('bgModeLabel').textContent = held
    ? 'Keep-awake ON: screen stays on, playback never pauses here.'
    : 'Video mode. Tip: Keep-awake ON stops auto-pause while screen is up.';
}

async function enterPictureInPicture() {
  // The <video> lives INSIDE YouTube's cross-origin iframe, so we cannot
  // grab it directly. Try the iframe element (works only if YT permits).
  try {
    const iframe = document.querySelector('#player iframe');
    if (!iframe) { setStatus('Player not ready yet.'); return; }
    if (typeof iframe.requestPictureInPicture === 'function') {
      await iframe.requestPictureInPicture();
      setStatus('Floating window ON.');
    } else {
      setStatus('Floating window blocked here. Use Keep-awake instead.');
    }
  } catch (e) {
    setStatus('Floating window blocked (' + (e.name || 'error') + '). Use Keep-awake.');
  }
}


/* ----------------------------------------------------------------------------
  SECTION 5: BASIC AD-BLOCKER (two layers)
  ----------------------------------------------------------------------------
  LAYER A (CSS, in styles.css): hides ad containers by class name:
      .ytp-ad-module, .video-ads, .ytp-ad-overlay-container ...
    Like firewall DENY rules on packet headers.
  LAYER B (JS, here): watches the player DOM for ad nodes and SKIPS them:
    - MutationObserver = like a Java WatchService on a directory: fires when
      child nodes change inside the player element.
    - If an ad appears, we click Skip or jump to the end via seekTo(duration).
  NOTE: YouTube serves ads INSIDE the iframe (cross-origin), so we cannot
  delete nodes inside the iframe directly. What we CAN do: (1) hide overlay
  ads on OUR page, (2) auto-click skip when detectable, (3) mute + seek past
  short ad breaks when the API exposes them. This blocks most ads.
---------------------------------------------------------------------------- */

function startAdBlocker() {
  // 1) Hide any ad divs on OUR page (outside the iframe we fully control).
  const killPageAds = () => {
    document
      .querySelectorAll('.ytp-ad-module, .video-ads, .ytp-ad-overlay-container, #player-ads')
      .forEach((el) => { el.style.display = 'none'; });
  };
  killPageAds();

  // 2) WatchService equivalent: observe DOM changes under the whole page.
  const observer = new MutationObserver(() => {
    killPageAds();
    trySkipAd();
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });

  // 3) Poll every 1s as backup (some ads do not trigger visible mutations).
  setInterval(() => {
    killPageAds();
    trySkipAd();
  }, 1000);
}

// Try to skip the current ad if the player API exposes one.
function trySkipAd() {
  if (!player || !isPlayerReady) return;
  try {
    // The iframe adds class "ad-showing" on the player div during ads.
    const playerEl = document.getElementById('player');
    const adShowing = playerEl && playerEl.classList.contains('ad-showing');
    // Also check for a visible Skip button we CAN click (same-origin overlay).
    const skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button');
    if (skipBtn) {
      skipBtn.click(); // like robot.click() - auto-press Skip
      console.log('[AdBlock] Clicked Skip button');
      return;
    }
    if (adShowing && typeof player.getDuration === 'function') {
      // For short bumpers: mute + jump near the end of the ad.
      const dur = player.getDuration();
      if (dur && dur < 60) {
        player.mute();
        player.seekTo(Math.max(0, dur - 0.5), true);
        console.log('[AdBlock] Jumped past short ad');
        setTimeout(() => { if (player) player.unMute(); }, 2000);
      }
    }
  } catch (e) {
    // Never crash the app because of ad logic (like catching RuntimeException).
  }
}

/* ----------------------------------------------------------------------------
  SECTION 6: SHARE FEATURE (Web Share API + clipboard fallback)
  ----------------------------------------------------------------------------
  navigator.share() opens the NATIVE mobile share sheet (WhatsApp, etc.).
  JAVA: like calling Desktop.getDesktop().share(url) - OS handles the rest.
  Desktop browsers often lack it, so we fall back to copying the link
  (like Toolkit.getSystemClipboard().setContents(...)).
---------------------------------------------------------------------------- */

async function shareCurrentVideo() {
  if (!currentVideoId) {
    setStatus('Play a video first, then share.');
    return;
  }
  const url = 'https://www.youtube.com/watch?v=' + currentVideoId;
  const shareData = { title: 'Watch via YTB Web', text: 'Check this video:', url };

  // Modern mobile path: native share sheet (WhatsApp, Telegram, Messages...).
  if (navigator.share) {
    try {
      await navigator.share(shareData);
      setStatus('Shared!');
    } catch (e) {
      // User cancelled the sheet - not an error (like closing a JDialog).
      if (e && e.name !== 'AbortError') setStatus('Share cancelled.');
    }
    return;
  }
  // Fallback: copy link to clipboard.
  try {
    await navigator.clipboard.writeText(url);
    setStatus('Share not supported here - link copied! Paste it in WhatsApp.');
  } catch (e) {
    // Last resort: show the URL so the user can long-press copy.
    prompt('Copy this link:', url); // prompt() = JOptionPane.showInputDialog
  }
}

/* ----------------------------------------------------------------------------
  SECTION 7: MINIMAL LOGIN (mock + real Google OAuth hook)
  ----------------------------------------------------------------------------
  TWO OPTIONS:
  A) MOCK (works now, no setup): stores a fake user in localStorage so you
     have an "account reference" (name shown in header).
  B) REAL GOOGLE OAUTH (needs Client ID from console.cloud.google.com):
     set GOOGLE_CLIENT_ID below and the button redirects to Google login,
     which returns to our page with ?code=... (OAuth2 code flow).
  Start with A; switch to B when you create a Google Cloud project.
---------------------------------------------------------------------------- */

// TODO: paste your Google OAuth Client ID here to enable REAL login.
// Get one free at: https://console.cloud.google.com/apis/credentials
// Leave empty ('') to keep using the instant mock login.
const GOOGLE_CLIENT_ID = '';
const GOOGLE_REDIRECT_URI = window.location.origin + window.location.pathname;

function initAuth() {
  // If Google redirected back with ?code=..., capture it as "logged in".
  const params = new URLSearchParams(window.location.search);
  const oauthCode = params.get('code');
  if (oauthCode) {
    // Real apps exchange code for tokens on a server. We just keep the
    // reference (like storing a session token string).
    localStorage.setItem(USER_KEY, JSON.stringify({ email: 'google-user (' + oauthCode.slice(0, 8) + ')', real: true }));
    // Clean the URL (remove ?code=...) - like clearing a password char[].
    window.history.replaceState({}, '', window.location.pathname);
  }
  renderAuth();
}

function renderAuth() {
  const area = document.getElementById('authArea');
  area.innerHTML = '';
  let user = null;
  try {
    user = JSON.parse(localStorage.getItem(USER_KEY) || 'null');
  } catch (e) { user = null; }

  if (user && user.email) {
    // Logged in: show email + Logout (like if (session != null)).
    const who = document.createElement('span');
    who.textContent = 'User: ' + user.email + ' ';
    who.style.fontSize = '13px';
    const out = document.createElement('button');
    out.textContent = 'Logout';
    out.className = 'btn-small';
    out.onclick = () => { localStorage.removeItem(USER_KEY); renderAuth(); setStatus('Logged out.'); };
    area.appendChild(who);
    area.appendChild(out);
  } else {
    // Logged out: show Login button.
    const btn = document.createElement('button');
    btn.textContent = 'Login with Google';
    btn.id = 'loginBtn';
    btn.className = 'btn-small';
    btn.onclick = handleLogin;
    area.appendChild(btn);
  }
}

function handleLogin() {
  if (GOOGLE_CLIENT_ID) {
    // REAL OAuth redirect: builds the Google consent URL.
    // Like response.sendRedirect("https://accounts.google.com/...") in servlet.
    const url =
      'https://accounts.google.com/o/oauth2/v2/auth' +
      '?client_id=' + encodeURIComponent(GOOGLE_CLIENT_ID) +
      '&redirect_uri=' + encodeURIComponent(GOOGLE_REDIRECT_URI) +
      '&response_type=code&scope=' + encodeURIComponent('openid email profile');
    window.location.href = url;
  } else {
    // MOCK login: no network, instant. Stores email in "properties file".
    const email = prompt('Mock login - enter any name/email:', 'demo@example.com');
    if (!email) return;
    localStorage.setItem(USER_KEY, JSON.stringify({ email: email.trim(), real: false }));
    renderAuth();
    setStatus('Mock-logged in as ' + email.trim());
  }
}

/* ----------------------------------------------------------------------------
  SECTION 8: WIRING BUTTONS + APP STARTUP (like main() + addActionListener)
---------------------------------------------------------------------------- */

function init() {
  loadHistory();   // read "properties file" into memory
  renderHistory(); // paint the JList
  initAuth();      // paint login state; handle ?code=... return

  // Play button: parse input -> loadVideo() (like form submit handler).
  document.getElementById('loadBtn').onclick = () => {
    const raw = document.getElementById('videoInput').value;
    const id = parseVideoId(raw);
    if (id) loadVideo(id);
    else setStatus('Enter a valid YouTube URL or 11-char video ID.');
  };
  // Allow Enter key in the input (like setDefaultButton on a JFrame).
  document.getElementById('videoInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') document.getElementById('loadBtn').click();
  });

  // Play/Pause toggle.
  document.getElementById('playPauseBtn').onclick = () => {
    if (!player || !isPlayerReady) { setStatus('Player still loading...'); return; }
    const state = player.getPlayerState();
    if (state === YT.PlayerState.PLAYING) player.pauseVideo();
    else player.playVideo();
  };

  // Share button -> Section 6.
  document.getElementById('shareBtn').onclick = shareCurrentVideo;

  // Keep-awake toggle -> Section 4B (must tap: browsers need a gesture).
  document.getElementById('wakeBtn').onclick = () => {
    if (wakeLock) releaseWakeLock();
    else requestWakeLock(false);
  };
  // Floating window button -> Section 4B.
  document.getElementById('pipBtn').onclick = enterPictureInPicture;

  // Clear history button.
  document.getElementById('clearHistoryBtn').onclick = () => {
    history = [];
    saveHistory();
    renderHistory();
    setStatus('History cleared.');
  };

  setStatus('Enter a YouTube link above and press Play.');
}

// Expose the API-ready callback GLOBALLY (YouTube looks for window.X).
// JAVA: like making a method public so an external framework can call it.
window.onYouTubeIframeAPIReady = onYouTubeIframeAPIReady;

// Start the app when HTML is parsed (like calling main()).
// defer on the script tag already ensures DOM is ready, so call directly.
init();

