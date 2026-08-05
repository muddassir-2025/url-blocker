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
const { youtubeDl, create } = require('youtube-dl-exec');
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
const BIN_PATH = path.join(__dirname, 'bin', BIN_NAME);
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

function startPotProvider() {
  const main = path.join(__dirname, 'pot-provider', 'server', 'build', 'main.js');
  if (!fs.existsSync(main)) {
    console.warn('[pot] provider not built (scripts/fetch-pot-provider.js did not run or failed) — no PO tokens');
    return;
  }
  const { spawn } = require('child_process');
  const logChild = (prefix, d) => {
    const line = String(d).trim();
    if (line) console.log(`${prefix} ${line.slice(0, 300)}`);
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
    console.log(`[pot] reusing already-running provider on port ${POT_PORT}`);
  });
  pre.on('error', () => {
    launch();
    probeUntilReady(60 * 1000);
  });
  pre.setTimeout(2000, () => pre.destroy());
}
startPotProvider();

// Boot-time self-check: run one real extraction with the plugin and confirm
// yt-dlp sees the bgutil PO-token provider (its verbose output lists it). This
// makes a wiring regression visible in the logs instead of silent bot-check 500s.
function verifyPotWiring() {
  const { execFile } = require('child_process');
  const probeUrl = 'https://www.youtube.com/watch?v=jNQXAC9IVRw';
  execFile(
    BIN_PATH,
    [
      '--plugin-dirs', PLUGINS_DIR,
      '--no-warnings', '--no-check-certificates', '--no-update',
      '--socket-timeout', '20',
      '--print', 'title',
      '-v',
      probeUrl,
    ],
    { timeout: 45000, encoding: 'utf8' },
    (err, stdout, stderr) => {
      const out = `${stdout || ''}\n${stderr || ''}`;
      if (/bgutil/.test(out)) {
        console.log('[pot] boot check: yt-dlp loads the bgutil PO-token plugin OK');
      } else {
        console.warn('[pot] boot check: bgutil NOT detected in yt-dlp verbose output — PO tokens will not be attached');
      }
    }
  );
}
setTimeout(verifyPotWiring, 8000);

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
      'youtube:player_client=web,web_embedded',
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
  for (const chain of clientChains()) {
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
    }
    if (cookiesPath) opts.cookies = cookiesPath;
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
      const detail = String(e.stderr || e.message || e).slice(0, 600);
      console.warn(`[yt-dlp] chain "${chain || 'default'}" failed: ${detail}`);
    }
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
