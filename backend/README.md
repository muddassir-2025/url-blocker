# ClearView Audio Backend

Streams the **best audio-only** stream of a YouTube video to the ClearView Android app
("Download audio" / offline listening). Nothing is stored on the server — audio bytes are
piped straight through to the phone.

- **Primary engine:** [`@distube/ytdl-core`](https://www.npmjs.com/package/@distube/ytdl-core)
- **Automatic fallback:** [`youtube-dl-exec`](https://www.npmjs.com/package/youtube-dl-exec)
  (yt-dlp). If ytdl-core fails because YouTube changed its internals, the server falls back
  to yt-dlp automatically — no app update needed.

## Run locally

```bash
cd backend
npm install
npm start            # → http://localhost:3000
```

Test it:

```bash
curl "http://localhost:3000/health"
curl "http://localhost:3000/api/audio?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DVIDEO_ID" -o test.m4a
```

## Deploy to Render (free tier)

1. Push this repository to GitHub.
2. Render → **New → Web Service** → connect the repo.
3. Settings:
   - **Root Directory:** `backend`
   - **Build Command:** `npm install`
   - **Start Command:** `npm start`
   - **Instance Type:** Free
4. Optional **Environment Variables**:
   - `AUDIO_TOKEN` — a shared secret. When set, the app must send it
     (`X-Audio-Token` header); protects your free-tier bandwidth from strangers.
   - `YTDL_NO_UPDATE=1` — skips yt-dlp's periodic update check.
5. Deploy. Copy the URL (`https://<your-app>.onrender.com`) and paste it into the app:
   **Media → Downloads → ⚙ Server settings** (plus the token if you set one).

### Free-tier behaviour

- The instance **sleeps after ~15 min idle** and cold-starts on the next request
  (30–90 s). The app shows an animated **"Preparing…"** state the whole time and retries
  if the server answers `503` while booting — just tap download and wait.
- If it stays in "Preparing…" for over a minute, the instance may still be booting; tap
  the download again or open the app's Server settings and press **Test connection**.

## Notes

- M4A (AAC) is preferred, then Opus/WebM — both play natively on Android (API 21+).
- A short in-memory cache avoids re-fetching video info for repeat downloads.
- Optionally drop an exported `cookies.json` (EditThisCookie format) next to `server.js`
  and set `COOKIES_FILE=cookies.json` to improve ytdl-core reliability — the yt-dlp
  fallback already covers most breakage without it.
