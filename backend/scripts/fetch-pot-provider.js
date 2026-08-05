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

(async () => {
  try {
    fs.mkdirSync(PLUGINS_DIR, { recursive: true });
    await ensureProvider();
    await ensurePlugin();
  } catch (e) {
    try { if (fs.existsSync(PLUGIN_ZIP)) fs.unlinkSync(PLUGIN_ZIP); } catch (_) { /* ignore */ }
    warn(`could not set up PO-token provider: ${e.message}`);
    warn('the server will run without PO tokens (client chains + ytdl-core only).');
  }
})();
