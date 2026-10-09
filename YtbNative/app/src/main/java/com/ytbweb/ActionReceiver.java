package com.ytbweb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/* ============================================================================
  ActionReceiver.java - NOTIFICATION BUTTON HANDLERS (lock-screen controls)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: ActionListeners on the notification's Pause/Play buttons.
  Android delivers the action here even with the screen locked; we forward to
  MainActivity, which drives YouTube's own <video> element via JavaScript.
  Declared in AndroidManifest.xml with <intent-filter> for TOGGLE + NEXT.
============================================================================= */
public class ActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        switch (intent.getAction()) {
            case PlaybackService.ACTION_TOGGLE:
                // Pause/Play the actual YouTube video (works locked).
                MainActivity.toggleFromNotification(context);
                break;
            case PlaybackService.ACTION_NEXT:
                // Naive next: history-forward else reload (honest v1).
                MainActivity.nextFromNotification(context);
                break;
            default:
                break; // unknown action: ignore (like default: in switch)
        }
    }
}
