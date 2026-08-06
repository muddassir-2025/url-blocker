/**
 * Runtime yt-dlp updater.
 *
 * Checks GitHub for the latest yt-dlp release and — when it differs from the
 * pinned binary in backend/bin/ — downloads and atomically swaps it. This is
 * the answer to "version lag is a common cause of sudden download failures":
 * between deploys (where scripts/fetch-ytdlp.js re-pins the verified build),
 * the server keeps the binary fresh on a schedule (see server.js
 * scheduleYtDlpUpdate, or run this script from any cron).
 *
 * Safety rails:
 *   - Never breaks anything: any failure logs a warning and exits 0 — the
 *     current binary keeps working.
 *   - Gate file bin/.last-ytdlp-update-check limits GitHub API calls to once
 *     per YTDLP_UPDATE_INTERVAL_HOURS (default 24) so a frequently
 *     cold-starting Render free instance does not hammer the API.
 *   - Size + magic checks reject truncated / HTML downloads, and the swap is
 *     an atomic rename (retried once on Windows if the binary is in use).
 *
 * Env: YTDLP_FORCE=1 forces a check now, YTDLP_UPDATE_INTERVAL_HOURS changes
 * the gate (default 24).
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
const GATE_FILE = path.join(destDir, '.last-ytdlp-update-check');
const MIN_SIZE = 1 * 1024 * 1024; // yt-dlp Linux asset is a ~3 MB zipapp
const MIN_HOURS = Number(process.env.YTDLP_UPDATE_INTERVAL_HOURS || 24);

function log(msg) {
  console.log(`[update-ytdlp] ${msg}`);
}

function binaryVersion(bin) {
  return new Promise((resolve) => {
    execFile(bin, ['--version'], { timeout: 15000, encoding: 'utf8' }, (err, stdout) => {
      if (err) return resolve(null);
      resolve(String(stdout || '').trim().split(/\s+/)[0] || null);
    });
  });
}

function latestReleaseTag() {
  return new Promise((resolve, reject) => {
    const req = https.get(
      'https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest',
      { headers: { 'User-Agent': 'clearview-audio-backend', Accept: 'application/vnd.github+json' } },
      (res) => {
        let body = '';
        res.on('data', (d) => (body += d));
        res.on('end', () => {
          if (res.statusCode !== 200) return reject(new Error(`GitHub API HTTP ${res.statusCode}`));
          try {
            const tag = String(JSON.parse(body).tag_name || '').trim();
            if (!tag) return reject(new Error('empty tag_name in GitHub response'));
            resolve(tag);
          } catch (e) {
            reject(new Error(`bad GitHub response: ${String(e.message || e).slice(0, 80)}`));
          }
        });
      }
    );
    req.on('error', reject);
    req.setTimeout(20000, () => req.destroy(new Error('GitHub API timed out')));
  });
}

function download(url, target) {
  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(target);
    const fail = (e) => {
      file.close();
      try { fs.unlinkSync(target); } catch (_) { /* ignore */ }
      reject(e);
    };
    const req = https.get(url, { headers: { 'User-Agent': 'clearview-audio-backend' } }, (res) => {
      const status = res.statusCode || 0;
      // GitHub /releases/download/* redirects to the real asset.
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

(async () => {
  try {
    if (!fs.existsSync(dest) || fs.statSync(dest).size < MIN_SIZE) {
      log('no usable pinned binary yet — nothing to update.');
      return;
    }

    // Gate: check GitHub at most once per interval, unless forced.
    if (process.env.YTDLP_FORCE !== '1') {
      const last = fs.existsSync(GATE_FILE) ? Number(fs.readFileSync(GATE_FILE, 'utf8')) : 0;
      if (last && Date.now() - last < MIN_HOURS * 3600 * 1000) {
        log(`last check was < ${MIN_HOURS}h ago — skipping (set YTDLP_FORCE=1 to override).`);
        return;
      }
    }

    const current = await binaryVersion(dest);
    const latest = await latestReleaseTag();
    log(`current ${current || '?'} / latest ${latest}`);
    if (!latest || latest === current) {
      // Stamp the gate only after a SUCCESSFUL check, so failures retry soon.
      fs.writeFileSync(GATE_FILE, String(Date.now()));
      return;
    }

    log(`new yt-dlp release ${latest} — downloading...`);
    const tmp = dest + '.new';
    await download(`https://github.com/yt-dlp/yt-dlp/releases/download/${latest}/${fileName}`, tmp);

    const size = fs.statSync(tmp).size;
    if (size < MIN_SIZE) throw new Error(`downloaded file looks wrong (${size} bytes)`);
    const fd = fs.openSync(tmp, 'r');
    const magic = Buffer.alloc(4);
    fs.readSync(fd, magic, 0, 4, 0);
    fs.closeSync(fd);
    if (magic[0] === 0x3c) throw new Error('downloaded file is HTML, not the yt-dlp binary');
    if (!isWin) fs.chmodSync(tmp, 0o755);

    // Atomic swap; on Windows an in-flight download can briefly lock the old
    // binary — retry once after 3 s, then give up and keep the old one.
    try {
      fs.renameSync(tmp, dest);
    } catch (e) {
      log(`swap blocked (${e.message}) — retrying in 3s`);
      await new Promise((r) => setTimeout(r, 3000));
      fs.renameSync(tmp, dest);
    }
    const ver = await binaryVersion(dest);
    // Stamp the gate only AFTER the swap succeeded — a failed download must
    // be retried soon, not blocked for the whole interval.
    fs.writeFileSync(GATE_FILE, String(Date.now()));
    log(`updated to ${ver || latest} (${(size / 1024 / 1024).toFixed(1)} MB).`);
  } catch (e) {
    try { if (fs.existsSync(dest + '.new')) fs.unlinkSync(dest + '.new'); } catch (_) { /* ignore */ }
    // Never break anything: the current binary keeps working.
    log(`WARN: update failed (${e.message}) — keeping the current binary.`);
  }
})();
