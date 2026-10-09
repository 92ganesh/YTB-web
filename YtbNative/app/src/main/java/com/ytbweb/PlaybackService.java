package com.ytbweb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/* ============================================================================
  PlaybackService.java - THE "DO NOT KILL ME" NOTICE (background privilege)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: a daemon thread that registers itself with the OS as doing
  user-visible work. startForeground(id, notification) posts a persistent
  notification (like Spotify's) and tells Android's task killer: hands off.
  Chrome tabs can NEVER do this — the OS suspends background tabs by design.
  This + the KeepAliveWebView hack = the two-part background trick from the
  repo you found (concept copied, Firebase tracking NOT copied).
============================================================================= */
public class PlaybackService extends Service {

    static final String CHANNEL_ID = "YtbWebChannel";
    static final String ACTION_TOGGLE = "com.ytbweb.TOGGLE";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(); // Android 8+ requires a channel before notifying
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Tap notification -> reopen the app (like clicking a tray icon).
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, open, pendingFlags());

        // Pause/Play button INSIDE the notification -> ActionReceiver.
        Intent toggle = new Intent(this, ActionReceiver.class);
        toggle.setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getBroadcast(
                this, 1, toggle, pendingFlags());

        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("YTB Web")
                    .setContentText("Playing — tap to open")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(openPi)
                    .addAction(new Notification.Action.Builder(
                            null, "Pause/Play", togglePi).build())
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("YTB Web")
                    .setContentText("Playing — tap to open")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(openPi)
                    .addAction(android.R.drawable.ic_media_pause, "Pause/Play", togglePi)
                    .build();
        }

        // THE call that grants background survival (like thread.setDaemon(false)
        // plus a priority boost — OS will not kill us while this shows).
        startForeground(1, n);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // nobody binds; we are start-only (like a singleton task)
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        stopSelf(); // user swiped app away -> drop the notification too
    }

    private void createChannel() {
        // Notification channels exist only on Android 8+ (like log categories).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "YTB Web playback",
                    NotificationManager.IMPORTANCE_LOW); // low = silent, no buzz
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
