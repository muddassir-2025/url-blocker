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
 *   1. @distube/ytdl-core resolves the video and picks the best audio-only
 *      format (M4A preferred, then Opus/WebM — highest bitrate).
 *   2. The chosen format's stream is piped back to the client with
 *      Content-Type / Content-Length when known.
 *   3. If ytdl-core fails (YouTube changed internals, 403, decipher errors),
 *      it automatically falls back to yt-dlp (via youtube-dl-exec, which
 *      downloads its own yt-dlp binary on first use) and pipes that stream.
 *
 * Deployment (Render free tier):
 *   - Root directory: backend
 *   - Build command:  npm install
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
const { youtubeDl } = require('youtube-dl-exec');
const https = require('https');
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');

const app = express();
app.disable('x-powered-by');

const PORT = process.env.PORT || 3000;
const TOKEN = process.env.AUDIO_TOKEN || '';
const MAX_CONCURRENT = 3;
const INFO_CACHE_MS = 10 * 60 * 1000;

const UA =
  'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36';

let activeStreams = 0;

// ── Video id ────────────────────────────────────────────────────────────

const VIDEO_ID_RE =
  /(?:youtube\.com\/(?:watch\?(?:.*&)?v=|shorts\/|embed\/|live\/)|youtu\.be\/)([A-Za-z0-9_-]{11})/;

function videoIdFromUrl(url) {
  const m = String(url || '').match(VIDEO_ID_RE);
  return m ? m[1] : null;
}

// ── Format selection ────────────────────────────────────────────────────

/**
 * Best audio-only format: M4A (AAC) preferred for compatibility, then
 * Opus/WebM; highest bitrate within each container. Skips formats without a
 * usable url.
 */
function chooseAudioFormat(formats) {
  const audio = formats.filter(
    (f) => f.hasAudio && !f.hasVideo && f.url && f.url.startsWith('http')
  );
  if (!audio.length) throw new Error('No audio-only formats available');
  const score = (f) => (f.container === 'm4a' ? 2 : f.container === 'webm' ? 1 : 0);
  return audio
    .slice()
    .sort(
      (a, b) =>
        score(b) - score(a) || (b.audioBitrate || 0) - (a.audioBitrate || 0)
    )[0];
}

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

// ── Info cache (avoids re-hitting YouTube for repeats) ──────────────────

const infoCache = new Map();

async function getInfoCached(url) {
  const id = videoIdFromUrl(url);
  const hit = id && infoCache.get(id);
  if (hit && Date.now() - hit.at < INFO_CACHE_MS) return hit.info;
  let info;
  try {
    // WEB_EMBEDDED / mobile clients are far less likely to be bot-blocked
    // than the default WEB client.
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
    // Keep the cache bounded on a long-running instance.
    if (infoCache.size > 200) infoCache.clear();
    infoCache.set(id, { info, at: Date.now() });
  }
  return info;
}

// ── yt-dlp fallback ─────────────────────────────────────────────────────

async function streamWithYtDlp(url, videoId, req, res) {
  const info = await youtubeDl(url, {
    dumpSingleJson: true,
    format: 'bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio',
    noPlaylist: true,
    noWarnings: true,
    noCheckCertificates: true,
  });
  const direct = info.url;
  if (!direct) throw new Error('yt-dlp returned no stream url');

  const ext = info.ext || 'm4a';
  const size = info.filesize || info.filesize_approx || 0;
  res.status(200);
  res.set('Content-Type', mimeForExt(ext));
  if (size) res.set('Content-Length', size);
  res.set('Accept-Ranges', 'bytes');
  res.set('Content-Disposition', `inline; filename="${videoId}.${ext}"`);
  res.set('X-Audio-Source', 'yt-dlp');

  const transport = direct.startsWith('https') ? https : http;
  const upstream = transport.get(
    direct,
    {
      headers: { 'User-Agent': UA, Referer: 'https://www.youtube.com/' },
    },
    (stream) => {
      stream.on('error', (err) => {
        console.error('[yt-dlp] upstream error', err.message);
        if (!res.headersSent) res.status(502).json({ error: 'Upstream error' });
        else res.destroy();
      });
      stream.pipe(res);
    }
  );
  upstream.on('error', (err) => {
    console.error('[yt-dlp] request error', err.message);
    if (!res.headersSent) res.status(502).json({ error: 'Upstream error' });
  });
  req.on('close', () => upstream.destroy());
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
    // ── Primary: @distube/ytdl-core ──
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
  } catch (err) {
    // ── Fallback: yt-dlp (auto-uses its own binary via youtube-dl-exec) ──
    console.warn('[ytdl-core] failed, falling back to yt-dlp:', err.message);
    try {
      await streamWithYtDlp(url, videoId, req, res);
    } catch (err2) {
      console.error('[yt-dlp] fallback failed:', err2.message);
      if (!res.headersSent) {
        res.status(500).json({
          error:
            'Could not fetch this audio right now. YouTube changes its internals often — try again in a few minutes.',
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
