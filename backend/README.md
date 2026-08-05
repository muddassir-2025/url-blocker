# ClearView Audio Backend

Streams the **best audio-only** stream of a YouTube video to the ClearView Android app
("Download audio" / offline listening). Nothing is stored on the server — audio bytes are
piped straight through to the phone.

- **Primary engine:** [`youtube-dl-exec`](https://www.npmjs.com/package/youtube-dl-exec)
  (yt-dlp). Actively maintained and works against current YouTube.
- **Bot-detection defense:** YouTube bot-blocks datacenter IPs (Render free
  tier) with *"Sign in to confirm you're not a bot"* — even with valid signed-in
  cookies, because the block is IP-level. The reliable fix is the bundled
  **PO-token provider** ([bgutil-ytdlp-pot-provider](https://github.com/Brainicism/bgutil-ytdlp-pot-provider)):
  a local HTTP server that solves YouTube's BotGuard attestation **on the
  server's own IP** and hands yt-dlp a proof-of-origin (PO) token. When the
  provider is up, the server tries the `web` client + PO token first; no
  cookies needed, so every user can download.
- **Client-chain retries:** if the provider is unavailable, the server retries
  the mobile innertube clients (`android_vr` → `android` → `ios` → `web_safari`)
  and the default chain, and uses signed-in cookies if `COOKIES_B64` is set.
- **Automatic fallback:** [`@distube/ytdl-core`](https://www.npmjs.com/package/@distube/ytdl-core).
  If every yt-dlp client fails, the server falls back to ytdl-core — no app
  update needed.
- The **yt-dlp binary and the PO-token provider are downloaded at install time**
  (`scripts/fetch-ytdlp.js` + `scripts/fetch-pot-provider.js`, hooked into
  `postinstall`) so a request never waits on a lazy download.

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
   - `YTDLP_FORCE=1` — forces the postinstall scripts to re-download the
     yt-dlp binary and the PO-token provider even if they already exist (use
     if a build cache carried older artifacts over and downloads start failing).
   - `COOKIES_B64` — optional base64 of a Netscape-format `cookies.txt` (see
     below). When set, cookies are passed to yt-dlp **on top of** the PO token
     — the strongest combination.
   - `POT_PORT` — optional port for the PO-token provider (default `4416`).
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

### How the PO-token provider works

- At build time (`npm install`) `scripts/fetch-pot-provider.js` clones
  `bgutil-ytdlp-pot-provider` (pinned tag `1.3.1`), installs it, and downloads
  the matching yt-dlp plugin zip into `plugins/`.
- At boot, `server.js` spawns the provider on `127.0.0.1:4416` (a child
  process, ~100–150 MB). The Render log will show
  `[pot] PO-token provider ready on port 4416`.
- Every yt-dlp call then runs the `web` client with a fresh PO token — this
  bypasses the datacenter-IP bot check. Cookies (if set) are still passed too.
- If the provider fails to build or start, the server logs a warning and runs
  without PO tokens (client chains + ytdl-core), exactly like before.

### Reading the boot check / PO-token logs

During boot the server runs a self-check that ends with a verdict like
`provider GENERATED N token generation(s) during the probe — PO pipeline works
end-to-end`. Two log patterns are easy to misread:

- `[pot:bgutil:script-node] No server_home...` / `Script path doesn't exist...`
  are **expected noise**: yt-dlp checks every registered provider's availability,
  including the script-based ones you're not using. They appear even in fully
  working runs and do **not** mean the HTTP provider was skipped.
- The line that proves the HTTP provider is being used is
  `[pot:bgutil:http] Generating a ... PO Token for ... via bgutil HTTP server`.
- `direct /get_pot probe -> HTTP 200 in Ns`: if `N > 20`, real downloads will
  fail anyway — the bgutil plugin's own solve timeout is 20 s, so a slow solve
  means yt-dlp runs without a token and gets bot-blocked.
- If the verdict says `bgutil HTTP provider loaded but NO token generation was
  observed`, the boot check dumps the yt-dlp probe's output tail. That dump is
  the decisive clue: look for `Error reaching GET .../ping` (plugin can't reach
  the provider), `HTTP Error 403` / `Sign in to confirm you're not a bot`
  (YouTube blocking the requests before a token was attached), or
  `failed to get token` (solve too slow).

### Setting up cookies (optional, on top of the PO token)

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
