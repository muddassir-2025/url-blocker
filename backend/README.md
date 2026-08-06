# ClearView Audio Backend

Streams the **best audio-only** stream of a YouTube video to the ClearView Android app
("Download audio" / offline listening). Nothing is stored on the server — audio bytes are
piped straight through to the phone.

- **Primary engine:** [`youtube-dl-exec`](https://www.npmjs.com/package/youtube-dl-exec)
  (yt-dlp). Actively maintained and works against current YouTube.
- **No login anywhere.** Downloads work with an **anonymous guest session** (below) plus a
  PO token — no YouTube account, no cookie exporting, nothing to refresh.
- **Automatic fallback:** [`@distube/ytdl-core`](https://www.npmjs.com/package/@distube/ytdl-core).
  If every yt-dlp client fails, the server falls back to ytdl-core — no app update needed.
- The **yt-dlp binary and the PO-token provider are downloaded at install time**
  (`scripts/fetch-ytdlp.js` + `scripts/fetch-pot-provider.js`, hooked into `postinstall`),
  and the binary is **auto-updated at runtime** (`scripts/update-ytdlp.js`) so YouTube
  extractor breakage doesn't take the service down between deploys.

## How a download works

```
GET /api/audio?url=… → app retries on 503 (cold boot / queue busy)
        │
        ├─ 1. Stream cache hit?  (keyed by video id, TTL ~3 h)
        │       → pipe the cached direct URL. NO yt-dlp, NO PO-token call.
        │
        └─ 2. Rate-limited extraction queue (token bucket, single-flight):
              a. yt-dlp with the persistent guest cookiejar (--cookies) +
                 PRIMARY chain  mweb,web + fetch_pot=always + PO token
              b. fallback chains (mobile innertube clients, then defaults)
              c. one retry with backoff on transient bot-check / 429 failures
              d. if all chains fail → ytdl-core fallback (same queue slot)
              e. cache the fresh stream URL, then pipe it to the client
```

### Anonymous guest session (the fix for cookie-less bot checks)

The old pattern ran yt-dlp with **no `--cookies` at all**, so every request looked like a
brand-new, unrelated client — which is exactly what makes YouTube answer
*"Sign in to confirm you're not a bot"* on datacenter IPs.

The server now always passes `--cookies <tmpdir>/clearview-guest-cookies.txt` — a writable
cookiejar that **yt-dlp creates and reuses across calls**. YouTube's own anonymous visitor
cookies (`VISITOR_INFO1_LIVE`, …) persist between requests, so the service reads as one
continuing guest session instead of a fresh stranger every time. The file starts empty and
yt-dlp populates it; **no login/account cookies ever go in it**.

> We deliberately do **not** pass `visitor_data` via `--extractor-args` + `player_skip`
> (documented as less stable) — yt-dlp manages the guest session naturally through the jar.

### Client chain

- **PRIMARY** (configurable): `player_client=mweb,web` with `fetch_pot=always` + the PO
  token plugin. `mweb,web` is currently the recommended no-login pairing; `web_embedded`
  is excluded (returned `LOGIN_REQUIRED` in testing). Override the list with `YTDLP_CLIENTS`
  when YouTube shifts its trust signals.
- **FALLBACK**: mobile innertube clients (`android_vr,android,ios,web_safari,web_music`) —
  direct signed URLs, no PO token needed.
- **LAST**: yt-dlp's default client set.

### PO-token provider

YouTube also bot-blocks datacenter IPs at the *IP* level. The bundled
[bgutil-ytdlp-pot-provider](https://github.com/Brainicism/bgutil-ytdlp-pot-provider) solves
YouTube's BotGuard attestation **on the server's own IP** and hands yt-dlp a proof-of-origin
(PO) token, which is exactly the bypass that works from flagged IPs. It is spawned at boot as
a local HTTP server on `127.0.0.1:4416`; the plugin's solve timeout is patched to 70 s at
build time (cold solves on Render take 20–70 s).

### Boot check (self-test)

At boot the server probes extraction with **both chains across several videos** — not just one
smoke-test id. The default set mixes the classic yt-dlp canary (`jNQXAC9IVRw` — hammered by
CI traffic, so it must never be the *only* signal) with an ordinary public video and two real
videos from the RSS feeds this app actually serves (Makkah + Madinah channels):

- `jNQXAC9IVRw` (canary) · `aqz-KE-bpKQ` (Big Buck Bunny) · `C_iHHP8LfGk` (Madinah feed) ·
  `jK6wgG6C4PY` (Makkah feed)

Override the set with `BOOT_CHECK_VIDEOS` (comma-separated URLs or bare 11-char ids) — plug in
the latest ids from your own RSS feeds. The first probe A warms the guest cookiejar and the
PO-token provider's minter and opens the request gate; the remaining probes run in the
background with throwaway cookiejars (they never race real requests on the shared jar). Every
probe logs a per-video verdict, and **both** probe types dump their verbose output tail on
failure so a broken chain is diagnosable from the log alone. Each probe is one YouTube
guest-session request — if the probe volume ever matters under load, trim the list via
`BOOT_CHECK_VIDEOS` or set `DEBUG_BOOT_CHECK=false` (the provider warmup still runs).

### Guest-session rate ceiling + queue

YouTube's documented guideline for a guest session is **~300 requests/hour per session/IP**.
The server enforces a global token bucket (per-process = the only IP on Render's free tier),
default budget **250/hour**, and runs extractions through a **single-flight FIFO queue**
(which also keeps the shared cookiejar free of concurrent write races). Requests past the
budget queue; if they are still queued after `QUEUE_MAX_WAIT_MS` (12 s) the server answers
**503** with `Retry-After`, `X-Audio-Queue-Position` and `X-Audio-Queue-Wait-Ms` — the app
retries automatically and shows the queue position if it keeps failing. Cache hits never
touch the queue.

### Stream cache

The extracted direct URL (or the ytdl-core fallback URL) is cached **keyed by video id** for
`STREAM_CACHE_TTL_MINUTES` (default 180 — stream URLs stay valid for a few hours). Repeat
downloads of the same video — very common for popular RSS episodes — are served **without
touching yt-dlp or the PO-token provider at all**. This is the main lever for staying under
the guest-session ceiling under multi-user load.

### Error handling

Total failures return a **clean, user-facing message** (never a stack trace) plus a
machine-readable header `X-Audio-Error-Code`:

| Code | HTTP | Meaning | App behaviour |
|------|------|---------|---------------|
| `login` | 403 | Private / age-restricted / members-only — needs sign-in | Shown immediately, not retried |
| `botcheck` | 503 | YouTube blocking the server right now | Retried automatically |
| `transient` | 502 | Network/timeout hiccup | Retried automatically |
| `queue` | 503 | Rate-limit queue backed up | Retried automatically (Retry-After) |
| `unknown` | 500 | Generic failure | Retried automatically |

Full yt-dlp stderr is logged server-side (STDERR TAIL) for debugging; the app only ever sees
the short message.

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

Run the unit tests for the pipeline (rate limiter, cache, error classifiers):

```bash
node test/extraction-pipeline.test.js
```

Run the full local end-to-end test (starts the server + provider, does a real download and
a cache hit):

```bash
bash scripts/local-e2e-test.sh
```

## Deploy to Render (free tier)

1. Push this repository to GitHub.
2. Render → **New → Web Service** → connect the repo.
3. Settings:
   - **Root Directory:** `backend`
   - **Build Command:** `npm install` (also fetches the pinned yt-dlp binary and the
     PO-token provider via the postinstall scripts)
   - **Start Command:** `npm start`
   - **Instance Type:** Free
4. Optional **Environment Variables** (all have safe defaults):

| Variable | Default | Purpose |
|----------|---------|---------|
| `AUDIO_TOKEN` | (none) | Shared secret — the app must send it (`X-Audio-Token`). Protects your bandwidth from strangers. |
| `YTDLP_CLIENTS` | `mweb,web` | Primary client list for the PO chain (e.g. `android_vr,ios,web_safari` if YouTube shifts trust signals). |
| `RATE_LIMIT_PER_HOUR` | `250` | Extraction budget/hour — keep under ~300 (guest-session ceiling). |
| `RATE_LIMIT_BURST` | `5` | Token-bucket burst capacity. |
| `QUEUE_MAX_WAIT_MS` | `12000` | How long a request queues before the server answers 503 + Retry-After. |
| `STREAM_CACHE_TTL_MINUTES` | `180` | How long extracted stream URLs stay cached. |
| `STREAM_CACHE_MAX_ENTRIES` | `500` | Cache size cap (oldest evicted). |
| `YTDLP_RETRY_BACKOFF_MS` | `3000` | Backoff before the single transient-failure retry pass. |
| `PROXY` | (none) | Optional `--proxy` for yt-dlp — an escape hatch if the guest session + cache are ever rate-limited at scale; NOT required normally. |
| `DEBUG_BOOT_CHECK` | `true` | `false` skips the boot-time yt-dlp extraction probes (the provider warmup still runs). |
| `BOOT_CHECK_VIDEOS` | 4 default videos | Comma-separated YouTube URLs or 11-char video ids probed at boot with both chains (default: the classic canary + an ordinary public video + 2 real videos from this app's RSS feeds). |
| `YTDLP_AUTO_UPDATE` | `true` | `false` disables the runtime yt-dlp updater. |
| `YTDLP_UPDATE_CHECK_HOURS` | `24` | How often the updater may check GitHub (it also runs ~5 min after each boot). |
| `YTDLP_FORCE` | — | Force postinstall scripts / the updater to re-download. |
| `YTDLP_VERSION` | `2026.07.04` | Build-time pin for the yt-dlp binary. |
| `POT_PORT` | `4416` | Port for the PO-token provider. |
| `YTDL_NO_UPDATE=1` | — | Skips yt-dlp's own periodic update check (recommended; the pinned binary + auto-updater handle freshness). |

5. Deploy. **Important:** after pushing this commit, open the Render dashboard and trigger a
   new deploy (the changed build step must run to fetch the yt-dlp binary). Copy the URL
   (`https://<your-app>.onrender.com`) and paste it into the app:
   **Media → Downloads → Server settings**.

### Free-tier behaviour

- The instance **sleeps after ~15 min idle** and cold-starts on the next request (30–90 s).
  The app shows an animated **"Preparing…"** state and retries if the server answers `503`
  while booting — just tap download and wait.
- If it stays in "Preparing…" for over a minute, the instance may still be booting; tap the
  download again or open Server settings and press **Test connection**.
- The yt-dlp auto-update runs ~5 min after each boot (and then on the 24 h interval while
  awake) — the script's own gate file keeps a frequently cold-starting instance from
  hammering the GitHub API.

## Known trade-off (accepted)

**Age-restricted, private, and members-only videos cannot be downloaded** — they require a
YouTube sign-in, which this service deliberately never uses. Such videos answer with the
`login` error (`HTTP 403`, *"This video requires a YouTube sign-in…"*). This is acceptable
for this app because the RSS feeds it serves are public content.

## Reading the logs

- Every HTTP request is logged as **`[req] METHOD /path -> STATUS in Nms`** — including the
  silent early-return paths (400 bad URL, 401 token mismatch, 503 while the instance is
  warming up). If the app taps Download and you see **no `[req]` line at all**, the request
  never reached this service — check the app's Server URL in **Downloads → Server settings**
  (Test connection) matches the service whose logs you're watching.
- Boot-check lines are prefixed **`[boot-check]`** so monitoring/alerting can exclude them
  (real request failures are `[yt-dlp]`, `[extract]`, `[cache]`, `[rate-limit]` lines).
  Set `DEBUG_BOOT_CHECK=false` to skip the extraction probes entirely (the provider warmup
  still runs). Every probe logs a per-video verdict line
  (`PRIMARY chain … extraction OK/FAILED`, `FALLBACK mobile chain … extraction OK/FAILED`),
  and **both** probe types dump their verbose output tail on failure — a failure is always
diagnosable from the log without a re-run.
- `[cache] HIT <id>` — a repeat download served from the cache (no YouTube call).
- `[rate-limit] queue full — 503 … (position #N)` — the guest-session ceiling is being hit.
- `[pot:bgutil:script-node] Script path doesn't exist…` lines are **expected noise** from
  yt-dlp's provider availability checks — they appear even in fully working runs.
- The line proving the PO pipeline works is
  `[boot-check] provider GENERATED N token generation(s) during probe A — PO pipeline works end-to-end`.
- `[update-ytdlp]` lines report yt-dlp release checks / swaps.

## Account cookies are retired (`COOKIES_B64` / `cookies.txt`)

Older versions used `COOKIES_B64` (base64 of a Netscape `cookies.txt` exported from a
**signed-in** browser) or a `cookies.txt` next to `server.js`. Those are **retired**: account
cookies expire and get rotated by YouTube, and the anonymous guest session + PO token is the
only supported path. If `COOKIES_B64` or `cookies.txt` is still present, the server logs a
warning and **ignores it** — the guest cookiejar is always used. You can delete them.

## Notes

- M4A (AAC) is preferred, then Opus/WebM — both play natively on Android (API 21+).
- If you hit `unknown` errors that persist, trigger a **Manual Deploy** (re-runs
  `npm install`, refreshing the yt-dlp binary) or check the `[update-ytdlp]` logs — a yt-dlp
  version lag is the most common cause of sudden breakage, and the auto-updater is the fix.
