#!/usr/bin/env python3
"""YTB Web telemetry server — live diagnostic monitor (stdlib only, no pip).

JAVA ANALOGY: a tiny log collector (like a Logback SocketAppender receiver).
The Android app POSTs JSON event batches to http://<this-mac>:8081/log;
this script prints them LIVE to your terminal AND appends to telemetry.log.

Run:  python3 telemetry_server.py          (listens on 0.0.0.0:8081)
Stop: Ctrl+C. Log file: ./telemetry.log (same folder, JSON-lines format).

Event types sent by the app:
  adblock  - firewall killed a request (which host/pattern matched)
  notif    - notification posted/updated (title/state shown or not)
  js       - WebView JS bridge event (watchdog alive, skip clicked, videos)
  nav      - page navigation (URL/title changes, SPA transitions)
  error    - anything unexpected (with best-effort stack info)
"""
import datetime
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8081
LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "telemetry.log")

# Short pretty labels per event type (like log levels with colors if supported).
LABELS = {
    "adblock": "ADBLOCK",
    "notif": "NOTIF  ",
    "js": "JS     ",
    "nav": "NAV    ",
    "error": "ERROR  ",
}


def fmt_time(ts_ms):
    try:
        dt = datetime.datetime.fromtimestamp(ts_ms / 1000.0)
        return dt.strftime("%H:%M:%S.%f")[:-3]
    except Exception:
        return "??:??:??.???"


def handle_event(ev):
    """Print one event live + append raw JSON to telemetry.log."""
    etype = str(ev.get("type", "?"))[:16]
    msg = str(ev.get("msg", ""))[:300]
    extra = ev.get("extra")
    label = LABELS.get(etype, etype.ljust(7)[:7])
    line = "[%s] %s %s" % (fmt_time(ev.get("ts", 0)), label, msg)
    if extra is not None:
        line += " | %s" % json.dumps(extra)[:300]
    print(line, flush=True)
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(json.dumps(ev, ensure_ascii=False) + "\n")
    except Exception as e:  # logging must never kill the server
        print("[server] log write failed: %s" % e, flush=True)


class Handler(BaseHTTPRequestHandler):
    server_version = "YTB-Telemetry/1.0"

    def log_message(self, *args):  # silence default stderr per-request spam
        pass

    def _send(self, code, body=b"ok"):
        self.send_response(code)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except BrokenPipeError:
            pass  # phone moved on; harmless (like a closed socket)

    def do_POST(self):
        if self.path != "/log":
            return self._send(404, b"not found")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > 1_000_000:  # sanity cap: 1MB per batch
            return self._send(400, b"bad length")
        try:
            raw = self.rfile.read(length)
            payload = json.loads(raw.decode("utf-8"))
        except Exception as e:
            print("[server] bad JSON: %s" % e, flush=True)
            return self._send(400, b"bad json")
        events = payload if isinstance(payload, list) else [payload]
        for ev in events:
            if isinstance(ev, dict):
                handle_event(ev)
        return self._send(200)

    def do_GET(self):
        # Health check: open http://<mac>:8081/ in a browser to verify.
        if self.path == "/":
            return self._send(200, b"YTB telemetry alive. POST JSON to /log")
        return self._send(404, b"not found")


def main():
    print("YTB telemetry server on 0.0.0.0:%d" % PORT, flush=True)
    print("Live log below. Full history -> %s" % LOG_FILE, flush=True)
    print("-" * 60, flush=True)
    srv = HTTPServer(("0.0.0.0", PORT), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n[server] stopped.", flush=True)


if __name__ == "__main__":
    sys.exit(main())
