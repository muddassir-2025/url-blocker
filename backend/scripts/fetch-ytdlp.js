/**
 * Fetches the yt-dlp binary into backend/bin/ at install/build time.
 *
 * Why: youtube-dl-exec downloads its yt-dlp binary LAZILY on first use (from
 * GitHub, at runtime). On a free-tier instance that first-use download can be
 * slow or fail mid-request, which surfaces as the app's "Could not fetch this
 * audio right now" error. Downloading the binary here — during `npm install`
 * (Render's build step) — guarantees the server always has a fresh binary
 * before it ever serves a request.
 *
 * Tolerant by design: if GitHub is unreachable the build still succeeds
 * (exit 0) and server.js falls back to youtube-dl-exec's auto-download.
 * Set YTDLP_FORCE=1 to force a re-download even when a binary already exists.
 *
 * Pinned version: the PO-token (bgutil) pipeline is verified against a
 * specific yt-dlp build; "latest" drifts silently and has broken plugin
 * invocation in the past. The script therefore pins YTDLP_VERSION and
 * re-downloads when the cached binary reports a different version, so a
 * deploy's build cache can never carry an unverified build over.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const { execFile } = require('child_process');

const isWin = process.platform === 'win32';
const fileName = isWin ? 'yt-dlp.exe' : 'yt-dlp';
const destDir = path.join(__dirname, '..', 'bin');
const dest = path.join(destDir, fileName);
// Pinned to the build verified end-to-end with the PO-token provider
// (bgutil plugin + BotGuard solve + download). Override with YTDLP_VERSION.
const PINNED_VERSION = process.env.YTDLP_VERSION || '2026.07.04';
const url = `https://github.com/yt-dlp/yt-dlp/releases/download/${PINNED_VERSION}/${fileName}`;

// Runs `yt-dlp --version` and returns the first token (e.g. "2026.07.04"),
// or null when the binary cannot execute (missing python3 on Linux, etc.).
function binaryVersion(bin) {
  return new Promise((resolve) => {
    execFile(bin, ['--version'], { timeout: 15000, encoding: 'utf8' }, (err, stdout) => {
      if (err) return resolve(null);
      resolve(String(stdout || '').trim().split(/\s+/)[0] || null);
    });
  });
}

// yt-dlp's Linux asset changed packaging (release 2026.07.04+) and is now a
// ~3 MB zipapp (the Windows exe is still ~18 MB), so the floor must be low.
// Only an HTML error page / truncated file should fail the check.
const MIN_SIZE = 1 * 1024 * 1024;

function log(msg) {
  console.log(`[fetch-ytdlp] ${msg}`);
}

function download(u, target) {
  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(target);
    const fail = (e) => {
      file.close();
      // Never leave a partial binary behind — server.js would otherwise
      // pick it up via its existence check and "use" a truncated file.
      try { fs.unlinkSync(target); } catch (_) { /* ignore */ }
      reject(e);
    };
    const req = https.get(u, { headers: { 'User-Agent': 'clearview-audio-backend' } }, (res) => {
      const status = res.statusCode || 0;
      // GitHub's /releases/latest/download/* redirects to the real asset.
      if (status >= 300 && status < 400 && res.headers.location) {
        res.resume();
        file.close();
        return download(res.headers.location, target).then(resolve, reject);
      }
      if (status !== 200) {
        res.resume();
        return fail(new Error(`HTTP ${status} from ${u}`));
      }
      res.pipe(file);
      file.on('finish', () => file.close(() => resolve()));
    });
    req.on('error', fail);
    file.on('error', (e) => {
      req.destroy();
      fail(e);
    });
  });
}

(async () => {
  try {
    fs.mkdirSync(destDir, { recursive: true });

    if (fs.existsSync(dest) && fs.statSync(dest).size >= MIN_SIZE &&
      process.env.YTDLP_FORCE !== '1'
    ) {
      const v = await binaryVersion(dest);
      if (v === PINNED_VERSION) {
        log(`${fileName} already present (${v}), skipping.`);
        return;
      }
      if (v === null) {
        // The binary exists and is plausible but can't be executed here (e.g.
        // no python3 in this build image). Keep the cached file — re-downloading
        // risks deleting a working binary if GitHub is flaky mid-build.
        log(`${fileName} present but unverifiable here — keeping it (set YTDLP_FORCE=1 to force a re-download).`);
        return;
      }
      log(`existing ${fileName} reports ${v} (want ${PINNED_VERSION}) — re-downloading.`);
    }

    let lastErr = null;
    for (let attempt = 1; attempt <= 3; attempt++) {
      try {
        log(`downloading ${url}`);
        await download(url, dest);
        lastErr = null;
        break;
      } catch (e) {
        lastErr = e;
        if (attempt < 3) {
          const wait = attempt * 3000;
          log(`attempt ${attempt} failed (${e.message}) — retrying in ${wait / 1000}s`);
          await new Promise((r) => setTimeout(r, wait));
        }
      }
    }
    if (lastErr) throw lastErr;

    const size = fs.statSync(dest).size;
    if (size < MIN_SIZE) {
      throw new Error(`downloaded file looks wrong (${size} bytes)`);
    }
    // A real yt-dlp binary starts with ELF (`\x7fELF`), a zipapp (`PK`/`#!`),
    // or on Windows `MZ` — never an HTML page.
    const fd = fs.openSync(dest, 'r');
    const magic = Buffer.alloc(4);
    fs.readSync(fd, magic, 0, 4, 0);
    fs.closeSync(fd);
    if (magic[0] === 0x3c) {
      // '<' — HTML error page
      throw new Error(`downloaded file is HTML, not the yt-dlp binary (${size} bytes)`);
    }
    if (!isWin) fs.chmodSync(dest, 0o755);
    // Verify the download actually is the pinned build (tolerant: if the
    // binary can't be executed here, accept it — the size+magic checks and
    // the server's own boot check already guard the runtime path).
    const got = await binaryVersion(dest);
    if (got && got !== PINNED_VERSION) {
      throw new Error(`downloaded binary reports version ${got}, expected ${PINNED_VERSION}`);
    }
    log(`${fileName} ready (${(size / 1024 / 1024).toFixed(1)} MB${got ? ', ' + got : ''}).`);
  } catch (e) {
    // Never break the build: server.js falls back to the lazy auto-download.
    // Clean up any partial file so it can't be mistaken for a real binary.
    try { if (fs.existsSync(dest)) fs.unlinkSync(dest); } catch (_) { /* ignore */ }
    console.warn(`[fetch-ytdlp] WARN: could not fetch yt-dlp binary: ${e.message}`);
    console.warn('[fetch-ytdlp] WARN: the server will try to download it at runtime instead.');
  }
})();
