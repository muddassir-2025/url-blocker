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
 *      It retries a few bot-resistant client chains (mobile innertube clients
 *      first) and passes cookies when COOKIES_B64 / cookies.txt is present.
 *   2. The chosen format's direct URL is piped back to the client with
 *      Content-Type / Content-Length when known.
 *   3. If every yt-dlp client fails, the server falls back to
 *      @distube/ytdl-core and pipes that stream.
 *
 * Deployment (Render free tier):
 *   - Root directory: backend
 *   - Build command:  npm install   (also fetches the yt-dlp binary)
 *   - Start command:  npm start
 *   - Env vars:       PORT (auto), AUDIO_TOKEN (optional shared secret),
 *                     YTDL_NO_UPDATE=1 (skips yt-dlp's periodic update check)
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
const BOOT_WINDOW_MS = 240 * 1000;
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
      console.log(`[pot] yt-dlp version: ${ver}`);
    }
  );

  // 2) Provider /ping from the server side — this is the EXACT endpoint the
  //    bgutil plugin probes before requesting a token (cached 60 s). Proves
  //    reachability independently of yt-dlp.
  const ping = http.get({ host: '127.0.0.1', port: POT_PORT, path: '/ping' }, (res) => {
    let body = '';
    res.on('data', (d) => (body += d));
    res.on('end', () => {
      console.log(`[pot] provider /ping -> HTTP ${res.statusCode} ${String(body).trim().slice(0, 80)}`);
    });
  });
  ping.on('error', (e) => console.warn(`[pot] provider /ping FAILED: ${e.message}`));
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
              `[pot] direct /get_pot probe -> HTTP 200 in ${(ms / 1000).toFixed(1)}s — solve is very slow (near the 45s plugin/probe timeout); this probe WARMS the provider, so later token requests should be fast (see the yt-dlp probe verdicts below)`
            );
          } else {
            console.log(
              `[pot] direct /get_pot probe -> HTTP 200 in ${(ms / 1000).toFixed(1)}s — provider CAN solve BotGuard on this IP`
            );
          }
        } else {
          console.warn(
            `[pot] direct /get_pot probe -> HTTP ${res.statusCode} after ${(ms / 1000).toFixed(1)}s — ${String(body).slice(0, 200)} (provider solve is the suspect)`
          );
        }
        runYtDlpProbe();
      });
    }
  );
  getPotProbe.on('error', (e) => {
    // Probe failed, but the provider may still serve real requests — run the
    // yt-dlp probe anyway; its own verdicts show what actually happens.
    console.warn(`[pot] direct /get_pot probe FAILED: ${e.message}`);
    runYtDlpProbe();
  });
  getPotProbe.setTimeout(45000, () => {
    getPotProbe.destroy();
    console.warn('[pot] direct /get_pot probe timed out after 45s');
    runYtDlpProbe();
  });
  getPotProbe.write('{}');
  getPotProbe.end();

  // 4) Full extraction through the plugin, then report whether the provider
  //    actually generated a token during it (potActivity delta). This turns
  //    the boot log into a complete end-to-end verdict. It runs only AFTER the
  //    /get_pot probe above has finished (runYtDlpProbe) so that (a) it benefits
  //    from the probe's warm minter, and (b) the potActivity delta is
  //    unambiguous — the probe's own token generation can no longer be counted
  //    as the yt-dlp run's (they used to run concurrently, which made the
  //    verdict unreliable).
  function runYtDlpProbe() {
    if (ytDlpProbeStarted) return;
    ytDlpProbeStarted = true;

    // Log the exact command so the Render logs prove which args reached
    // yt-dlp — both extractor-args flags must be present (player_client+
    // fetch_pot in the youtube: flag, base_url in the plugin flag).
    console.log(
      `[pot] boot check: running: ${BIN_PATH} --plugin-dirs ${PLUGINS_DIR} --no-warnings --no-check-certificates --no-update --socket-timeout 20 ` +
      `--extractor-args "youtube:player_client=web,web_embedded;fetch_pot=always" --extractor-args "youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}" --print title -v ${probeUrl}`
    );

    const before = potActivity;
    execFile(
    BIN_PATH,
    [
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
      '--extractor-args', 'youtube:player_client=web,web_embedded;fetch_pot=always',
      '--extractor-args', `youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}`,
      '--print', 'title',
      '-v',
      probeUrl,
    ],
    { timeout: 60000, encoding: 'utf8' },
    (err, stdout, stderr) => {
      // Set first so a stray exception in the verdict code below can never leave
      // the 503 boot-window gate closed past its time cap.
      potBootCheckDone = true;
      const out = `${stdout || ''}\n${stderr || ''}`;
      const potLines = potActivity - before;
      // The HTTP provider is genuinely used only when it logs a token
      // generation line. The script-node / script-deno "Script path doesn't
      // exist" lines are EXPECTED noise from yt-dlp's availability checks and
      // appear even in fully working runs — they do NOT mean the HTTP provider
      // was skipped.
      const httpProviderUsed = /\[pot:bgutil:http\]\s+Generating a .*PO Token for/.test(out);
      const tokenRetrieved = /Retrieved a .*PO Token/.test(out);
      if (httpProviderUsed) {
        console.log('[pot] boot check: bgutil HTTP provider WAS USED to generate PO tokens');
      } else if (/bgutil:http/.test(out)) {
        // NB: the plugin's registration line ("PO Token Providers: bgutil:http-1.3.1
        // (external), ...") always contains bgutil:http, so this branch means the
        // plugin loaded but no token generation was observed (e.g. the extraction
        // failed before a token was requested) — not that it was skipped.
        console.warn('[pot] boot check: bgutil HTTP provider loaded but NO token generation was observed during the probe');
      } else {
        console.warn('[pot] boot check: bgutil NOT detected in yt-dlp verbose output — PO tokens will not be attached');
      }
      console.log(
        potLines > 0
          ? `[pot] boot check: provider GENERATED ${potLines} token generation(s) during the probe — PO pipeline works end-to-end`
          : '[pot] boot check: provider saw NO token request during the probe — the plugin did not reach it (or the token was served from the provider cache)'
      );
      if (tokenRetrieved) {
        console.log('[pot] boot check: yt-dlp RETRIEVED at least one PO token from the provider');
      }
      if (err && !tokenRetrieved) {
        console.warn(`[pot] boot check: the probe extraction itself failed (${String(err.message || err).slice(0, 120)})`);
      }
      // When the probe did NOT use the HTTP provider, dump its verbose output
      // tail — the decisive evidence for WHY (e.g. HTTP 403 on the webpage,
      // plugin "Error reaching GET .../ping", "failed to get token", or a
      // "Sign in to confirm you're not a bot" page from the datacenter IP).
      if (!httpProviderUsed || err) {
        const probeLines = String(out).trim().split(/\r?\n/).filter(Boolean);
        console.log(`[pot] boot check: yt-dlp probe output tail (last ${Math.min(15, probeLines.length)} of ${probeLines.length} lines):`);
        for (const line of probeLines.slice(-15)) {
          console.log(`[pot]   | ${line.slice(0, 220)}`);
        }
      }
    }
  );
  }
}

async function runBootCheck() {
  const ready = await waitForPotReady(90 * 1000);
  if (!ready) {
    console.warn('[pot] boot check skipped: provider did not become ready within 90 s');
    // Open the request gate so users aren't 503'd for the whole boot window on
    // top of the startup window when there is no provider to warm anyway.
    potBootCheckDone = true;
    return;
  }
  verifyPotWiring();
}
runBootCheck();

// ── YouTube cookies (best defense against bot detection) ─────────────────
// Priority: COOKIES_B64 env var (base64 of a Netscape-format cookies.txt,
// exported from a logged-in browser) → a cookies.txt file next to server.js.
// Passed to yt-dlp as --cookies so signed-in requests are treated as human.
const COOKIES_FILE = path.join(__dirname, 'cookies.txt');
let cookiesPath = null;
try {
  if (process.env.COOKIES_B64) {
    const tmp = path.join(require('os').tmpdir(), 'clearview-cookies.txt');
    fs.writeFileSync(tmp, Buffer.from(process.env.COOKIES_B64, 'base64').toString('utf8'));
    try { fs.chmodSync(tmp, 0o600); } catch (_) { /* best effort */ }
    cookiesPath = tmp;
    console.log('[server] using cookies from COOKIES_B64');
  } else if (fs.existsSync(COOKIES_FILE)) {
    cookiesPath = COOKIES_FILE;
    console.log('[server] using cookies.txt for yt-dlp');
  }
} catch (e) {
  console.warn('[server] could not load cookies:', e.message);
}

// Cookie diagnostics — the #1 reason downloads stay bot-blocked is a cookie
// file exported from a session that was NOT signed in. Log what we actually
// have so the Render logs make it obvious.
if (cookiesPath) {
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
        '[server] WARNING: no signed-in cookies found — YouTube will still bot-block ' +
          'the server. Re-export cookies from a SIGNED-IN YouTube session ' +
          '(incognito window → sign in → export) and update COOKIES_B64.'
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

// Client strategies. On datacenter IPs YouTube bot-blocks the plain `web`
// client ("Sign in to confirm you're not a bot"), so:
//   - WITH the PO-token provider: the `web` client + BotGuard token goes
//     first — this is the combination that works from flagged IPs.
//   - Else WITH cookies: the yt-dlp default (which uses the cookies) first.
//   - Otherwise: mobile innertube clients (android_vr / android / ios) first.
// Each entry is tried until one returns a playable URL.
function clientChains() {
  if (potReady) {
    return [
      // web + web_embedded with the PO token — the combo that beats the
      // datacenter-IP bot check (plain `web` alone returns unplayable formats).
      //
      // fetch_pot=always is REQUIRED, not optional: it forces yt-dlp to mint a
      // PLAYER PO token and attach it to the player API request itself
      // (serviceIntegrityDimensions.poToken), BEFORE the request is sent.
      // Without it, yt-dlp only fetches the GVS token lazily AFTER a successful
      // player response — but from a flagged datacenter IP the tokenless player
      // request is bot-blocked (HTTP 403 / LOGIN_REQUIRED / "Sign in to confirm
      // you're not a bot"), so formats are never processed and the provider is
      // NEVER asked for a token ("provider saw NO token request during the
      // probe"). Verified end-to-end on yt-dlp 2026.07.04 + plugin 1.3.1: with
      // fetch_pot=always the provider is asked for player + gvs tokens and
      // extraction succeeds.
      //
      // NB: player_client and fetch_pot must live in the SAME youtube: flag
      // (separated by ';') — a second `youtube:` --extractor-args flag would
      // OVERRIDE the first (only the last flag per extractor key survives).
      'youtube:player_client=web,web_embedded;fetch_pot=always',
      'youtube:player_client=default,-web',
      'youtube:player_client=android_vr,android,ios,web_safari,web_music',
    ];
  }
  if (cookiesPath) {
    return [
      null, // yt-dlp default (incl. web) — uses the cookies
      'youtube:player_client=default,-web',
      'youtube:player_client=android_vr,android,ios,web_safari,web_music',
    ];
  }
  return [
    'youtube:player_client=android_vr,android,ios,web_safari,web_music',
    'youtube:player_client=default,-web',
    null, // yt-dlp default
  ];
}

async function streamWithYtDlp(url, videoId, req, res) {
  let lastError = null;
  const chains = clientChains();
  for (const [idx, chain] of chains.entries()) {
    const opts = {
      dumpSingleJson: true,
      format: 'bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio',
      noPlaylist: true,
      noWarnings: true,
      noCheckCertificates: true,
      noUpdate: true,
      // Bound each attempt so a hung YouTube response can't hold the request
      // (or an activeStreams slot) for the full app read timeout.
      socketTimeout: 30,
    };
    if (chain) opts.extractorArgs = chain;
    if (potReady) {
      // Load the PO-token plugin and point it at the local provider.
      opts.pluginDirs = PLUGINS_DIR;
      opts.extractorArgs = [
        ...(Array.isArray(opts.extractorArgs) ? opts.extractorArgs : opts.extractorArgs ? [opts.extractorArgs] : []),
        `youtubepot-bgutilhttp:base_url=http://127.0.0.1:${POT_PORT}`,
      ];
      // NOTE: player_skip=webpage was tried and REVERTED — it reduces token
      // generation (1 vs 2 contexts), risking a tokenless player request.
      // IMPORTANT: do NOT attach the account cookies on the PO chains — this
      // account is challenged from datacenter IPs ("Sign in to confirm you're
      // not a bot"), and the pure PO-token path is the documented bgutil flow.
      // Cookies stay on the non-PO fallback chains below.
    } else if (cookiesPath) {
      opts.cookies = cookiesPath;
    }

    // DEBUG instrumentation (temporary, kept minimal): on the first chain we
    // (a) print the EXACT command line youtube-dl-exec will spawn, and
    // (b) run yt-dlp with --verbose so its debug output shows whether the
    //     bgutil PO-token provider is registered, contacted, and returning a
    //     token (look for "PO Token Providers:", "Getting POT", "Generating POT",
    //     and the provider's own [pot] lines in the server log).
    if (idx === 0) {
      try {
        const argv = [url].concat(buildArgs(opts));
        console.log(`[yt-dlp] FULL COMMAND: ${BIN_PATH} ${argv.join(' ')}`);
      } catch (e) {
        console.warn(`[yt-dlp] could not build command line: ${e.message}`);
      }
      opts.verbose = true;
    }

    try {
      const info = await ytDlp(url, opts);
      const direct = info.url;
      if (!direct) throw new Error('yt-dlp returned no stream url');
      const ext = info.ext || 'm4a';
      const size = info.filesize || info.filesize_approx || 0;
      pipeDirect(direct, videoId, ext, size, req, res, 'yt-dlp');
      return;
    } catch (e) {
      lastError = e;
      // Log the full stderr — POT-plugin warnings ("failed to get token", etc.)
      // live there but never reach the thrown message.
      // The real error is at the END of stderr (the --verbose debug header
      // eats the first ~1.5 KB), so log the TAIL, not the head.
      const detail = String(e.stderr || e.message || e).slice(-600);
      console.warn(`[yt-dlp] chain "${chain || 'default'}" failed: ${detail}`);
      if (e && e.stderr) {
        console.warn(`[yt-dlp] chain "${chain || 'default'}" STDERR TAIL:\n${String(e.stderr).slice(-2000)}`);
      }
    }
  }
  if (potReady) {
    console.log(`[pot] request activity: ${potActivity} token generation(s) observed across the yt-dlp attempts`);
  }
  throw lastError || new Error('yt-dlp failed');
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

async function streamWithYtdlCore(url, videoId, req, res) {
  const info = await getInfoCached(url);
  const format = chooseAudioFormat(info.formats);

  res.status(200);
  res.set('Content-Type', format.mimeType || 'audio/webm');
  if (format.contentLength) res.set('Content-Length', format.contentLength);
  res.set('Accept-Ranges', 'bytes');
  res.set(
    'Content-Disposition',
    `inline; filename="${videoId}.${extOfFormat(format)}"`
  );
  res.set('X-Audio-Source', 'ytdl-core');

  const stream = ytdl.downloadFromInfo(info, {
    format,
    requestOptions: { headers: { 'User-Agent': UA } },
    highWaterMark: 1 << 25,
  });
  req.on('close', () => stream.destroy());
  stream.on('error', (err) => {
    console.error('[ytdl-core] stream error', err.message);
    if (!res.headersSent) res.status(502).json({ error: 'Stream error' });
    else res.destroy();
  });
  stream.pipe(res);
}

// ── Routes ──────────────────────────────────────────────────────────────

app.get('/health', (req, res) => {
  res.json({ ok: true, service: 'clearview-audio', time: Date.now() });
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
    // ── Primary: yt-dlp (actively maintained, works against current YouTube) ──
    await streamWithYtDlp(url, videoId, req, res);
  } catch (err) {
    // ── Fallback: @distube/ytdl-core ──
    console.warn('[yt-dlp] failed, falling back to ytdl-core:', err.message);
    try {
      await streamWithYtdlCore(url, videoId, req, res);
    } catch (err2) {
      console.error('[ytdl-core] fallback failed:', err2.message);
      if (!res.headersSent) {
        // Include the underlying reason so the app (and support) can see the
        // real cause — usually YouTube's bot check on datacenter IPs.
        // Keep it short — the app shows the body as-is, capped at 300 chars.
        const detail = String((err && err.message) || err || 'yt-dlp and ytdl-core both failed').slice(0, 150);
        res.status(500).json({
          error:
            'Could not fetch this audio right now. YouTube changes its internals often — try again in a few minutes. ' +
            `(Reason: ${detail})`,
        });
      }
    }
  }
});

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
