/**
 * Fetches the yt-dlp PO-token provider (bgutil-ytdlp-pot-provider) at build time.
 *
 * Why: YouTube bot-blocks datacenter IPs ("Sign in to confirm you're not a bot")
 * even with valid cookies. The bgutil provider solves YouTube's BotGuard
 * attestation ON the server's own IP and hands yt-dlp a proof-of-origin (PO)
 * token, which is exactly the bypass that works from flagged IPs — no cookies
 * required, so every user of the app can download.
 *
 * This script does three things (all pinned to the same release tag):
 *   1. Clones the provider repo into backend/pot-provider/ and builds it
 *      (npm ci + npx tsc) so server.js can spawn it as a local HTTP server.
 *   2. Downloads the yt-dlp plugin zip into backend/plugins/ so the standalone
 *      yt-dlp binary can talk to the provider.
 *   3. Tolerant by design: any failure logs a warning and the build still
 *      succeeds (exit 0) — server.js then runs without PO tokens, exactly as
 *      before. Set YTDLP_FORCE=1 to force a re-fetch.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const { execSync } = require('child_process');

const TAG = '1.3.1'; // provider + plugin must match; see README of bgutil-ytdlp-pot-provider
const ROOT = path.join(__dirname, '..');
const PROVIDER_DIR = path.join(ROOT, 'pot-provider');
const PROVIDER_MAIN = path.join(PROVIDER_DIR, 'server', 'build', 'main.js');
const PLUGINS_DIR = path.join(ROOT, 'plugins');
const PLUGIN_ZIP = path.join(PLUGINS_DIR, 'bgutil-ytdlp-pot-provider.zip');
// NOTE: keep the plugin as the original ZIP — verified on both the Windows exe
// and the Linux zipapp that this exact zip is what registers the provider
// ("PO Token Providers: bgutil:http-1.3.1 (external)"). Repackaging it or
// extracting it to a directory breaks loading.
const MIN_ZIP_SIZE = 1 * 1024; // plugin zip is tiny (~8 KB) — only reject empty/HTML files

function log(msg) {
  console.log(`[fetch-pot] ${msg}`);
}

function warn(msg) {
  console.warn(`[fetch-pot] WARN: ${msg}`);
}

function run(cmd, cwd) {
  execSync(cmd, { cwd, stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
}

function download(url, target) {
  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(target);
    const fail = (e) => {
      file.close();
      try { fs.unlinkSync(target); } catch (_) { /* ignore */ }
      reject(e);
    };
    const req = https.get(url, { headers: { 'User-Agent': 'clearview-audio-backend', Accept: 'application/octet-stream' } }, (res) => {
      const status = res.statusCode || 0;
      if (status >= 300 && status < 400 && res.headers.location) {
        res.resume();
        file.close();
        return download(res.headers.location, target).then(resolve, reject);
      }
      if (status !== 200) {
        res.resume();
        return fail(new Error(`HTTP ${status} from ${url}`));
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

// GitHub API fallback: resolve the real plugin asset name for the pinned tag.
function pluginAssetUrl() {
  return new Promise((resolve, reject) => {
    https.get(
      `https://api.github.com/repos/Brainicism/bgutil-ytdlp-pot-provider/releases/tags/${TAG}`,
      { headers: { 'User-Agent': 'clearview-audio-backend' } },
      (res) => {
        let body = '';
        res.on('data', (d) => (body += d));
        res.on('end', () => {
          try {
            const json = JSON.parse(body);
            const asset = (json.assets || []).find((a) => /\.zip$/i.test(a.name));
            if (!asset) return reject(new Error('no zip asset in release'));
            resolve(asset.browser_download_url);
          } catch (e) {
            reject(new Error(`bad release JSON: ${String(e.message || e).slice(0, 80)}`));
          }
        });
      }
    ).on('error', reject);
  });
}

async function ensureProvider() {
  if (fs.existsSync(PROVIDER_MAIN) && process.env.YTDLP_FORCE !== '1') {
    log('provider already built, skipping.');
    return;
  }
  try {
    if (fs.existsSync(PROVIDER_DIR)) fs.rmSync(PROVIDER_DIR, { recursive: true, force: true });
    log(`cloning bgutil-ytdlp-pot-provider@${TAG} ...`);
    run(`git clone --depth 1 --branch ${TAG} https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git "${PROVIDER_DIR}"`, ROOT);
    const serverDir = path.join(PROVIDER_DIR, 'server');
    log('installing provider dependencies (npm ci) ...');
    run('npm ci', serverDir);
    log('building provider (npx tsc) ...');
    run('npx tsc', serverDir);
    if (!fs.existsSync(PROVIDER_MAIN)) throw new Error('build/main.js not produced');
    log('provider ready.');
  } catch (e) {
    try { if (fs.existsSync(PROVIDER_DIR)) fs.rmSync(PROVIDER_DIR, { recursive: true, force: true }); } catch (_) { /* ignore */ }
    throw e;
  }
}

async function ensurePlugin() {
  if (fs.existsSync(PLUGIN_ZIP) && fs.statSync(PLUGIN_ZIP).size >= MIN_ZIP_SIZE && process.env.YTDLP_FORCE !== '1') {
    log('plugin zip already present, skipping.');
    return;
  }
  // Build the candidate URLs: the conventional asset name first, then whatever
  // the GitHub API says is the real asset (in case the name ever changes).
  const candidates = [
    `https://github.com/Brainicism/bgutil-ytdlp-pot-provider/releases/download/${TAG}/bgutil-ytdlp-pot-provider.zip`,
  ];
  try {
    const apiUrl = await pluginAssetUrl();
    if (!candidates.includes(apiUrl)) candidates.push(apiUrl);
  } catch (e) {
    log(`could not resolve asset via API (${e.message}) — using conventional URL only`);
  }

  let lastErr = null;
  for (const url of candidates) {
    try {
      log(`downloading plugin zip (${url})`);
      await download(url, PLUGIN_ZIP);
      const size = fs.statSync(PLUGIN_ZIP).size;
      if (size < MIN_ZIP_SIZE) throw new Error(`downloaded file looks wrong (${size} bytes)`);
      const fd = fs.openSync(PLUGIN_ZIP, 'r');
      const magic = Buffer.alloc(4);
      fs.readSync(fd, magic, 0, 4, 0);
      fs.closeSync(fd);
      if (!(magic[0] === 0x50 && magic[1] === 0x4b)) throw new Error('downloaded file is not a zip');
      log(`plugin zip ready (${(size / 1024).toFixed(0)} KB).`);
      return;
    } catch (e) {
      lastErr = e;
      log(`attempt failed (${e.message})`);
      try { if (fs.existsSync(PLUGIN_ZIP)) fs.unlinkSync(PLUGIN_ZIP); } catch (_) { /* ignore */ }
    }
  }
  throw lastErr || new Error('no plugin download source worked');
}

// ── Plugin timeout patch ──────────────────────────────────────────────────
// The plugin's /get_pot solve timeout is hardcoded at 20 s (`_GETPOT_TIMEOUT`
// in getpot_bgutil_http.py). Cold BotGuard solves on Render's free tier
// routinely take 20-70 s (anonymous/guest identities can be slower than
// signed-in ones), so the plugin gives up at 20 s and yt-dlp runs tokenless
// (then gets bot-blocked: "Sign in to confirm you're not a bot").
// This rebuilds the plugin zip from the cloned repo sources with the timeout
// raised to 70 s (matching the server's own boot-check probe timeout). Pure
// JS (deflate via node:zlib) — no external unzip/zip tools needed, works on
// any platform. Idempotent: only rebuilds when the source still has 20.0.
const PATCHED_SOLVE_TIMEOUT = 70.0;

let CRC_TABLE;
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c;
    }
  }
  let crc = -1;
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ CRC_TABLE[(crc ^ buf[i]) & 0xff];
  return (crc ^ -1) >>> 0;
}

// Builds a valid ZIP (deflate method) from { name, data } entries — exactly
// what yt-dlp's plugin loader expects (yt_dlp_plugins/... at the zip root).
function buildPluginZip(entries) {
  const { deflateRawSync } = require('zlib');
  const localParts = [];
  const centralParts = [];
  let offset = 0;
  for (const { name: rawName, data } of entries) {
    const name = Buffer.from(rawName, 'utf8');
    const crc = crc32(data);
    const compressed = deflateRawSync(data);

    const lfh = Buffer.alloc(30);
    lfh.writeUInt32LE(0x04034b50, 0);
    lfh.writeUInt16LE(20, 4);
    lfh.writeUInt16LE(0x0800, 6); // UTF-8 flag
    lfh.writeUInt16LE(8, 8); // deflate
    lfh.writeUInt16LE(0, 10);
    lfh.writeUInt16LE(0, 12);
    lfh.writeUInt32LE(crc, 14);
    lfh.writeUInt32LE(compressed.length, 18);
    lfh.writeUInt32LE(data.length, 22);
    lfh.writeUInt16LE(name.length, 26);
    lfh.writeUInt16LE(0, 28);
    localParts.push(lfh, name, compressed);

    const ch = Buffer.alloc(46);
    ch.writeUInt32LE(0x02014b50, 0);
    ch.writeUInt16LE(20, 4);
    ch.writeUInt16LE(20, 6);
    ch.writeUInt16LE(0x0800, 8);
    ch.writeUInt16LE(8, 10);
    ch.writeUInt16LE(0, 12);
    ch.writeUInt16LE(0, 14);
    ch.writeUInt32LE(crc, 16);
    ch.writeUInt32LE(compressed.length, 20);
    ch.writeUInt32LE(data.length, 24);
    ch.writeUInt16LE(name.length, 28);
    ch.writeUInt16LE(0, 30);
    ch.writeUInt16LE(0, 32);
    ch.writeUInt16LE(0, 34);
    ch.writeUInt16LE(0, 36);
    ch.writeUInt32LE(0, 38);
    ch.writeUInt32LE(offset, 42);
    centralParts.push(ch, name);

    offset += lfh.length + name.length + compressed.length;
  }

  const centralStart = offset;
  const centralSize = centralParts.reduce((a, p) => a + p.length, 0);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(entries.length, 8);
  eocd.writeUInt16LE(entries.length, 10);
  eocd.writeUInt32LE(centralSize, 12);
  eocd.writeUInt32LE(centralStart, 16);
  eocd.writeUInt16LE(0, 20);

  return Buffer.concat([...localParts, ...centralParts, eocd]);
}

function patchPluginZip() {
  // _GETPOT_TIMEOUT lives in the base class getpot_bgutil.py — both HTTP and
  // script providers inherit it.
  const extractorDir = path.join(PROVIDER_DIR, 'plugin', 'yt_dlp_plugins', 'extractor');
  const baseSrcPath = path.join(extractorDir, 'getpot_bgutil.py');
  if (!fs.existsSync(baseSrcPath)) {
    log('plugin sources missing (provider clone incomplete) — skipping timeout patch');
    return;
  }
  const baseSrc = fs.readFileSync(baseSrcPath, 'utf8');
  if (!/_GETPOT_TIMEOUT = 20\.0/.test(baseSrc)) {
    log('plugin sources already patched or unrecognized — skipping');
    return;
  }
  const patched = baseSrc.replace(
    '_GETPOT_TIMEOUT = 20.0',
    `_GETPOT_TIMEOUT = ${PATCHED_SOLVE_TIMEOUT}`,
  );
  const read = (p) => fs.readFileSync(path.join(extractorDir, p));
  const zip = buildPluginZip([
    { name: 'yt_dlp_plugins/', data: Buffer.alloc(0) },
    { name: 'yt_dlp_plugins/extractor/', data: Buffer.alloc(0) },
    { name: 'yt_dlp_plugins/extractor/getpot_bgutil.py', data: Buffer.from(patched, 'utf8') },
    { name: 'yt_dlp_plugins/extractor/getpot_bgutil_http.py', data: read('getpot_bgutil_http.py') },
    { name: 'yt_dlp_plugins/extractor/getpot_bgutil_script.py', data: read('getpot_bgutil_script.py') },
  ]);
  fs.writeFileSync(PLUGIN_ZIP, zip);
  log(`plugin zip rebuilt with _GETPOT_TIMEOUT = ${PATCHED_SOLVE_TIMEOUT}s (${(zip.length / 1024).toFixed(0)} KB)`);
}

(async () => {
  try {
    fs.mkdirSync(PLUGINS_DIR, { recursive: true });
    await ensureProvider();
    await ensurePlugin();
    // Always run after the zip is in place; no-ops when already patched.
    patchPluginZip();
  } catch (e) {
    try { if (fs.existsSync(PLUGIN_ZIP)) fs.unlinkSync(PLUGIN_ZIP); } catch (_) { /* ignore */ }
    warn(`could not set up PO-token provider: ${e.message}`);
    warn('the server will run without PO tokens (client chains + ytdl-core only).');
  }
})();
