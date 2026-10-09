# YTB Web — native Android shell (no Android Studio needed)

Personal YouTube player: WebView + network ad-block + background playback.
Your `index.html` / `styles.css` / `app.js` ARE the UI (served from your Mac
in DEV, bundled in `assets/www/` for the FINAL standalone APK).

## DEV mode (now): phone loads from your Mac
1. `MainActivity.START_URL` = `http://192.168.1.35:8000` (already set).
2. On Mac: `cd "/Users/ganeshpatra/Documents/Projects/YTB Web" && python3 -m http.server 8000`
3. Push to GitHub (commands below) → Actions builds APK → install on phone.
4. Edit web files → save → pull-to-refresh in the app. No rebuild needed.

## FINAL mode (later): standalone APK
1. `cp ../index.html ../styles.css ../app.js YtbNative/app/src/main/assets/www/`
2. In `MainActivity.java`: comment the `http://` START_URL line,
   uncomment the `file:///android_asset/www/index.html` line.
3. Push → rebuild → final APK works anywhere, no Mac needed.

## Push commands (run from "YTB Web" folder, repo root)
```bash
cd "/Users/ganeshpatra/Documents/Projects/YTB Web"
git init -b main
git add index.html styles.css app.js YtbNative .github .gitignore
git commit -m "YTB Web native shell (dev: server mode)"
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```
Then: GitHub → Actions tab → wait ~5 min → Artifacts → `YTB-Web-debug-apk`.

## What the native shell adds over a Chrome tab
- `KeepAliveWebView` — swallows hide signal, YouTube never self-pauses.
- `AdBlockClient` — firewall blocks ad hosts + injects skip logic.
- `PlaybackService` + `ActionReceiver` — persistent notification, survives lock.

No Firebase, no tracking, zero external libraries.
