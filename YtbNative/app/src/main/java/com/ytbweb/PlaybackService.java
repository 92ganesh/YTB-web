package com.ytbweb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/* ============================================================================
  PlaybackService.java - RICH NOTIFICATION (title + thumbnail + buttons)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: a daemon thread registered with the OS as doing user-visible
  work. startForeground(id, notification) posts a persistent media-style
  notification (like Spotify's) and tells Android's task killer: hands off.
  Chrome tabs can NEVER do this — the OS suspends background tabs by design.

  Notification content is LIVE: MainActivity pushes title/thumbnail/playing
  state here via ACTION_UPDATE intents (Observer pattern: activity = subject,
  this service = observer). Thumbnail downloads on a background thread pool
  (never block the main thread — like SwingWorker for image loading).
============================================================================= */
public class PlaybackService extends Service {

    static final String CHANNEL_ID = "YtbWebChannel";
    static final String ACTION_TOGGLE = "com.ytbweb.TOGGLE";
    static final String ACTION_NEXT = "com.ytbweb.NEXT";
    static final String ACTION_UPDATE = "com.ytbweb.UPDATE";

    // Last known track info (like cached fields of a PlayerModel).
    private String trackTitle = "YTB Web";
    private String trackSubtitle = "Open YouTube and play - tap to open";
    private Bitmap trackArt = null;
    private boolean playing = true;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(); // Android 8+ requires a channel before notifying
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // UPDATE from MainActivity: refresh cached title/art/state, re-post.
        // JAVA: like setState(model) then repaint().
        if (intent != null && ACTION_UPDATE.equals(intent.getAction())) {
            String t = intent.getStringExtra("title");
            String s = intent.getStringExtra("subtitle");
            String artUrl = intent.getStringExtra("artUrl");
            if (t != null) trackTitle = t;
            if (s != null) trackSubtitle = s;
            playing = intent.getBooleanExtra("playing", playing);
            if (artUrl != null && !artUrl.isEmpty()) {
                fetchArtAsync(artUrl); // re-posts when bitmap arrives
            } else {
                postNotification();
            }
            return START_NOT_STICKY;
        }
        // First start (or plain restart): post with whatever we know.
        postNotification();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // nobody binds; we are start-only (like a singleton task)
    }

    // Download thumbnail off the UI thread, then re-post notification.
    // JAVA: Executors...submit(() -> ...) = SwingWorker for image loading.
    private void fetchArtAsync(final String artUrl) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                URL url = new URL(artUrl);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.connect();
                try (InputStream in = c.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) trackArt = bmp;
                }
            } catch (Exception e) {
                // Offline/blocked art must NEVER kill playback (log + continue).
                android.util.Log.w("YTB", "art fetch failed: " + artUrl, e);
            }
            postNotification();
        });
    }

    // Build + (re)post the media notification with current cached state.
    private void postNotification() {
        // Tap body -> reopen the app (like clicking a tray icon).
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, pendingFlags());

        // Pause/Play button -> ActionReceiver (works with screen LOCKED).
        Intent toggle = new Intent(this, ActionReceiver.class);
        toggle.setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getBroadcast(
                this, 1, toggle, pendingFlags());

        String toggleLabel = playing ? "Pause" : "Play";
        int toggleIcon = playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;

        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Action act = new Notification.Action.Builder(
                    null, toggleLabel, togglePi).build();
            Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle(trackTitle)
                    .setContentText(trackSubtitle)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(openPi)
                    .addAction(act)
                    .setOngoing(playing); // ongoing = swipe-proof while playing
            if (trackArt != null) b.setLargeIcon(trackArt); // video thumbnail!
            // MediaStyle WITHOUT a MediaSession token still renders the media
            // template (big art + lock-screen placement) on most ROMs. A null
            // token is legal — setMediaSession(null) is simply skipped here.
            // (Full MediaSession wiring is the v5 upgrade: real lock-screen
            // transport controls driven by the OS instead of our receiver.)
            b.setStyle(new Notification.MediaStyle());
            n = b.build();
        } else {
            Notification.Builder b = new Notification.Builder(this)
                    .setContentTitle(trackTitle)
                    .setContentText(trackSubtitle)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(openPi)
                    .addAction(toggleIcon, toggleLabel, togglePi)
                    .setOngoing(playing);
            if (trackArt != null) b.setLargeIcon(trackArt);
            n = b.build();
        }

        // THE call that grants background survival (like thread.setDaemon(false)
        // plus a priority boost — OS will not kill us while this shows).
        startForeground(1, n);
        // Telemetry: WHAT the user actually sees (or doesn't). If title is
        // still "YTB Web" here, the model never got a real title = bridge dead.
        Telemetry.send("notif", "posted: playing=" + playing,
                "{\"title\":" + Telemetry.q(trackTitle)
                + ",\"subtitle\":" + Telemetry.q(trackSubtitle)
                + ",\"hasArt\":" + (trackArt != null) + "}");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf(); // user swiped app away -> drop the notification too
    }

    private void createChannel() {
        // Notification channels exist only on Android 8+ (like log categories).
        // v4: IMPORTANCE_DEFAULT (was LOW). LOW hides the banner/sound AND —
        // on several OEM ROMs — collapses media notifications to a tiny row
        // or nothing at all. DEFAULT shows the heads-up banner + lock-screen
        // card while staying quiet-ish. Channel importance is SET ONCE per
        // install: if the user installed earlier, they must CLEAR APP DATA
        // (or reinstall) for the new importance to take effect — Android
        // ignores re-creation with different importance (documented behavior).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "YTB Web playback",
                    NotificationManager.IMPORTANCE_DEFAULT); // banner visible!
            ch.setDescription("Keeps YTB Web playing in background");
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(ch);
        }
    }

    private int pendingFlags() {
        // Android 12+ REQUIRES mutability flag (like making an Intent final).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }
}
