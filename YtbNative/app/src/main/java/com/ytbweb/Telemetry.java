package com.ytbweb;

import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/* ============================================================================
  Telemetry.java - LIVE DIAGNOSTIC REPORTER (phone -> your Mac server)
  ----------------------------------------------------------------------------
  JAVA ANALOGY: a bounded, drop-on-overflow log appender (like Logback's
  AsyncAppender) POSTing JSON lines to http://<mac>:8081/log. Rules:
    1. FIRE-AND-FORGET: send() never blocks — events go to a bounded queue
       (capacity 500, newest dropped when full) drained by ONE daemon thread.
    2. BEST-EFFORT: short timeouts + catch-all; server down / WiFi changed /
       Mac asleep => events silently dropped. Telemetry never crashes the app.
    3. BATCHED: drain holds up to 2s to send 50 events per POST (bulk insert).
  Server: telemetry_server.py (stdlib only) prints live + writes telemetry.log.
============================================================================= */
public final class Telemetry {

    private static final String TAG = "YTB-TEL";

    // DEV server endpoint (same Mac, port 8081 so it never collides with the
    // :8000 file server). FINAL mode: posts fail fast and are dropped.
    // NOTE 2026-10-17: Mac DHCP moved 192.168.1.35 -> .34; endpoint follows.
    // If logs stop again, run `ipconfig getifaddr en0` on the Mac and update.
    private static final String ENDPOINT = "http://192.168.1.34:8081/log";

    private static final int QUEUE_CAP = 500;
    private static final int BATCH_MAX = 50;
    private static final long FLUSH_MS = 2000;

    private static final LinkedBlockingQueue<String> QUEUE =
            new LinkedBlockingQueue<>(QUEUE_CAP);

    static {
        // One daemon drain thread per process, started on class load.
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ytb-telemetry");
            t.setDaemon(true);
            return t;
        }).execute(Telemetry::drainLoop);
    }

    private Telemetry() { } // static-only utility

    /** Queue one JSON event. Never blocks, never throws. */
    public static void send(String type, String msg, String extraJsonOrNull) {
        try {
            String json = "{\"ts\":" + System.currentTimeMillis()
                    + ",\"type\":" + quote(type)
                    + ",\"msg\":" + quote(msg == null ? "" : msg)
                    + (extraJsonOrNull == null ? "" : ",\"extra\":" + extraJsonOrNull)
                    + "}";
            QUEUE.offer(json); // drop-newest when full (playback > debug trail)
        } catch (Exception ignored) { }
    }

    public static void send(String type, String msg) {
        send(type, msg, null);
    }

    /** Quote a raw string as a JSON string literal (for building extra JSON
        from Java without an org.json dependency). */
    public static String q(String s) {
        return quote(s == null ? "" : s);
    }


    // Drain loop: take 1st event (blocking = zero CPU when idle), grab the
    // rest up to BATCH_MAX, POST once, then pause FLUSH_MS (radio-friendly).
    private static void drainLoop() {
        java.util.ArrayList<String> batch = new java.util.ArrayList<>(BATCH_MAX);
        while (true) {
            try {
                batch.clear();
                batch.add(QUEUE.take()); // blocks until an event exists
                QUEUE.drainTo(batch, BATCH_MAX - 1);
                post("[" + android.text.TextUtils.join(",", batch) + "]");
                Thread.sleep(FLUSH_MS);
            } catch (InterruptedException e) {
                return; // process dying — exit quietly (daemon teardown)
            } catch (Exception e) {
                Log.w(TAG, "drain error", e);
                try { Thread.sleep(FLUSH_MS); } catch (InterruptedException ie) { return; }
            }
        }
    }

    // Single batched POST, short timeouts, all errors swallowed (best-effort).
    private static void post(String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setConnectTimeout(1500); // fail fast: Mac asleep/WiFi changed
            c.setReadTimeout(1500);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int code = c.getResponseCode(); // must read to actually send
            if (code < 200 || code >= 300) Log.w(TAG, "server returned " + code);
        } catch (Exception e) {
            Log.w(TAG, "post failed (server down? WiFi changed?)", e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // Minimal JSON string escaper (no org.json dependency on old devices).
    private static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.append('"').toString();
    }
}
