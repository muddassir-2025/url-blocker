# ClearView Audio Backend

Streams the **best audio-only** stream of a YouTube video to the ClearView Android app
("Download audio" / offline listening). Nothing is stored on the server — audio bytes are
piped straight through to the phone.

- **Primary engine:** [`youtube-dl-exec`](https://www.npmjs.com/package/youtube-dl-exec)
  (yt-dlp). Actively maintained and works against current YouTube.
- **Bot-detection defense:** YouTube bot-blocks the plain `web` client on
  datacenter IPs (Render free tier) with *"Sign in to confirm you're not a
  bot"*. The server automatically retries with the mobile innertube clients
  (`android_vr` → `android` → `ios` → `web_safari`) before falling back to
  the default chain — most requests succeed without any setup.
- **Guaranteed fix:** add signed-in YouTube **cookies** (see below) when even
  the mobile clients are blocked.
- **Automatic fallback:** [`@distube/ytdl-core`](https://www.npmjs.com/package/@distube/ytdl-core).
  If every yt-dlp client fails, the server falls back to ytdl-core — no app
  update needed.
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
   - `COOKIES_B64` — base64 of a Netscape-format `cookies.txt` (see below).
     The server writes it to a temp file at boot and passes it to yt-dlp.
     This is the **reliable fix** if downloads still say *"Sign in to confirm
     you're not a bot"* after the client-chain retries.
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
  binary.

### Setting up cookies (only needed if downloads are bot-blocked)

1. On your computer (desktop Chrome), install the **"Get cookies.txt LOCALLY"**
   extension. Open an **incognito window**, go to https://www.youtube.com and
   **sign in** to your Google account, then click the extension icon and
   **Export** — save the file (it's in Netscape format).
2. Convert it to base64 and paste into Render as the `COOKIES_B64` env var:

   ```bash
   base64 -w0 cookies.txt   # Linux/Mac  → copy the output
   # Windows PowerShell:
   # [Convert]::ToBase64String([IO.File]::ReadAllBytes("cookies.txt"))
   ```

3. Render → your service → **Environment** → add `COOKIES_B64` with that value
   → **Deploy** (env changes restart the service).

   Cookie files expire; re-export and update `COOKIES_B64` when downloads stop
   working again. Keep the file private — it grants access to your account.
