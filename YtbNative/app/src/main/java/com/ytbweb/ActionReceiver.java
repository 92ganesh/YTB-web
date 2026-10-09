package com.ytbweb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* ============================================================================
  ActionReceiver.java - NOTIFICATION BUTTON HANDLER (lock-screen controls)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: an ActionListener registered on the notification's Pause/Play
  button. When tapped, Android delivers ACTION_TOGGLE here (even with the
  screen locked), and we forward it to the WebView's own play/pause button.
  Declared in AndroidManifest.xml with an <intent-filter> for com.ytbweb.TOGGLE.
============================================================================= */
public class ActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (PlaybackService.ACTION_TOGGLE.equals(intent.getAction())) {
            // Clicks the page's #playPauseBtn via JS (see MainActivity).
            MainActivity.toggleFromNotification(context);
        }
    }
}
