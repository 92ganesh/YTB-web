package com.ytbweb;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

/* ============================================================================
  KeepAliveWebView.java - THE BACKGROUND TRICK (top-level for layout inflation)
  ----------------------------------------------------------------------------
  A WebView stops JS/media when its window goes GONE/INVISIBLE (screen lock).
  Overriding onWindowVisibilityChanged and DROPPING those two signals means
  the YouTube page never learns it was hidden — Google's self-pause never
  fires. Like overriding setVisible(false) with a no-op.
  Concept from the YTbackground repo you found; Firebase tracking NOT copied.
  Top-level (not nested) so activity_main.xml can inflate it by class name.
============================================================================= */
public class KeepAliveWebView extends WebView {

    public KeepAliveWebView(Context c) {
        super(c);
    }

    public KeepAliveWebView(Context c, AttributeSet a) {
        super(c, a); // layout inflation uses THIS constructor (must exist!)
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (visibility == View.GONE || visibility == View.INVISIBLE) {
            return; // swallow: pretend we are still visible
        }
        super.onWindowVisibilityChanged(visibility);
    }
}
