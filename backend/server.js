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
 *   3. Client chains (configurable via YTDLP_CLIENTS / YTDLP_MOBILE_CLIENTS /
 *      YTDLP_TV_CLIENTS): the primary chain is `mweb,web` + fetch_pot=always +
 *      a PO token from the bundled provider (web_embedded is excluded — it
 *      returned LOGIN_REQUIRED in testing), then mobile innertube clients
 *      (direct signed URLs + GVS PO token), then the tv/tv_embedded family,
 *      then the default chain. The web/PO chain is ADAPTIVELY DEMOTED to the
 *      end after repeated login/botcheck verdicts (a flagged datacenter IP)
 *      so it never stalls every request; the cooldown re-tests it.
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

// ── Per-request logging ───────────────────────────────────────────────────
// Every request (including early-return paths: 400 / 401 / silent warm-up
// 503s) is logged with status + duration, so Render's logs always show when
// the app actually reaches the server. Without this, a download tap that gets
// a 503 while the instance warms up looks exactly like "no logs at all" — the
// request never arriving.
app.use((req, res, next) => {
  const startedAt = Date.now();
  res.on('finish', () => {
    const ms = Date.now() - startedAt;
    const target = String(req.originalUrl || req.url || '').slice(0, 160);
    console.log(`[req] ${req.method} ${target} -> ${res.statusCode} in ${ms}ms`);
  });
  next();
});

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
// Fallback client lists (configurable for when YouTube shifts its trust
// signals). MOBILE: innertube clients that serve direct signed URLs and now
// also accept GVS PO tokens. TV: the tv/tv_embedded client family, a separate
// trust path that historically survives datacenter-IP blocks.
const MOBILE_CLIENT_LIST =
  process.env.YTDLP_MOBILE_CLIENTS || 'android_vr,android,ios,web_safari,web_music';
const TV_CLIENT_LIST = process.env.YTDLP_TV_CLIENTS || 'tv_embedded,tv';
// Adaptive web/PO-chain demotion: on datacenter IPs the mweb,web pairing can
// be permanently bot-blocked (LOGIN_REQUIRED even with a valid PO token). After
// PO_CHAIN_DEMOTE_STREAK consecutive login/botcheck verdicts the web chain is
// demoted to the END of the rotation for PO_CHAIN_DEMOTE_MS, so real requests
// stop burning 5-30 s on it before reaching a working fallback. It is NEVER
// removed: the cooldown re-tests it, and a success re-promotes it immediately.
const PO_CHAIN_DEMOTE_STREAK = Number(process.env.YTDLP_PO_DEMOTE_STREAK || 3);
const PO_CHAIN_DEMOTE_MS =
  Number(process.env.YTDLP_PO_DEMOTE_MINUTES || 30) * 60 * 1000;
let poChainFailStreak = 0;
let poChainDemotedAt = 0; // epoch ms; 0 = not demoted

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
// solve, up to 70 s) + yt-dlp probes across every BOOT_CHECK_VIDEOS entry
// (probe A up to 60 s each, then probe B). Until the FIRST probe A finishes
// (which warms the provider's minter + guest jar under the real keys),
// requests are 503-gated for this window too (time-capped like the others).
// NOTE: the plugin's solve timeout is patched to 70 s at build time
// (scripts/fetch-pot-provider.js), which is what makes 20-70 s cold solves
// survivable for real yt-dlp requests.
// Worst case to the gate opening ≈ 90 s ready-wait + 70 s /get_pot + 120 s
// first probe A ≈ 280 s, so 300 s keeps the gate aligned. The remaining
// videos' probes run in the BACKGROUND after the gate opens (throwaway jars —
// never racing real requests on the shared guest jar). The gate self-opens
// regardless (time cap), and the probes are skipped entirely when
// DEBUG_BOOT_CHECK=false (the /get_pot warmup always runs).
const BOOT_WINDOW_MS = 300 * 1000;
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

// Boot-time self-check: run real extractions (one per BOOT_CHECK_VIDEOS entry)
// with the plugin and confirm yt-dlp sees the bgutil PO-token provider (its
// verbose output lists it). This makes a wiring regression visible in the logs
// instead of silent bot-check 500s. IMPORTANT: it only runs AFTER the provider
// is actually listening — earlier deploys probed too early and logged bogus
// ECONNREFUSED verdicts.
function verifyPotWiring() {
  const { execFile } = require('child_process');
  // Probe video set lives in runYtDlpProbe (BOOT_CHECK_VIDEOS override + the
  // default canary/ordinary/real-feed mix) — see below.

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
  // NOTE: the plugin's own solve timeout is patched to 70 s at build time
  // (scripts/fetch-pot-provider.js), so a cold solve up to ~65 s survives real
  // requests; the logged duration makes the verdict interpretable
  // ("200 in 2 s" = fine; "200 in 68 s" = right at the cap, real requests risk
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
          // The plugin's /get_pot solve timeout is patched to 70 s at build
          // time (scripts/fetch-pot-provider.js rebuilds the plugin zip with
          // _GETPOT_TIMEOUT = 70.0). The probe's own timeout is also 70 s, so
          // only solves above ~65 s are cutting it close.
          if (ms / 1000 > 65) {
            // The cold BotGuard solve is a one-time cost: the provider caches
            // the solved session (minter) for ~12h, and this probe IS the
            // warmup, so later /get_pot calls mint tokens fast. The warning
            // still matters — a solve this slow is right at the 70 s cap.
            console.warn(
              `[boot-check] direct /get_pot probe -> HTTP 200 in ${(ms / 1000).toFixed(1)}s — solve is very slow (near the 70s plugin/probe timeout); this probe WARMS the provider, so later token requests should be fast (see the yt-dlp probe verdicts below)`
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
  getPotProbe.setTimeout(70000, () => {
    getPotProbe.destroy();
    console.warn('[boot-check] direct /get_pot probe timed out after 70s');
    runYtDlpProbe();
  });
  getPotProbe.write('{}');
  getPotProbe.end();

  // 4) yt-dlp extraction probes, run SEQUENTIALLY after the /get_pot probe
  //    (runYtDlpProbe) so they benefit from its warm minter and the potActivity
  //    delta is unambiguous. They probe SEVERAL videos — not just the classic
  //    jNQXAC9IVRw canary, which is hammered by CI/automation traffic and must
  //    not be the only signal. The set is BOOT_CHECK_VIDEOS (comma-separated
  //    URLs or bare 11-char ids; defaults below) and every probe is gated
  //    behind DEBUG_BOOT_CHECK so boot-time output is never mistaken for real
  //    request failures in monitoring.
  //    Probe A — the PRIMARY chain (CLIENT_LIST + PO token + guest session):
  //      exactly what real requests use. The FIRST video's probe A also WARMS
  //      the real guest cookiejar and the provider's minter, and opens the 503    //      gate when it finishes.
    //    Probe B — the MOBILE innertube chain (direct signed URLs + GVS PO
    //      token): validates the exact mobile+PO config real requests now use
    //      and verifies the provider mints tokens for it. Runs with -v and
    //      ALWAYS dumps its output tail on failure, so a broken fallback is
    //      diagnosable from the log alone.
  function runYtDlpProbe() {
    if (ytDlpProbeStarted) return;
    ytDlpProbeStarted = true;
    if (!DEBUG_BOOT_CHECK) {
      console.log('[boot-check] DEBUG_BOOT_CHECK=false — skipping the yt-dlp extraction probes (the provider /get_pot warmup above still ran)');
      potBootCheckDone = true;
      return;
    }

    const DEFAULT_PROBE_VIDEOS = [
      'https://www.youtube.com/watch?v=jNQXAC9IVRw', // "Me at the zoo" (classic smoke-test canary)
      'https://www.youtube.com/watch?v=aqz-KE-bpKQ', // Big Buck Bunny — ordinary public video
      'https://www.youtube.com/watch?v=C_iHHP8LfGk', // real video from the Madinah feed this app serves
      'https://www.youtube.com/watch?v=jK6wgG6C4PY', // real video from the Makkah feed this app serves
    ];
    const probeItems = (process.env.BOOT_CHECK_VIDEOS || '')
      .split(',').map((s) => s.trim()).filter(Boolean);
    const probeVideos = (probeItems.length ? probeItems : DEFAULT_PROBE_VIDEOS)
      .map((item) => (/^[A-Za-z0-9_-]{11}$/.test(item)
        ? `https://www.youtube.com/watch?v=${item}` : item));
    console.log(
      `[boot-check] probing ${probeVideos.length} video(s) with both chains` +
      (probeItems.length ? ' (BOOT_CHECK_VIDEOS override)' : ' (default set)') + ':'
    );
    for (const u of probeVideos) console.log(`[boot-check]   - ${u}`);

    // Throwaway cookiejar for probes that must never touch the shared guest
    // jar: probe A for videos 2+ and every probe B run happen AFTER the 503
    // gate opens, when real requests could be writing the shared jar — a
    // concurrent read/modify/write would race it. These jars start empty and
    // yt-dlp populates them exactly like the guest jar (still no login).
    const throwawayJar = (name) => {
      const p = path.join(require('os').tmpdir(), `clearview-${name}-cookies.txt`);
      try {
        if (!fs.existsSync(p)) fs.writeFileSync(p, '# Netscape HTTP Cookie File\n');
      } catch (_) { /* best effort */ }
      return p;
    };
    const label = (url) =>
      `video #${probeVideos.indexOf(url) + 1} (${videoIdFromUrl(url) || url})`;
    // Parses a probe's --print '%(id)s|%(title)s' stdout into the printed id,
    // the title (may be blank on some platforms for non-ASCII text), and a
    // success flag keyed on the id — ASCII and encoding-proof.
    const parseProbePrint = (stdout, url) => {
      const printed = String(stdout || '').trim();
      const id = printed.split('|')[0].trim();
      const title = printed.split('|').slice(1).join('|').trim();
      const expected = videoIdFromUrl(url);
      return { id, title, ok: id.length > 0 && id === (expected || id) };
    };
    const MOBILE_ARGS = `youtube:player_client=${MOBILE_CLIENT_LIST};fetch_pot=always`;
    let probeIndex = 0;

    // ---- Probe A: PRIMARY chain (guest cookiejar + CLIENT_LIST + PO token) ----
    // The FIRST video's probe A uses the REAL guest jar (the gate is still
    // closed, so no real request can race it) — this is what warms the guest
    // session for the first real request. Videos 2+ use throwaway jars. Every
    // run passes the exact same args as real requests: guest jar + CLIENT_LIST
    // + fetch_pot=always + the POT plugin.
    function runProbeA(url) {
      const isFirst = probeIndex === 0;
      const jar = isFirst ? GUEST_COOKIES_PATH : throwawayJar('probe-a');
      const tag = label(url);
      console.log(
        `[boot-check] probe A (PRIMARY ${CLIENT_LIST}+PO chain) ${tag}: ${BIN_PATH} --cookies ${jar} --plugin-dirs ${PLUGINS_DIR} --no-warnings --no-check-certificates --no-update --socket-timeout 20 ` +
        `--extractor-args "youtube:player_client=${CLIENT_LIST};fetch_pot=always" --extractor-args "youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}" --print "%(id)s|%(title)s" -v ${url}`
      );
      const before = potActivity;
      execFile(
        BIN_PATH,
        [
          '--cookies', jar,
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
          '--print', '%(id)s|%(title)s',
          '-v',
          url,
        ],
        // 120 s (not 60): probes 2+ run concurrently with real requests, which
        // slows YouTube enough that 60 s could kill a working extraction.
        // PYTHONUNBUFFERED flushes the --print output to the pipe immediately,
        // so even a timeout-killed probe still reports the id it extracted.
        { timeout: 120000, encoding: 'utf8', env: { ...process.env, PYTHONUNBUFFERED: '1', PYTHONIOENCODING: 'utf-8' } },
        (errA, stdoutA, stderrA) => {
          // The FIRST probe A is what WARMS the provider's minter under the
          // real cache key — open the 503 gate as soon as it finishes (probe B
          // and the later videos are purely informational). Set first so a
          // stray exception below can never leave the gate closed past its cap.
          if (isFirst) potBootCheckDone = true;
          const outA = `${stdoutA || ''}\n${stderrA || ''}`;
          // Success signal = the printed video ID: ASCII and encoding-proof.
          // Non-ASCII titles can print as blank/spaces on some platforms (a
          // local-Windows quirk — the title field itself is fine), so the
          // verdict must NOT depend on the title text.
          const probePrint = parseProbePrint(stdoutA, url);
          const extractedId = probePrint.id;
          const titleA = probePrint.title;
          const okA = !errA && probePrint.ok;
          const potLines = potActivity - before;
          const httpProviderUsed = /\[pot:bgutil:http\]\s+Generating a .*PO Token for/.test(outA);
          const tokenRetrieved = /Retrieved a .*PO Token/.test(outA);
          if (okA) {
            console.log(`[boot-check] PRIMARY chain (${CLIENT_LIST}+PO, guest session) extraction OK — "${(titleA || extractedId).slice(0, 60)}" (${tag})`);
          } else {
            console.warn(
              `[boot-check] PRIMARY chain extraction FAILED (${String((errA && errA.message) || 'no id extracted').slice(0, 120)}) — ${tag}; real requests will fall back to the mobile chain`
            );
          }
          // PO-pipeline verdict (the provider is only used by probe A). The
          // script-node / script-deno "Script path doesn't exist" lines are
          // EXPECTED noise from yt-dlp's availability checks and appear even in
          // fully working runs — they do NOT mean the HTTP provider was skipped.
          if (httpProviderUsed) {
            console.log(`[boot-check] ${tag}: bgutil HTTP provider WAS USED to generate PO tokens`);
          } else if (/bgutil:http/.test(outA)) {
            // NB: the plugin's registration line ("PO Token Providers: bgutil:http-1.3.1
            // (external), ...") always contains bgutil:http, so this branch means the
            // plugin loaded but no token generation was observed — not that it was skipped.
            console.warn(`[boot-check] ${tag}: bgutil HTTP provider loaded but NO token generation was observed`);
          } else {
            console.warn(`[boot-check] ${tag}: bgutil NOT detected in yt-dlp verbose output — PO tokens will not be attached`);
          }
          console.log(
            potLines > 0
              ? `[boot-check] ${tag}: provider GENERATED ${potLines} token generation(s) during probe A — PO pipeline works end-to-end`
              : `[boot-check] ${tag}: provider saw NO token request during probe A — the plugin did not reach it (or the token was served from the provider cache)`
          );
          if (tokenRetrieved) {
            console.log(`[boot-check] ${tag}: yt-dlp RETRIEVED at least one PO token from the provider`);
          }
          // Dump the verbose output tail whenever the verdict was NOT ok (or
          // the provider was not actually used) — the decisive evidence for
          // WHY (e.g. HTTP 403 on the webpage, plugin "Error reaching GET
          // .../ping", "failed to get token", or a "Sign in to confirm you're
          // not a bot" page). The condition is on the VERDICT, not on errA:
          // an exit-0-with-no-id probe used to log "FAILED" with no evidence.
          if (!okA || !httpProviderUsed) {
            const probeLines = String(outA).trim().split(/\r?\n/).filter(Boolean);
            console.log(`[boot-check] ${tag} probe A output tail (last ${Math.min(15, probeLines.length)} of ${probeLines.length} lines):`);
            for (const line of probeLines.slice(-15)) {
              console.log(`[boot-check]   | ${line.slice(0, 220)}`);
            }
          }
          runProbeB(url);
        }
      );
    }

    // ---- Probe B: MOBILE innertube chain (direct URLs + GVS PO token) ----
    // Throwaway jar of its own (the gate is open by now), -v, and the output
    // tail is ALWAYS dumped on failure — the old code logged only the error
    // message and cut off with no detail. Mirrors probe A's PO-pipeline
    // verdicts so the boot log proves the provider minted a MOBILE token.
    function runProbeB(url) {
      const jar = throwawayJar('probe-b');
      const tag = label(url);
      console.log(
        `[boot-check] probe B (MOBILE ${MOBILE_CLIENT_LIST}+PO chain) ${tag}: ${BIN_PATH} --cookies ${jar} --plugin-dirs ${PLUGINS_DIR} --no-warnings --no-check-certificates --no-update --socket-timeout 20 --extractor-args "${MOBILE_ARGS}" --extractor-args "youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}" --print "%(id)s|%(title)s" -v ${url}`
      );
      const beforeB = potActivity;
      execFile(
        BIN_PATH,
        [
          '--cookies', jar,
          '--plugin-dirs', PLUGINS_DIR,
          '--no-warnings', '--no-check-certificates', '--no-update',
          '--socket-timeout', '20',
          '--extractor-args', MOBILE_ARGS,
          '--extractor-args', `youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}`,
          '--print', '%(id)s|%(title)s',
          '-v',
          url,
        ],
        // Same robustness as probe A: 120 s + unbuffered stdout (id survives
        // a timeout kill) + encoding forced to UTF-8.
        { timeout: 120000, encoding: 'utf8', env: { ...process.env, PYTHONUNBUFFERED: '1', PYTHONIOENCODING: 'utf-8' } },
        (errB, stdoutB, stderrB) => {
          // Idempotent with probe A — belt and braces.
          potBootCheckDone = true;
          const outB = `${stdoutB || ''}\n${stderrB || ''}`;
          // Same id-based success signal as probe A (ASCII, encoding-proof).
          const probePrint = parseProbePrint(stdoutB, url);
          const extractedId = probePrint.id;
          const titleB = probePrint.title;
          const okB = !errB && probePrint.ok;
          if (okB) {
            console.log(`[boot-check] MOBILE chain extraction OK — "${(titleB || extractedId).slice(0, 60)}" (${tag}, mobile innertube clients + PO token)`);
          } else {
            console.warn(`[boot-check] MOBILE chain extraction FAILED (${String((errB && errB.message) || 'no id extracted').slice(0, 120)}) — ${tag}`);
            // ALWAYS dump the output tail on failure (the old code logged only
            // the message and cut off with no detail) — including the
            // exit-0-no-id case, where the tail is the only evidence.
            const probeLines = String(outB).trim().split(/\r?\n/).filter(Boolean);
            console.log(`[boot-check] ${tag} probe B output tail (last ${Math.min(15, probeLines.length)} of ${probeLines.length} lines):`);
            for (const line of probeLines.slice(-15)) {
              console.log(`[boot-check]   | ${line.slice(0, 220)}`);
            }
          }
          // PO-pipeline verdict for the mobile chain (same signals as probe A).
          const potLines = potActivity - beforeB;
          const httpProviderUsed = /\[pot:bgutil:http\]\s+Generating a .*PO Token for/.test(outB);
          const tokenRetrieved = /Retrieved a .*PO Token/.test(outB);
          if (httpProviderUsed) {
            console.log(`[boot-check] ${tag}: bgutil HTTP provider WAS USED for the mobile chain`);
          }
          console.log(
            potLines > 0
              ? `[boot-check] ${tag}: provider GENERATED ${potLines} token generation(s) during probe B — mobile chain PO pipeline works`
              : `[boot-check] ${tag}: provider saw NO token request during probe B (token may have been served from the provider cache)`
          );
          if (tokenRetrieved) {
            console.log(`[boot-check] ${tag}: yt-dlp RETRIEVED at least one PO token for the mobile chain`);
          }
          probeIndex++;
          if (probeIndex < probeVideos.length) runProbeA(probeVideos[probeIndex]);
        }
      );
    }

    runProbeA(probeVideos[0]);
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
// The ANONYMOUS GUEST SESSION is the ONLY identity path. yt-dlp is ALWAYS
// passed --cookies <guest jar> — an empty Netscape jar it creates and reuses —
// so YouTube's own visitor markers (VISITOR_INFO1_LIVE, …) persist between
// calls instead of every request looking like a brand-new, unrelated client
// (which is exactly what drew the "Sign in to confirm you're not a bot" check
// in the old cookie-less pattern).
//
// Account cookies are RETIRED. Older builds could opt in via COOKIES_B64
// (base64 of a signed-in browser's cookies.txt) or a cookies.txt next to
// server.js — those expire, get rotated by YouTube, and were never required.
// If either is detected it is now IGNORED (a warning is logged) and the guest
// jar is used; no login cookies ever go in the jar.
let cookiesPath = GUEST_COOKIES_PATH;
try {
  // Seed an empty Netscape-format jar so yt-dlp always has a valid file to
  // load and save back to, even before its first extraction.
  if (!fs.existsSync(GUEST_COOKIES_PATH)) {
    fs.writeFileSync(GUEST_COOKIES_PATH, '# Netscape HTTP Cookie File\n');
  }
  if (process.env.COOKIES_B64) {
    console.warn(
      '[server] WARNING: COOKIES_B64 is set but account cookies are RETIRED — ignoring it ' +
        'and using the anonymous guest cookiejar (no login needed). Remove COOKIES_B64.'
    );
  } else if (fs.existsSync(path.join(__dirname, 'cookies.txt'))) {
    console.warn(
      '[server] WARNING: cookies.txt found but account cookies are RETIRED — ignoring it ' +
        'and using the anonymous guest cookiejar (no login needed). Delete cookies.txt.'
    );
  }
  console.log(`[server] using anonymous guest cookiejar at ${GUEST_COOKIES_PATH} (no login needed)`);
} catch (e) {
  console.warn('[server] could not prepare the guest cookiejar:', e.message);
  cookiesPath = GUEST_COOKIES_PATH;
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
//   - FALLBACK: mobile innertube clients (android_vr/android/ios/…) + GVS PO
//     token. They serve direct signed URLs; newer YouTube builds strip or block
//     them without a token (yt-dlp #17348), so fetch_pot=always is now set. A
//     failed token fetch degrades gracefully (yt-dlp proceeds tokenless).
//   - TV: tv_embedded/tv — a separate trust path that historically survives
//     datacenter-IP blocks (no PO token needed).
//   - DEFAULT: yt-dlp's own default client set.
// Each entry is tried until one returns a playable URL. The web/PO chain is
// ADAPTIVELY DEMOTED to the end after repeated login/botcheck verdicts (see
// PO_CHAIN_DEMOTE_* above) so a dead-on-this-IP web chain never stalls every
// request; the cooldown re-tests it and a success re-promotes it.
function clientChains() {
  const poBaseUrl = `youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}`;
  const webChain = {
    label: `primary (${CLIENT_LIST} + PO token)`,
    extractorArgs: [`youtube:player_client=${CLIENT_LIST};fetch_pot=always`, poBaseUrl],
    pluginDirs: PLUGINS_DIR,
    kind: 'web',
  };
  const mobileChain = {
    label: `mobile (${MOBILE_CLIENT_LIST} + PO)`,
    extractorArgs: [
      `youtube:player_client=${MOBILE_CLIENT_LIST};fetch_pot=always`,
      poBaseUrl,
    ],
    pluginDirs: PLUGINS_DIR,
    kind: 'mobile',
  };
  const tvChain = {
    label: `tv (${TV_CLIENT_LIST})`,
    extractorArgs: `youtube:player_client=${TV_CLIENT_LIST}`,
  };
  const defaultChain = {
    label: 'default,-web',
    extractorArgs: 'youtube:player_client=default,-web',
  };
  // Insurance against a hard-failing mobile token fetch: same innertube
  // clients WITHOUT fetch_pot, tried only when the +PO mobile chain fails.
  // If fetch_pot degrades gracefully this chain is never reached.
  const mobileTokenlessChain = {
    label: `mobile tokenless (${MOBILE_CLIENT_LIST})`,
    extractorArgs: `youtube:player_client=${MOBILE_CLIENT_LIST}`,
  };

  if (!potReady) {
    // Provider down (no PO tokens): mobile tokenless first, then tv, defaults.
    return [
      {
        label: `mobile (${MOBILE_CLIENT_LIST})`,
        extractorArgs: `youtube:player_client=${MOBILE_CLIENT_LIST}`,
      },
      tvChain,
      defaultChain,
      { label: 'yt-dlp default', extractorArgs: null },
    ];
  }

  const demoted = poChainDemotedAt > 0 &&
    Date.now() < poChainDemotedAt + PO_CHAIN_DEMOTE_MS;
  const chains = [webChain, mobileChain, mobileTokenlessChain, tvChain, defaultChain];
  if (demoted) {
    const [first, ...rest] = chains;
    return [...rest, first]; // web chain demoted to the end, still present
  }
  return chains;
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
      const isWebChain = chain.kind === 'web';

      // DEBUG instrumentation: print the EXACT command line youtube-dl-exec
      // will spawn for the first chain, and run it with --verbose so its debug
      // output shows whether the bgutil provider is registered, contacted, and
      // returning a token (look for "PO Token Providers:", "Getting POT",
      // "Generating POT", and the provider's own [pot] lines in the server log).
      if (idx === 0) {
        // Set verbose BEFORE building the logged command line so the FULL
        // COMMAND log actually shows --verbose when it is passed.
        if (isPoChain) opts.verbose = true;
        try {
          const argv = [url].concat(buildArgs(opts));
          console.log(`[yt-dlp] FULL COMMAND: ${BIN_PATH} ${argv.join(' ')}`);
        } catch (e) {
          console.warn(`[yt-dlp] could not build command line: ${e.message}`);
        }
      }

      // Per-client result logging: each attempt logs a Trying + result line so
      // the chain that actually fixed (or failed) a request is obvious at a
      // glance (e.g. [yt-dlp] trying chain 2/4: "mobile …" → OK).
      console.log(`[yt-dlp] trying chain ${idx + 1}/${chains.length}: "${chain.label || 'default'}"`);
      try {
        const info = await ytDlp(url, opts);
        if (isWebChain) {
          // A working web chain clears the demotion state (and any streak).
          poChainFailStreak = 0;
          if (poChainDemotedAt > 0) {
            poChainDemotedAt = 0;
            console.log('[yt-dlp] web/PO chain worked again — re-promoted to the front of the chain order');
          }
        }
        const direct = info.url;
        if (!direct) throw new Error('yt-dlp returned no stream url');
        const size = info.filesize || info.filesize_approx || 0;
        console.log(`[yt-dlp] chain "${chain.label || 'default'}" OK — ${info.ext || 'm4a'}${size ? `, ${size} bytes` : ''}`);
        return {
          url: direct,
          ext: info.ext || 'm4a',
          size,
          source: 'yt-dlp',
        };
      } catch (e) {
        lastError = e;
        const verdict = classifyExtractionError(e);
        // Reuse the verdict instead of re-classifying inside
        // isTransientExtractionError (identical botcheck/transient rule).
        if (verdict.code === 'botcheck' || verdict.code === 'transient') anyTransient = true;
        if (isWebChain) {
          // Adaptive demotion: consecutive bot-blocks on the web chain mean the
          // datacenter IP is flagged for web clients — stop paying the 5-30 s
          // cost on every request until the cooldown re-tests. Only the bot
          // signal counts: a genuinely private/age-restricted video (login
          // WITHOUT the LOGIN_REQUIRED playability line) must not demote the
          // web chain — every chain fails on those.
          const rawText = String(e.stderr || '') + '\n' + String(e.message || '');
          const isBotBlock = verdict.code === 'botcheck' ||
            (verdict.code === 'login' && /LOGIN_REQUIRED/i.test(rawText));
          if (isBotBlock) {
            if (poChainDemotedAt > 0 && Date.now() >= poChainDemotedAt + PO_CHAIN_DEMOTE_MS) {
              // Cooldown over: clear the demotion and start a fresh streak.
              poChainDemotedAt = 0;
              poChainFailStreak = 0;
            }
            poChainFailStreak++;
            if (poChainDemotedAt === 0 && poChainFailStreak >= PO_CHAIN_DEMOTE_STREAK) {
              poChainDemotedAt = Date.now();
              console.warn(
                `[yt-dlp] web/PO chain failed ${PO_CHAIN_DEMOTE_STREAK}× in a row (${verdict.code}) — demoting it for ${Math.round(PO_CHAIN_DEMOTE_MS / 60000)} min; mobile/tv chains go first now`
              );
            }
          } else {
            poChainFailStreak = 0;
          }
        }
        // Log the full stderr — POT-plugin warnings ("failed to get token",
        // etc.) live there but never reach the thrown message. The real error
        // is at the END of stderr (the --verbose debug header eats the first
        // ~1.5 KB), so log the TAIL, not the head.
        const detail = String(e.stderr || e.message || e).slice(-600);
        console.warn(`[yt-dlp] chain "${chain.label || 'default'}" failed (${verdict.code}): ${detail}`);
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
  // use. A request now could pay the slow cold BotGuard solve (20-70 s on
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
});
