/**
 * ClearView Offline Audio backend
 * -------------------------------
 * Endpoints:
 *   GET /health            → { ok: true }  (used by the app's "Test connection")
 *   GET /api/audio?url=…   → streams the best AUDIO-ONLY stream of a YouTube
 *                            video directly to the client. Nothing is stored
 *                            on the server — bytes are piped through.
 *
 * Flow:
 *   1. yt-dlp (via youtube-dl-exec) resolves the video and picks the best
 *      audio-only format (M4A preferred, then Opus/WebM — highest bitrate).
 *      The binary is fetched at BUILD time (scripts/fetch-ytdlp.js, run by
 *      `npm install`), so a request never waits on a lazy binary download.
 *   2. Every extraction is served by the ANONYMOUS GUEST SESSION: yt-dlp is
 *      always passed a writable cookiejar (--cookies <tmpdir>/clearview-…)
 *      that it creates and reuses, so YouTube's own visitor cookies persist
 *      between calls instead of every request looking like a brand-new,
 *      unrelated client (which is what drew the bot check). NO account login
 *      is involved anywhere — the jar starts empty and yt-dlp populates it.
 *   3. Client chains (configurable via YTDLP_CLIENTS): the primary chain is
 *      `mweb,web` + fetch_pot=always + a PO token from the bundled provider
 *      (web_embedded is excluded — it returned LOGIN_REQUIRED in testing),
 *      then mobile innertube clients (direct signed URLs, no token needed),
 *      then the default chain.
 *   4. A global rate limiter (token bucket + FIFO queue) keeps extraction
 *      comfortably under YouTube's guest-session ceiling (~300 requests/hour)
 *      and serializes calls so the shared cookiejar has no concurrent write
 *      races. Requests past the budget queue; beyond the wait cap they get a
 *      503 + Retry-After + queue-position headers and the app retries.
 *   5. A small TTL cache of extracted stream URLs (keyed by video id) serves
 *      repeat downloads of the same video WITHOUT touching yt-dlp or the
 *      PO-token provider at all — the main lever for staying under the rate
 *      ceiling when many users grab the same popular RSS episodes.
 *   6. The chosen format's direct URL is piped back to the client with
 *      Content-Type / Content-Length when known.
 *   7. If every yt-dlp client fails, the server falls back to
 *      @distube/ytdl-core and pipes that stream; total failures return a
 *      clean, user-facing error (403 "requires sign-in" for private /
 *      age-restricted / members-only videos, 503 bot-check, …) plus a
 *      machine-readable X-Audio-Error-Code header.
 *
 * Deployment (Render free tier):
 *   - Root directory: backend
 *   - Build command:  npm install   (also fetches the yt-dlp binary)
 *   - Start command:  npm start
 *   - Env vars:       PORT (auto), AUDIO_TOKEN (optional shared secret),
 *                     YTDLP_CLIENTS, RATE_LIMIT_PER_HOUR, RATE_LIMIT_BURST,
 *                     STREAM_CACHE_TTL_MINUTES, PROXY, DEBUG_BOOT_CHECK,
 *                     YTDLP_AUTO_UPDATE — see README.md.
 *
 * Free-tier notes: instances sleep after ~15 min idle. The first request
 * wakes it (30–90 s); the app shows an animated "Preparing…" while waiting,
 * and the server answers 503 while the instance is still booting — the app
 * retries automatically. Requests are also capped to a few concurrent
 * streams to stay inside the free instance's RAM.
 */

const express = require('express');
const ytdl = require('@distube/ytdl-core');
// `args` is youtube-dl-exec's exported dargs builder — we use it to log the
// EXACT command line that gets sent to yt-dlp (the user-visible proof of what
// the integration passes, including the PO-token extractor args).
const { youtubeDl, create, args: buildArgs } = require('youtube-dl-exec');
const https = require('https');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const app = express();
app.disable('x-powered-by');

const PORT = process.env.PORT || 3000;
const TOKEN = process.env.AUDIO_TOKEN || '';
// Kept at 2 to leave headroom for the PO-token provider's BotGuard process
// on free-tier instances (512 MB).
const MAX_CONCURRENT = 2;
const INFO_CACHE_MS = 10 * 60 * 1000;

// ── Anonymous guest session (no login) ───────────────────────────────────
// YouTube treats requests that share visitor markers as ONE session; a fresh
// cookie-less client every call looks like an unrelated new client and gets
// challenged ("Sign in to confirm you're not a bot"). yt-dlp manages a guest
// session itself when handed a writable cookiejar: pass --cookies <file> and
// it persists YouTube's own visitor cookies (VISITOR_INFO1_LIVE, etc.) across
// runs. The file starts empty — yt-dlp populates it — and NO login/account
// cookies ever go in here. (Render's filesystem is ephemeral per instance, so
// os.tmpdir() is the right home.)
const GUEST_COOKIES_PATH = path.join(require('os').tmpdir(), 'clearview-guest-cookies.txt');

// ── Guest-session rate ceiling ───────────────────────────────────────────
// YouTube's documented guideline for a guest session is ~300 requests/hour
// per session/IP. A global token bucket (per-process = the only IP on Render's
// free tier) paces ALL extractions comfortably under that, and a FIFO queue
// serializes them — which also keeps the shared cookiejar free of concurrent
// read/modify/write races. Requests past the budget queue; beyond
// QUEUE_MAX_WAIT_MS they get a 503 + Retry-After + position info and the app
// retries automatically.
const RATE_LIMIT_PER_HOUR = Number(process.env.RATE_LIMIT_PER_HOUR || 250);
const RATE_LIMIT_BURST = Number(process.env.RATE_LIMIT_BURST || 5);
const QUEUE_MAX_WAIT_MS = Number(process.env.QUEUE_MAX_WAIT_MS || 12000);

// ── Extracted-stream cache ───────────────────────────────────────────────
// Repeat downloads of the same video (very common for popular RSS episodes)
// are served from here WITHOUT touching yt-dlp or the PO-token provider. The
// TTL matches how long YouTube's direct stream URLs stay valid (a few hours).
const STREAM_CACHE_TTL_MS = Number(process.env.STREAM_CACHE_TTL_MINUTES || 180) * 60 * 1000;
const STREAM_CACHE_MAX = Number(process.env.STREAM_CACHE_MAX_ENTRIES || 500);

// ── Client chain ─────────────────────────────────────────────────────────
// The primary no-login pairing. mweb,web is currently the recommended
// cookie-less client combination; web_embedded is excluded because it returns
// LOGIN_REQUIRED in testing. Override with YTDLP_CLIENTS when YouTube shifts
// its trust signals (e.g. YTDLP_CLIENTS=android_vr,ios,web_safari).
const CLIENT_LIST = process.env.YTDLP_CLIENTS || 'mweb,web';

// Single backoff retry across all chains on transient failures (bot checks /
// 429s / timeouts are often momentary).
const RETRY_BACKOFF_MS = Number(process.env.YTDLP_RETRY_BACKOFF_MS || 3000);

// Boot-check self-test: keep the yt-dlp extraction probes (they validate the
// real chain and warm the guest cookiejar), but let operators drop them so
// boot-time noise is never confused with real request failures in monitoring.
const DEBUG_BOOT_CHECK = process.env.DEBUG_BOOT_CHECK !== 'false';

// Optional HTTP(S) proxy escape hatch (--proxy), used ONLY when set — the
// guest session + cache are the primary defenses, not a proxy.
const PROXY_URL = process.env.PROXY || '';

// ── Pipeline building blocks (rate limiter, cache, classifiers) ──────────
const {
  createExtractionQueue,
  createStreamCache,
  classifyExtractionError,
  isTransientExtractionError,
} = require('./lib/extraction-pipeline');
const extractionQueue = createExtractionQueue({
  perHour: RATE_LIMIT_PER_HOUR,
  burst: RATE_LIMIT_BURST,
});
const streamCache = createStreamCache({
  ttlMs: STREAM_CACHE_TTL_MS,
  maxEntries: STREAM_CACHE_MAX,
});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Rejects [p] after [ms] — used to bound the ytdl-core fallback, which has no
// socket timeout of its own and now runs inside the single-flight queue (a
// hang there would stall every other request).
const withTimeout = (p, ms, msg) =>
  Promise.race([p, new Promise((_, reject) => setTimeout(() => reject(new Error(msg)), ms))]);

const UA =
  'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36';

// ── yt-dlp binary ────────────────────────────────────────────────────────
// Prefer the binary fetched at build time (scripts/fetch-ytdlp.js); if that
// is missing (e.g. GitHub was unreachable during install, or the build cache
// skipped the postinstall) fall back to youtube-dl-exec's lazy auto-download
// and kick a background fetch so the pinned binary is ready for later calls.
const BIN_NAME = process.platform === 'win32' ? 'yt-dlp.exe' : 'yt-dlp';
// YTDLP_BIN overrides the yt-dlp binary (used for testing / debugging).
const BIN_PATH = process.env.YTDLP_BIN || path.join(__dirname, 'bin', BIN_NAME);
// Only trust a non-trivial binary (>= 1 MB) — a truncated fetch must never
// be "used"; fall back to the lazy auto-download in that case. (yt-dlp's
// Linux asset is now a ~3 MB zipapp, so 5 MB would wrongly reject it.)
const pinnedOk =
  fs.existsSync(BIN_PATH) &&
  fs.statSync(BIN_PATH).size >= 1 * 1024 * 1024;
const ytDlp = pinnedOk ? create(BIN_PATH) : youtubeDl;
if (ytDlp === youtubeDl) {
  console.warn('[server] pinned yt-dlp binary missing or too small — using lazy auto-download');
  // Best effort: fetch the pinned binary in the background for later requests.
  try {
    const { spawn } = require('child_process');
    const child = spawn(process.execPath, [path.join(__dirname, 'scripts', 'fetch-ytdlp.js')], {
      stdio: 'ignore',
      detached: true,
    });
    child.unref();
  } catch (e) {
    console.warn('[server] could not start background binary fetch:', e.message);
  }
} else {
  console.log(`[server] using pinned yt-dlp binary at ${BIN_PATH}`);
}

// ── PO-token provider (bgutil-ytdlp-pot-provider) ─────────────────────────
// A local HTTP server that solves YouTube's BotGuard attestation on the
// server's own IP and hands yt-dlp a proof-of-origin (PO) token. This is what
// unblocks downloads from flagged datacenter IPs ("Sign in to confirm you're
// not a bot") where even signed-in cookies get challenged. Built at deploy
// time by scripts/fetch-pot-provider.js; spawned here as a child process.
const POT_PORT = Number(process.env.POT_PORT || 4416);
const PLUGINS_DIR = path.join(__dirname, 'plugins');
let potReady = false;
// Provider boot window: on Render's cold free tier the provider process takes
// 10–25 s to start listening. Requests inside that window must NOT attempt a
// tokenless download (it always bot-checks from the datacenter IP) — they get
// a 503 and the app retries a few seconds later. Crash-restarts are gated with
// a shorter window of their own; both ceilings keep the service from deadlocking.
const SERVER_START = Date.now();
const STARTUP_WINDOW_MS = 180 * 1000;
const RESTART_WINDOW_MS = 60 * 1000;
// Boot check runs AFTER the provider is ready: direct /get_pot probe (cold
// solve, up to 45 s) + yt-dlp probe (up to 60 s). Until it finishes, the
// provider may not yet have a warm minter under the key real requests use, so
// requests are 503-gated for this window too (time-capped like the others).
// NOTE: the plugin's solve timeout is patched to 45 s at build time
// (scripts/fetch-pot-provider.js), which is what makes 20-45 s cold solves
// survivable for real yt-dlp requests.
// Two sequential yt-dlp probes (primary mweb,web+PO, then mobile fallback)
// after the /get_pot probe: worst case ≈ 90 s ready-wait + 45 s /get_pot +
// 60 s + 60 s probes ≈ 255 s, so the gate stays aligned at ~280 s. It
// self-opens regardless (time cap), and the probes are skipped entirely when
// DEBUG_BOOT_CHECK=false (the /get_pot warmup always runs).
const BOOT_WINDOW_MS = 280 * 1000;
let potEverReady = false;
let potDownAt = null;
let potBootCheckDone = false;
const PROVIDER_BUILT = fs.existsSync(
  path.join(__dirname, 'pot-provider', 'server', 'build', 'main.js')
);
// Counts provider-side token generations (each "Generating POT" line logged
// by the child provider process = one real token generation). Lets the boot
// check and request logs definitively report whether the PO-token plugin
// actually REACHED the provider. Note: when an already-running provider is
// REUSED ("reusing already-running provider") this counter can't see its
// stdout and stays 0 — Render always spawns fresh, so it is accurate there.
let potActivity = 0;

function startPotProvider() {
  const main = path.join(__dirname, 'pot-provider', 'server', 'build', 'main.js');
  if (!fs.existsSync(main)) {
    console.warn('[pot] provider not built (scripts/fetch-pot-provider.js did not run or failed) — no PO tokens');
    return;
  }
  const { spawn } = require('child_process');
  const logChild = (prefix, d) => {
    const line = String(d).trim();
    if (line) {
      console.log(`${prefix} ${line.slice(0, 300)}`);
      if (/Generating POT/.test(line)) potActivity++;
    }
  };

  const MAX_RESTARTS = 5;
  let restarts = 0;

  const launch = () => {
    console.log(`[pot] starting provider (${main})`);
    const child = spawn(process.execPath, [main, '--port', String(POT_PORT)], {
      cwd: path.dirname(main),
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.on('data', (d) => logChild('[pot]', d));
    child.stderr.on('data', (d) => logChild('[pot]', d));
    child.on('error', (e) => {
      // A failed spawn without a listener would crash the whole server.
      potReady = false;
      console.warn(`[pot] provider spawn failed: ${e.message}`);
    });
    child.on('exit', (code, signal) => {
      potReady = false;
      potDownAt = Date.now();
      const willRestart = restarts < MAX_RESTARTS;
      console.warn(
        `[pot] provider exited (code ${code}${signal ? ', signal ' + signal : ''})` +
          (willRestart ? ` — restarting in ${(restarts + 1) * 5}s` : ' — giving up until next boot')
      );
      if (willRestart) {
        restarts++;
        // Backoff: 5s, 10s, 15s… — covers OOM-kills on free tier.
        setTimeout(() => {
          launch();
          probeUntilReady(60 * 1000);
        }, restarts * 5000);
      }
    });
  };

  const probeUntilReady = (deadlineMs) => {
    const deadline = Date.now() + deadlineMs;
    const tick = () => {
      const req = http.get({ host: '127.0.0.1', port: POT_PORT, path: '/' }, (res) => {
        res.resume();
        if (!potReady) {
          potReady = true;
          potEverReady = true;
          potDownAt = null;
          console.log(`[pot] PO-token provider ready on port ${POT_PORT}`);
        }
      });
      req.on('error', () => {
        if (Date.now() < deadline) setTimeout(tick, 1000);
      });
      req.setTimeout(2000, () => req.destroy());
    };
    tick();
  };

  // Reuse a provider left over from a previous local run; otherwise spawn one.
  const pre = http.get({ host: '127.0.0.1', port: POT_PORT, path: '/' }, (res) => {
    res.resume();
    potReady = true;
    potEverReady = true;
    potDownAt = null;
    console.log(`[pot] reusing already-running provider on port ${POT_PORT}`);
  });
  pre.on('error', () => {
    launch();
    probeUntilReady(60 * 1000);
  });
  pre.setTimeout(2000, () => pre.destroy());
}
startPotProvider();

function waitForPotReady(maxMs) {
  return new Promise((resolve) => {
    if (potReady) return resolve(true);
    const deadline = Date.now() + maxMs;
    const tick = () => {
      if (potReady) return resolve(true);
      if (Date.now() > deadline) return resolve(false);
      setTimeout(tick, 1000);
    };
    tick();
  });
}

// Boot-time self-check: run one real extraction with the plugin and confirm
// yt-dlp sees the bgutil PO-token provider (its verbose output lists it). This
// makes a wiring regression visible in the logs instead of silent bot-check 500s.
// IMPORTANT: it only runs AFTER the provider is actually listening — earlier
// deploys probed too early and logged bogus ECONNREFUSED verdicts.
function verifyPotWiring() {
  const { execFile } = require('child_process');
  const probeUrl = 'https://www.youtube.com/watch?v=jNQXAC9IVRw';

  // 1) Exact yt-dlp version — version drift between the local binary and the
  //    build-cached binary on Render has caused silent PO-token differences
  //    before; pin the comparison in the logs. (30 s timeout: the Linux
  //    zipapp's first run on a cold instance exceeds 10 s.)
  execFile(
    BIN_PATH,
    ['--version'],
    { timeout: 30000, encoding: 'utf8' },
    (err, stdout) => {
      const ver = String(stdout || (err && err.message) || '?').trim().slice(0, 40);
      console.log(`[boot-check] yt-dlp version: ${ver}`);
    }
  );

  // 2) Provider /ping from the server side — this is the EXACT endpoint the
  //    bgutil plugin probes before requesting a token (cached 60 s). Proves
  //    reachability independently of yt-dlp.
  const ping = http.get({ host: '127.0.0.1', port: POT_PORT, path: '/ping' }, (res) => {
    let body = '';
    res.on('data', (d) => (body += d));
    res.on('end', () => {
      console.log(`[boot-check] provider /ping -> HTTP ${res.statusCode} ${String(body).trim().slice(0, 80)}`);
    });
  });
  ping.on('error', (e) => console.warn(`[boot-check] provider /ping FAILED: ${e.message}`));
  ping.setTimeout(5000, () => ping.destroy());

  // 3) DIRECT /get_pot probe: asks the provider to solve BotGuard right now,
  // independent of yt-dlp. A 200 proves the provider can generate tokens on
  // THIS IP (the decisive test for Render's datacenter IP); a 500 pins the
  // failure on the provider's solve path rather than the yt-dlp plugin wiring.
  // The probe also acts as a WARMUP: it seeds the provider's session cache, so
  // the first real request doesn't pay the slow cold solve.
  // NOTE: the plugin's own solve timeout is patched to 45 s at build time
  // (scripts/fetch-pot-provider.js), so a cold solve up to ~40 s survives real
  // requests; the logged duration makes the verdict interpretable
  // ("200 in 2 s" = fine; "200 in 44 s" = right at the cap, real requests risk
  // timing out until the warm minter kicks in).
  // Guard: runYtDlpProbe (declared below) must run exactly once, after this
  // probe has completed (or failed/timeout) — never concurrently with it.
  let ytDlpProbeStarted = false;
  const probeStartedAt = Date.now();
  const getPotProbe = http.request(
    {
      host: '127.0.0.1',
      port: POT_PORT,
      path: '/get_pot',
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': 2 },
    },
    (res) => {
      let body = '';
      res.on('data', (d) => (body += d));
      res.on('end', () => {
        const ok = res.statusCode === 200;
        const ms = Date.now() - probeStartedAt;
        if (ok) {
          // The plugin's /get_pot solve timeout is patched to 45 s at build
          // time (scripts/fetch-pot-provider.js rebuilds the plugin zip with
          // _GETPOT_TIMEOUT = 45.0). The probe's own timeout is also 45 s, so
          // only solves above ~40 s are cutting it close.
          if (ms / 1000 > 40) {
            // The cold BotGuard solve is a one-time cost: the provider caches
            // the solved session (minter) for ~12h, and this probe IS the
            // warmup, so later /get_pot calls mint tokens fast. The warning
            // still matters — a solve this slow is right at the 45 s cap.
            console.warn(
              `[boot-check] direct /get_pot probe -> HTTP 200 in ${(ms / 1000).toFixed(1)}s — solve is very slow (near the 45s plugin/probe timeout); this probe WARMS the provider, so later token requests should be fast (see the yt-dlp probe verdicts below)`
            );
          } else {
            console.log(
              `[boot-check] direct /get_pot probe -> HTTP 200 in ${(ms / 1000).toFixed(1)}s — provider CAN solve BotGuard on this IP`
            );
          }
        } else {
          console.warn(
            `[boot-check] direct /get_pot probe -> HTTP ${res.statusCode} after ${(ms / 1000).toFixed(1)}s — ${String(body).slice(0, 200)} (provider solve is the suspect)`
          );
        }
        runYtDlpProbe();
      });
    }
  );
  getPotProbe.on('error', (e) => {
    // Probe failed, but the provider may still serve real requests — run the
    // yt-dlp probe anyway; its own verdicts show what actually happens.
    console.warn(`[boot-check] direct /get_pot probe FAILED: ${e.message}`);
    runYtDlpProbe();
  });
  getPotProbe.setTimeout(45000, () => {
    getPotProbe.destroy();
    console.warn('[boot-check] direct /get_pot probe timed out after 45s');
    runYtDlpProbe();
  });
  getPotProbe.write('{}');
  getPotProbe.end();

  // 4) Two yt-dlp extraction probes, run SEQUENTIALLY after the /get_pot probe
  //    above (runYtDlpProbe) so they benefit from its warm minter and the
  //    potActivity delta is unambiguous. Both use the EXACT same arguments as
  //    real requests — INCLUDING the guest cookiejar (the old boot check ran
  //    with no --cookies at all, which is precisely why it got bot-checked
  //    while real requests were fine) — and both are gated behind
  //    DEBUG_BOOT_CHECK so boot-time output is never mistaken for real request
  //    failures in monitoring.
  //    Probe A — the PRIMARY chain (mweb,web + PO token + guest session): what
  //      real requests try first. Also validates the bgutil provider wiring
  //      end-to-end and WARMS the guest cookiejar for the first real request.
  //    Probe B — the FALLBACK mobile chain (direct signed URLs, no PO token):
  //      proves the fallback still works even if the provider is unhappy.
  function runYtDlpProbe() {
    if (ytDlpProbeStarted) return;
    ytDlpProbeStarted = true;
    if (!DEBUG_BOOT_CHECK) {
      console.log('[boot-check] DEBUG_BOOT_CHECK=false — skipping the yt-dlp extraction probes (the provider /get_pot warmup above still ran)');
      potBootCheckDone = true;
      return;
    }

    // ---- Probe A: PRIMARY chain (guest cookiejar + CLIENT_LIST + PO token) ----
    // Uses cookiesPath (the guest jar by default, legacy account cookies if
    // explicitly configured) — exactly what real requests get.
    console.log(
      `[boot-check] probe A (PRIMARY ${CLIENT_LIST}+PO chain): ${BIN_PATH} --cookies ${cookiesPath} --plugin-dirs ${PLUGINS_DIR} --no-warnings --no-check-certificates --no-update --socket-timeout 20 ` +
      `--extractor-args "youtube:player_client=${CLIENT_LIST};fetch_pot=always" --extractor-args "youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}" --print title -v ${probeUrl}`
    );
    const before = potActivity;
    execFile(
      BIN_PATH,
      [
        '--cookies', cookiesPath,
        '--plugin-dirs', PLUGINS_DIR,
        '--no-warnings', '--no-check-certificates', '--no-update',
        '--socket-timeout', '20',
        // IMPORTANT: keep the plugin base_url in its OWN flag (different
        // extractor key: youtubepot-bgutilhttp:). player_client + fetch_pot must
        // share the youtube: flag (joined with ';') — a separate second
        // `youtube:` flag would silently override player_client (only the last
        // flag per extractor key survives), and a single flag mixing
        // "youtube:...;youtubepot-bgutilhttp:..." swallows the base_url
        // (yt-dlp parses ';' within one extractor key only; verified against
        // yt-dlp 2026.07.04 + plugin 1.3.1).
        '--extractor-args', `youtube:player_client=${CLIENT_LIST};fetch_pot=always`,
        '--extractor-args', `youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}`,
        '--print', 'title',
        '-v',
        probeUrl,
      ],
      { timeout: 60000, encoding: 'utf8' },
      (errA, stdoutA, stderrA) => {
        // Probe A is the one that WARMS the provider's minter under the real
        // cache key — open the 503 gate as soon as it finishes (probe B is
        // purely informational). Set first so a stray exception below can
        // never leave the gate closed past its time cap.
        potBootCheckDone = true;
        const outA = `${stdoutA || ''}\n${stderrA || ''}`;
        const titleA = String(stdoutA || '').trim();
        const potLines = potActivity - before;
        const httpProviderUsed = /\[pot:bgutil:http\]\s+Generating a .*PO Token for/.test(outA);
        const tokenRetrieved = /Retrieved a .*PO Token/.test(outA);
        if (!errA && titleA) {
          console.log(`[boot-check] PRIMARY chain (${CLIENT_LIST}+PO, guest session) extraction OK — "${titleA.slice(0, 60)}"`);
        } else {
          console.warn(
            `[boot-check] PRIMARY chain extraction FAILED (${String((errA && errA.message) || 'no title').slice(0, 120)}) — real requests will fall back to the mobile chain`
          );
        }
        // PO-pipeline verdict (the provider is only used by probe A). The
        // script-node / script-deno "Script path doesn't exist" lines are
        // EXPECTED noise from yt-dlp's availability checks and appear even in
        // fully working runs — they do NOT mean the HTTP provider was skipped.
        if (httpProviderUsed) {
          console.log('[boot-check] bgutil HTTP provider WAS USED to generate PO tokens');
        } else if (/bgutil:http/.test(outA)) {
          // NB: the plugin's registration line ("PO Token Providers: bgutil:http-1.3.1
          // (external), ...") always contains bgutil:http, so this branch means the
          // plugin loaded but no token generation was observed — not that it was skipped.
          console.warn('[boot-check] bgutil HTTP provider loaded but NO token generation was observed during probe A');
        } else {
          console.warn('[boot-check] bgutil NOT detected in yt-dlp verbose output — PO tokens will not be attached');
        }
        console.log(
          potLines > 0
            ? `[boot-check] provider GENERATED ${potLines} token generation(s) during probe A — PO pipeline works end-to-end`
            : '[boot-check] provider saw NO token request during probe A — the plugin did not reach it (or the token was served from the provider cache)'
        );
        if (tokenRetrieved) {
          console.log('[boot-check] yt-dlp RETRIEVED at least one PO token from the provider');
        }
        if (errA && !tokenRetrieved) {
          console.warn(`[boot-check] probe A extraction failed (${String((errA && errA.message) || errA).slice(0, 120)})`);
        }
        // When probe A did NOT use the HTTP provider, dump its verbose output
        // tail — the decisive evidence for WHY (e.g. HTTP 403 on the webpage,
        // plugin "Error reaching GET .../ping", "failed to get token", or a
        // "Sign in to confirm you're not a bot" page from the datacenter IP).
        if (!httpProviderUsed || errA) {
          const probeLines = String(outA).trim().split(/\r?\n/).filter(Boolean);
          console.log(`[boot-check] probe A output tail (last ${Math.min(15, probeLines.length)} of ${probeLines.length} lines):`);
          for (const line of probeLines.slice(-15)) {
            console.log(`[boot-check]   | ${line.slice(0, 220)}`);
          }
        }
        runMobileProbe();
      }
    );

    // ---- Probe B: FALLBACK mobile chain (direct URLs, no PO token) ----
    function runMobileProbe() {
      // Throwaway cookiejar of its own: probe B only VALIDATES the mobile chain
      // (probe A already warmed the real guest jar), and since the gate opens
      // after probe A, its yt-dlp run must never race real requests writing
      // the shared jar.
      const probeBPath = path.join(require('os').tmpdir(), 'clearview-probe-b-cookies.txt');
      try {
        if (!fs.existsSync(probeBPath)) fs.writeFileSync(probeBPath, '# Netscape HTTP Cookie File\n');
      } catch (_) { /* best effort */ }
      const MOBILE_ARGS = 'youtube:player_client=android_vr,android,ios,web_safari,web_music';
      console.log(`[boot-check] probe B (FALLBACK mobile chain): ${BIN_PATH} --cookies ${probeBPath} --no-warnings --no-check-certificates --no-update --socket-timeout 20 --extractor-args "${MOBILE_ARGS}" --print title ${probeUrl}`);
      execFile(
        BIN_PATH,
        [
          '--cookies', probeBPath,
          '--no-warnings', '--no-check-certificates', '--no-update',
          '--socket-timeout', '20',
          '--extractor-args', MOBILE_ARGS,
          '--print', 'title',
          probeUrl,
        ],
        { timeout: 60000, encoding: 'utf8' },
        (errB, stdoutB) => {
          // Idempotent with probe A — belt and braces.
          potBootCheckDone = true;
          const titleB = String(stdoutB || '').trim();
          if (!errB && titleB) {
            console.log(`[boot-check] FALLBACK mobile chain extraction OK — "${titleB.slice(0, 60)}" (direct URLs, no PO token needed)`);
          } else {
            console.warn(`[boot-check] FALLBACK mobile chain extraction FAILED (${String((errB && errB.message) || 'no title').slice(0, 120)})`);
          }
        }
      );
    }
  }
}

async function runBootCheck() {
  const ready = await waitForPotReady(90 * 1000);
  if (!ready) {
    console.warn('[boot-check] boot check skipped: provider did not become ready within 90 s');
    // Open the request gate so users aren't 503'd for the whole boot window on
    // top of the startup window when there is no provider to warm anyway.
    potBootCheckDone = true;
    return;
  }
  verifyPotWiring();
}
runBootCheck();

// ── YouTube session identity ─────────────────────────────────────────────
// DEFAULT: the anonymous guest cookiejar (GUEST_COOKIES_PATH above). yt-dlp
// is ALWAYS passed --cookies <path> — an empty jar it creates and reuses — so
// YouTube's own visitor markers persist between calls. This is the fix for
// the old cookie-less pattern: running with NO --cookies at all made every
// request look like a brand-new client and is exactly what drew the
// "Sign in to confirm you're not a bot" check.
//
// LEGACY OPT-IN (deprecated): COOKIES_B64 (base64 of a Netscape cookies.txt
// exported from a signed-in browser) or a cookies.txt next to server.js still
// work for backward compatibility, but they are ACCOUNT cookies — they
// expire, need re-exporting, and are NOT required anymore. Prefer removing
// them and letting the guest session do its job.
const COOKIES_FILE = path.join(__dirname, 'cookies.txt');
let cookiesPath = GUEST_COOKIES_PATH;
let legacyAccountCookies = false;
try {
  // Seed an empty Netscape-format jar so yt-dlp always has a valid file to
  // load and save back to, even before its first extraction.
  if (!fs.existsSync(GUEST_COOKIES_PATH)) {
    fs.writeFileSync(GUEST_COOKIES_PATH, '# Netscape HTTP Cookie File\n');
  }
  if (process.env.COOKIES_B64) {
    const tmp = path.join(require('os').tmpdir(), 'clearview-account-cookies.txt');
    fs.writeFileSync(tmp, Buffer.from(process.env.COOKIES_B64, 'base64').toString('utf8'));
    try { fs.chmodSync(tmp, 0o600); } catch (_) { /* best effort */ }
    cookiesPath = tmp;
    legacyAccountCookies = true;
    console.warn(
      '[server] DEPRECATED: COOKIES_B64 account cookies configured. The guest-session ' +
        'cookiejar is the supported no-login path; account cookies are kept only for ' +
        'backward compatibility (and still pair fine with PO tokens).'
    );
  } else if (fs.existsSync(COOKIES_FILE)) {
    cookiesPath = COOKIES_FILE;
    legacyAccountCookies = true;
    console.warn(
      '[server] DEPRECATED: cookies.txt found. The guest-session cookiejar is the ' +
        'supported no-login path; account cookies are kept only for backward compatibility.'
    );
  } else {
    console.log(`[server] using anonymous guest cookiejar at ${GUEST_COOKIES_PATH} (no login needed)`);
  }
} catch (e) {
  console.warn('[server] could not prepare session cookies, using the guest cookiejar:', e.message);
  cookiesPath = GUEST_COOKIES_PATH;
}

// Diagnostics only for the legacy ACCOUNT cookies (the guest jar is empty by
// design — a "no signed-in markers" warning there would be meaningless).
if (legacyAccountCookies) {
  try {
    const raw = fs.readFileSync(cookiesPath, 'utf8');
    const lines = raw.split(/\r?\n/).filter((l) => l && !l.trimStart().startsWith('#'));
    const names = new Set(
      lines.map((l) => (l.split('\t')[5] || '').trim()).filter(Boolean)
    );
    const markers = ['__Secure-3PSID', '__Secure-1PSID', 'SAPISID', 'LOGIN_INFO'];
    const present = markers.filter((k) => names.has(k));
    console.log(
      `[server] cookies: ${lines.length} entries, signed-in markers: [${present.join(', ') || 'NONE'}]`
    );
    if (!present.length) {
      console.warn(
        '[server] WARNING: no signed-in cookies found — YouTube may still bot-block ' +
          'the server. Re-export cookies from a SIGNED-IN YouTube session and update ' +
          'COOKIES_B64, or remove COOKIES_B64 and rely on the guest session + PO token.'
      );
    }
  } catch (e) {
    console.warn('[server] could not inspect cookies:', e.message);
  }
}

let activeStreams = 0;

// ── Video id ────────────────────────────────────────────────────────────

const VIDEO_ID_RE =
  /(?:youtube\.com\/(?:watch\?(?:.*&)?v=|shorts\/|embed\/|live\/)|youtu\.be\/)([A-Za-z0-9_-]{11})/;

function videoIdFromUrl(url) {
  const m = String(url || '').match(VIDEO_ID_RE);
  return m ? m[1] : null;
}

// ── Format helpers ───────────────────────────────────────────────────────

function extOfFormat(format) {
  const mime = format.mimeType || '';
  if (mime.includes('mp4') || mime.includes('m4a') || format.container === 'm4a') return 'm4a';
  if (mime.includes('webm') || mime.includes('opus') || format.container === 'webm') return 'webm';
  return format.container || 'm4a';
}

function mimeForExt(ext) {
  const mimes = {
    m4a: 'audio/mp4',
    mp4: 'audio/mp4',
    webm: 'audio/webm',
    ogg: 'audio/ogg',
    opus: 'audio/ogg',
    mp3: 'audio/mpeg',
    aac: 'audio/aac',
  };
  return mimes[String(ext).toLowerCase()] || 'audio/mpeg';
}

// Pipes `directUrl` to the client with sensible audio headers.
function pipeDirect(url, videoId, ext, size, req, res, source) {
  res.status(200);
  res.set('Content-Type', mimeForExt(ext));
  if (size) res.set('Content-Length', size);
  res.set('Accept-Ranges', 'bytes');
  res.set('Content-Disposition', `inline; filename="${videoId}.${ext}"`);
  res.set('X-Audio-Source', source);

  const transport = url.startsWith('https') ? https : http;
  const upstream = transport.get(
    url,
    { headers: { 'User-Agent': UA, Referer: 'https://www.youtube.com/' } },
    (stream) => {
      stream.on('error', (err) => {
        console.error(`[${source}] upstream error`, err.message);
        if (!res.headersSent) res.status(502).json({ error: 'Upstream error' });
        else res.destroy();
      });
      stream.pipe(res);
    }
  );
  upstream.on('error', (err) => {
    console.error(`[${source}] request error`, err.message);
    if (!res.headersSent) res.status(502).json({ error: 'Upstream error' });
  });
  req.on('close', () => upstream.destroy());
}

// ── yt-dlp (primary) ─────────────────────────────────────────────────────

// Client strategies. On datacenter IPs YouTube bot-blocks requests that look
// like a brand-new cookie-less client ("Sign in to confirm you're not a bot").
// Two things fix that: the persistent guest cookiejar (passed on EVERY chain)
// and a PO token from the bundled provider. Chains, in order:
//   - PRIMARY: mweb,web + fetch_pot=always + PO token. mweb,web is currently
//     the recommended no-login client pairing (configurable via YTDLP_CLIENTS);
//     web_embedded is excluded — it returned LOGIN_REQUIRED in testing.
//     fetch_pot=always is REQUIRED: it forces yt-dlp to mint a PLAYER PO token
//     and attach it to the player API request itself before it is sent.
//     Without it, yt-dlp only fetches the GVS token lazily AFTER a successful
//     player response — so if the tokenless player request is bot-blocked,
//     formats are never processed and the provider is NEVER asked for a token.
//     NB: player_client and fetch_pot must share the SAME `youtube:` flag
//     (joined with ';') and the plugin base_url its OWN `youtubepot-…:` flag —
//     a second `youtube:` flag would override the first (only the last flag
//     per extractor key survives).
//   - FALLBACK: mobile innertube clients (android_vr/android/ios/web_safari) —
//     direct signed URLs, no PO token, no provider round-trip; fetch_pot is
//     intentionally NOT set (these clients aren't in the plugin's WEBPO_CLIENTS,
//     so a token fetch would just add latency).
//   - DEFAULT: yt-dlp's own default client set.
// Each entry is tried until one returns a playable URL.
function clientChains() {
  const poBaseUrl = `youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}`;
  if (potReady) {
    return [
      {
        label: `primary (${CLIENT_LIST} + PO token)`,
        extractorArgs: [`youtube:player_client=${CLIENT_LIST};fetch_pot=always`, poBaseUrl],
        pluginDirs: PLUGINS_DIR,
      },
      {
        label: 'mobile (android_vr,android,ios,web_safari,web_music)',
        extractorArgs: 'youtube:player_client=android_vr,android,ios,web_safari,web_music',
      },
      { label: 'default,-web', extractorArgs: 'youtube:player_client=default,-web' },
    ];
  }
  // Provider down (no PO tokens): mobile first (direct URLs), then defaults.
  return [
    {
      label: 'mobile (no provider)',
      extractorArgs: 'youtube:player_client=android_vr,android,ios,web_safari,web_music',
    },
    { label: 'default,-web', extractorArgs: 'youtube:player_client=default,-web' },
    { label: 'yt-dlp default', extractorArgs: null },
  ];
}

/**
 * Extracts the best audio stream for [url] with yt-dlp, walking every client
 * chain and retrying the whole set once (after RETRY_BACKOFF_MS) when the
 * failure is transient (bot check / 429 / timeout). Runs INSIDE the extraction
 * queue (single-flight + rate paced), so the shared guest cookiejar is never
 * written by two processes at once. Returns { url, ext, size, source }.
 * When yt-dlp is exhausted it falls back to ytdl-core; a video that needs
 * sign-in throws the classified 403 verdict instead of burning the fallback.
 */
async function extractWithYtDlp(url, videoId) {
  let lastError = null;
  for (let pass = 0; pass < 2; pass++) {
    const chains = clientChains();
    // Any chain failing transiently (bot check / 429 / timeout) earns the
    // backoff retry — not just the LAST chain's error, which could be a
    // different, non-transient failure.
    let anyTransient = false;
    for (let idx = 0; idx < chains.length; idx++) {
      const chain = chains[idx];
      const opts = {
        dumpSingleJson: true,
        format: 'bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio',
        noPlaylist: true,
        noWarnings: true,
        noCheckCertificates: true,
        noUpdate: true,
        // Bound each attempt so a hung YouTube response can't hold the queue
        // (or an activeStreams slot) for the full app read timeout.
        socketTimeout: 30,
        // The guest cookiejar goes on EVERY chain — it IS the anonymous
        // session, and it pairs fine with PO tokens.
        cookies: cookiesPath,
      };
      if (chain.extractorArgs) opts.extractorArgs = chain.extractorArgs;
      if (chain.pluginDirs) opts.pluginDirs = chain.pluginDirs;
      if (PROXY_URL) opts.proxy = PROXY_URL;
      const isPoChain = !!chain.pluginDirs;

      // DEBUG instrumentation: print the EXACT command line youtube-dl-exec
      // will spawn for the first chain, and run it with --verbose so its debug
      // output shows whether the bgutil provider is registered, contacted, and
      // returning a token (look for "PO Token Providers:", "Getting POT",
      // "Generating POT", and the provider's own [pot] lines in the server log).
      if (idx === 0) {
        try {
          const argv = [url].concat(buildArgs(opts));
          console.log(`[yt-dlp] FULL COMMAND: ${BIN_PATH} ${argv.join(' ')}`);
        } catch (e) {
          console.warn(`[yt-dlp] could not build command line: ${e.message}`);
        }
        if (isPoChain) opts.verbose = true;
      }

      try {
        const info = await ytDlp(url, opts);
        const direct = info.url;
        if (!direct) throw new Error('yt-dlp returned no stream url');
        return {
          url: direct,
          ext: info.ext || 'm4a',
          size: info.filesize || info.filesize_approx || 0,
          source: 'yt-dlp',
        };
      } catch (e) {
        lastError = e;
        if (isTransientExtractionError(e)) anyTransient = true;
        // Log the full stderr — POT-plugin warnings ("failed to get token",
        // etc.) live there but never reach the thrown message. The real error
        // is at the END of stderr (the --verbose debug header eats the first
        // ~1.5 KB), so log the TAIL, not the head.
        const detail = String(e.stderr || e.message || e).slice(-600);
        console.warn(`[yt-dlp] chain "${chain.label || 'default'}" failed: ${detail}`);
        if (e && e.stderr) {
          console.warn(`[yt-dlp] chain "${chain.label || 'default'}" STDERR TAIL:\n${String(e.stderr).slice(-2000)}`);
        }
      }
    }
    // One backoff retry across the whole chain set, only for transient
    // failures (bot checks / 429s / timeouts are often momentary). Videos that
    // hard-require login are not retried — no point.
    if (pass === 0 && lastError && anyTransient) {
      console.warn(
        `[yt-dlp] transient failure (${String((lastError && lastError.message) || lastError).slice(0, 120)}) — retrying all chains once in ${(RETRY_BACKOFF_MS / 1000).toFixed(0)}s`
      );
      await sleep(RETRY_BACKOFF_MS);
      continue;
    }
    break;
  }
  if (potReady) {
    console.log(`[pot] request activity: ${potActivity} token generation(s) observed across the yt-dlp attempts`);
  }

  // yt-dlp exhausted → ytdl-core fallback. Skipped entirely for videos that
  // need sign-in: the fallback can't get them either, and burning another
  // request (and rate budget) just delays a clear answer.
  const verdict = classifyExtractionError(lastError);
  if (verdict.code === 'login') throw verdict;
  console.warn('[yt-dlp] failed, falling back to ytdl-core:', String((lastError && lastError.message) || lastError).slice(0, 150));
  try {
    // Timeout-bounded: getInfoCached has no timeout of its own and the queue
    // is single-flight, so a hang would block every other request.
    const info = await withTimeout(getInfoCached(url), 30000, 'ytdl-core fallback timed out');
    const format = chooseAudioFormat(info.formats);
    return {
      url: format.url,
      ext: extOfFormat(format),
      size: format.contentLength || 0,
      source: 'ytdl-core',
    };
  } catch (err2) {
    console.error('[ytdl-core] fallback failed:', err2.message);
    throw classifyExtractionError(err2);
  }
}

// ── ytdl-core (fallback) ────────────────────────────────────────────────

/** Best audio-only format: M4A preferred, then Opus/WebM; highest bitrate. */
function chooseAudioFormat(formats) {
  const audio = formats.filter(
    (f) => f.hasAudio && !f.hasVideo && f.url && f.url.startsWith('http')
  );
  if (!audio.length) throw new Error('No audio-only formats available');
  const score = (f) => (f.container === 'm4a' ? 2 : f.container === 'webm' ? 1 : 0);
  return audio
    .slice()
    .sort(
      (a, b) => score(b) - score(a) || (b.audioBitrate || 0) - (a.audioBitrate || 0)
    )[0];
}

const infoCache = new Map();

async function getInfoCached(url) {
  const id = videoIdFromUrl(url);
  const hit = id && infoCache.get(id);
  if (hit && Date.now() - hit.at < INFO_CACHE_MS) return hit.info;
  let info;
  try {
    info = await ytdl.getInfo(url, {
      playerClients: ['WEB_EMBEDDED', 'ANDROID', 'IOS'],
      requestOptions: { headers: { 'User-Agent': UA } },
    });
  } catch (e) {
    info = await ytdl.getInfo(url, {
      requestOptions: { headers: { 'User-Agent': UA } },
    });
  }
  if (id) {
    if (infoCache.size > 200) infoCache.clear();
    infoCache.set(id, { info, at: Date.now() });
  }
  return info;
}

// (The ytdl-core FALLBACK now lives inside extractWithYtDlp — it resolves the
// best format and pipes format.url directly, same queue slot, no extra code
// path. getInfoCached / chooseAudioFormat / extOfFormat are still used there.)

// ── Routes ──────────────────────────────────────────────────────────────

app.get('/health', (req, res) => {
  // Surface queue/cache/pot state so dashboards can alert on sustained
  // rate-limit pressure (queueDepth) or a dead provider (potReady=false).
  res.json({
    ok: true,
    service: 'clearview-audio',
    time: Date.now(),
    queueDepth: extractionQueue.depth(),
    cacheEntries: streamCache.size(),
    potReady,
    potActivity,
  });
});

app.get('/', (req, res) => {
  res.json({ ok: true, service: 'clearview-audio', usage: 'GET /api/audio?url=<youtube-url>' });
});

app.get('/api/audio', async (req, res) => {
  const url = String(req.query.url || '').trim();
  const videoId = videoIdFromUrl(url);
  if (!videoId) return res.status(400).json({ error: 'Not a valid YouTube URL' });

  // Optional shared secret (AUDIO_TOKEN env). Protects your free-tier
  // bandwidth from strangers.
  if (TOKEN) {
    const given = String(req.get('X-Audio-Token') || req.query.token || '');
    // timingSafeEqual throws on length mismatch — compare lengths first.
    const ok =
      given.length > 0 &&
      given.length === TOKEN.length &&
      crypto.timingSafeEqual(Buffer.from(given), Buffer.from(TOKEN));
    if (!ok) return res.status(401).json({ error: 'Unauthorized' });
  }

  // During the provider's startup window (or a crash-restart), answer 503 so
  // the app retries in a few seconds instead of burning a doomed tokenless
  // attempt (which always bot-checks on Render's datacenter IP). Both windows
  // are time-capped so the service can never deadlock — after them the server
  // falls back to the non-PO chains.
  const inStartupWindow = Date.now() - SERVER_START < STARTUP_WINDOW_MS;
  const inRestartWindow = potEverReady && potDownAt !== null && Date.now() - potDownAt < RESTART_WINDOW_MS;
  if (PROVIDER_BUILT && !potReady && (inStartupWindow || inRestartWindow)) {
    return res.status(503).json({ error: 'Audio service is warming up — retry in a moment' });
  }
  // Provider is up, but the boot check hasn't finished yet: its yt-dlp probe is
  // what warms the provider's minter under the SAME cache key real requests
  // use. A request now could pay the slow cold BotGuard solve (20-45 s on
  // Render) and hit the plugin's solve timeout — 503 instead.
  const inBootCheckWindow = !potBootCheckDone && Date.now() - SERVER_START < BOOT_WINDOW_MS;
  if (PROVIDER_BUILT && inBootCheckWindow) {
    return res.status(503).json({ error: 'Audio service is warming up — PO-token boot check in progress, retry in a moment' });
  }

  if (activeStreams >= MAX_CONCURRENT) {
    return res.status(503).json({ error: 'Server is busy — retry in a moment' });
  }
  activeStreams++;
  let finished = false;
  const release = () => {
    if (!finished) {
      finished = true;
      activeStreams--;
    }
  };
  req.on('close', release);

  try {
    // 1) Cache hit → serve WITHOUT touching yt-dlp or the PO-token provider.
    //    This is the main lever for staying under the guest-session rate
    //    ceiling when many users download the same popular RSS episodes.
    const cached = streamCache.get(videoId);
    if (cached) {
      console.log(`[cache] HIT ${videoId} (source ${cached.source}) — piping cached stream URL, no extraction`);
      pipeDirect(cached.url, videoId, cached.ext, cached.size, req, res, cached.source === 'ytdl-core' ? 'ytdl-core-cache' : 'yt-dlp-cache');
      return;
    }

    // 2) Extraction — single-flight + rate paced by the global queue. If the
    //    queue is backed up past QUEUE_MAX_WAIT_MS, answer 503 with Retry-After
    //    and the user's queue position so the app can retry (it does so
    //    automatically) instead of piling more work onto YouTube.
    let stream;
    try {
      stream = await new Promise((resolve, reject) => {
        const handle = extractionQueue.push(() => extractWithYtDlp(url, videoId));
        const timer = setTimeout(() => {
          // Only shed load while the job is still QUEUED. Once it has started,
          // let this client ride out its own extraction (bounded by the per-
          // chain socket timeouts + the app's 180 s read timeout): cancelling
          // an in-flight job would throw away the work AND a rate-bucket token
          // on a client we already told to retry.
          if (!handle.started()) {
            handle.cancel();
            reject(Object.assign(new Error('queue-wait-limit'), { code: 'QUEUE_TIMEOUT', position: handle.position() }));
          }
        }, QUEUE_MAX_WAIT_MS);
        handle.promise.then(
          (v) => { clearTimeout(timer); resolve(v); },
          (e) => { clearTimeout(timer); reject(e); }
        );
      });
    } catch (err) {
      if (err && err.code === 'QUEUE_TIMEOUT') {
        const position = err.position || 1;
        console.warn(`[rate-limit] queue full — 503 for ${videoId} (position #${position})`);
        res.status(503);
        res.set('Retry-After', '5');
        res.set('X-Audio-Queue-Position', String(position));
        res.set('X-Audio-Queue-Wait-Ms', String(extractionQueue.estimateWaitMs(position)));
        res.set('X-Audio-Error-Code', 'queue');
        res.json({ error: `The audio server is busy — you're #${position} in the queue. Retrying automatically…` });
        return;
      }
      throw err;
    }

    // 3) Cache the fresh extraction, then pipe the direct stream URL.
    streamCache.put(videoId, stream);
    pipeDirect(stream.url, videoId, stream.ext, stream.size, req, res, stream.source);
  } catch (err) {
    // Total failure. extractWithYtDlp throws already-classified verdict objects
    // ({ status, code, message }) for login/botcheck/transient — pass them
    // through verbatim; anything else gets classified here.
    const verdict = err && err.status ? err : classifyExtractionError(err);
    console.error(
      `[extract] FAIL ${videoId}: code=${verdict.code} status=${verdict.status} (${String((err && err.message) || err).slice(0, 150)})`
    );
    if (!res.headersSent) {
      res.set('X-Audio-Error-Code', verdict.code);
      // 403 (login-required) is NOT retried by the app — shown to the user
      // immediately with a clear "needs sign-in" message. 503/502/500 are
      // retried automatically. Keep the body short (app caps at 300 chars).
      res.status(verdict.status).json({ error: verdict.message });
    }
  }
});

// ── yt-dlp auto-update ───────────────────────────────────────────────────
// YouTube extractor breakage is frequent and version lag is a common cause of
// sudden download failures. On a schedule (and ~5 min after boot — on Render's
// sleeping free tier that is effectively the only chance to run) spawn
// scripts/update-ytdlp.js: it checks GitHub for a newer release and atomically
// swaps the pinned binary. Disable with YTDLP_AUTO_UPDATE=false. The script
// itself gates on bin/.last-ytdlp-update-check so a frequently cold-starting
// instance does not hammer the GitHub API.
function scheduleYtDlpUpdate() {
  if (process.env.YTDLP_AUTO_UPDATE === 'false') {
    console.log('[update] yt-dlp auto-update disabled (YTDLP_AUTO_UPDATE=false)');
    return;
  }
  const { spawn } = require('child_process');
  const script = path.join(__dirname, 'scripts', 'update-ytdlp.js');
  const run = () => {
    try {
      console.log('[update] checking for a new yt-dlp release...');
      const child = spawn(process.execPath, [script], { stdio: 'inherit', detached: true });
      child.unref();
    } catch (e) {
      console.warn('[update] could not start yt-dlp update check:', e.message);
    }
  };
  setTimeout(run, 5 * 60 * 1000); // soon after boot (free tier may sleep before the interval)
  const hours = Math.max(1, Number(process.env.YTDLP_UPDATE_CHECK_HOURS || 24));
  setInterval(run, hours * 3600 * 1000);
}
scheduleYtDlpUpdate();

// Keep the process alive on unhandled rejections from the stream glue.
process.on('unhandledRejection', (err) => {
  console.error('unhandled rejection', err && err.message);
});

app.listen(PORT, () => {
  console.log(`clearview-audio backend listening on port ${PORT}`);
  if (fs.existsSync('./cookies.json')) {
    console.log('cookies.json found — not used automatically; set COOKIES_FILE to enable');
  }
});
