# ClearView Audio Backend

Streams the **best audio-only** stream of a YouTube video to the ClearView Android app
("Download audio" / offline listening). Nothing is stored on the server — audio bytes are
piped straight through to the phone.

- **Primary engine:** [`youtube-dl-exec`](https://www.npmjs.com/package/youtube-dl-exec)
  (yt-dlp). Actively maintained and works against current YouTube.
- **Automatic fallback:** [`@distube/ytdl-core`](https://www.npmjs.com/package/@distube/ytdl-core).
  If yt-dlp fails because YouTube changed its internals, the server falls back
  to ytdl-core automatically — no app update needed.
- The **yt-dlp binary is downloaded at install time** (`scripts/fetch-ytdlp.js`,
  hooked into `postinstall`) so a request never waits on a lazy binary download.

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
   - **Build Command:** `npm install` (also fetches the pinned yt-dlp binary
     via the postinstall script)
   - **Start Command:** `npm start`
   - **Instance Type:** Free
4. Optional **Environment Variables**:
   - `AUDIO_TOKEN` — a shared secret. When set, the app must send it
     (`X-Audio-Token` header); protects your free-tier bandwidth from strangers.
   - `YTDL_NO_UPDATE=1` — skips yt-dlp's periodic update check (recommended;
     the pinned binary is refreshed on every fresh deploy).
   - `YTDLP_FORCE=1` — forces the postinstall script to re-download the
     yt-dlp binary even if one already exists (use if a build cache carried
     an older binary over and downloads start failing).
5. Deploy. **Important:** after pushing this commit, open the Render dashboard
   and trigger a new deploy (the changed build step must run to fetch the
   yt-dlp binary). Copy the URL (`https://<your-app>.onrender.com`) and paste it
   into the app: **Media → Downloads → Server settings**.

### Free-tier behaviour

- The instance **sleeps after ~15 min idle** and cold-starts on the next request
  (30–90 s). The app shows an animated **"Preparing…"** state the whole time and retries
  if the server answers `503` while booting — just tap download and wait.
- If it stays in "Preparing…" for over a minute, the instance may still be booting; tap
  the download again or open the app's Server settings and press **Test connection**.

## Notes

- M4A (AAC) is preferred, then Opus/WebM — both play natively on Android (API 21+).
- A short in-memory info cache avoids re-fetching video metadata for repeat downloads.
- If you hit "Could not fetch this audio right now", trigger a **Manual Deploy**
  from the Render dashboard — it re-runs `npm install` and refreshes the yt-dlp
  binary. Optionally drop an exported `cookies.json` (EditThisCookie format) next
  to `server.js` for even better reliability.
