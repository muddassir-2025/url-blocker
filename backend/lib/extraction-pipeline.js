/**
 * Extraction pipeline building blocks for the ClearView audio backend.
 *
 * Pure, dependency-free logic, kept separate from server.js so it can be unit
 * tested without booting the HTTP server (or the PO-token provider):
 *
 *   - createExtractionQueue: a single-flight FIFO queue paced by a token
 *     bucket. This is what keeps the service under YouTube's guest-session
 *     rate ceiling (~300 requests/hour per session/IP) and — because it
 *     serializes extraction — keeps the shared guest cookiejar free of
 *     concurrent read/modify/write races.
 *   - createStreamCache: in-memory TTL cache of extracted stream URLs keyed by
 *     video id. Cache hits are served without touching yt-dlp or the PO-token
 *     provider at all, which is the main lever for staying under the rate
 *     ceiling when many users download the same popular RSS episodes.
 *   - classifyExtractionError / isTransientExtractionError: map raw yt-dlp
 *     failures to user-facing verdicts so "needs sign-in" is never shown as a
 *     generic error and monitoring can distinguish boot-check noise from real
 *     request failures.
 */
'use strict';

/** ms between two tokens at the given hourly budget. */
function msPerToken(perHour) {
  return 3600000 / perHour;
}

/**
 * Single-flight FIFO queue with token-bucket pacing.
 *
 * `perHour` is the hourly request budget (the guest-session ceiling),
 * `burst` the number of back-to-back calls allowed when the bucket is full.
 * Jobs run strictly one at a time. A job that is still queued when its caller
 * calls cancel() is skipped (its promise never settles). The returned handle
 * exposes the job's position for queue-occupancy reporting.
 */
function createExtractionQueue({ perHour = 250, burst = 5, avgExtractMs = 6000 } = {}) {
  perHour = Number(perHour) > 0 ? Number(perHour) : 250;
  burst = Number(burst) > 0 ? Number(burst) : 5;
  const tokenEvery = msPerToken(perHour);
  let tokens = burst;
  let lastRefill = Date.now();
  const jobs = [];
  let busy = false;
  let timer = null;

  function refill() {
    const now = Date.now();
    tokens = Math.min(burst, tokens + (now - lastRefill) / tokenEvery);
    lastRefill = now;
  }

  function pump() {
    if (busy || !jobs.length) return;
    refill();
    if (tokens < 1) {
      // No token yet — wake up when the next one lands (poll capped at 2 s so
      // the deadline is never far away regardless of the budget).
      if (!timer) {
        const ms = Math.max(250, Math.min(Math.ceil((1 - tokens) * tokenEvery), 2000));
        timer = setTimeout(() => {
          timer = null;
          pump();
        }, ms);
      }
      return;
    }
    // Skip jobs cancelled while queued WITHOUT spending a token on them (a
    // pile of timed-out requests must not silently drain the rate budget).
    while (jobs.length && jobs[0].cancelled) jobs.shift();
    if (!jobs.length) return;
    tokens -= 1;
    const job = jobs.shift();
    job.started = true;
    busy = true;
    Promise.resolve()
      .then(job.task)
      .then(
        (v) => {
          busy = false;
          job.resolve(v);
          pump();
        },
        (e) => {
          busy = false;
          job.reject(e);
          pump();
        }
      );
  }

  return {
    /** Enqueues [task]; returns { promise, cancel, position, started }. */
    push(task) {
      const job = {
        task,
        cancelled: false,
        started: false,
        // pump() dequeues synchronously during push, so "in line" means every
        // job already queued PLUS the one currently being served (if any).
        position: jobs.length + (busy ? 1 : 0) + 1,
        resolve: null,
        reject: null,
      };
      job.promise = new Promise((resolve, reject) => {
        job.resolve = resolve;
        job.reject = reject;
      });
      jobs.push(job);
      pump();
      return {
        promise: job.promise,
        cancel: () => {
          job.cancelled = true;
        },
        position: () => job.position,
        started: () => job.started,
      };
    },
    /** Number of jobs currently queued (excluding the one being served). */
    depth() {
      return jobs.length;
    },
    /** Rough estimate (ms) until the job at [position] starts. */
    estimateWaitMs(position) {
      refill();
      const behind = Math.max(0, position - 1);
      const extractMs = behind * avgExtractMs;
      // Once the burst is spent, tokens land `tokenEvery` ms apart.
      const pacingMs = Math.max(0, behind - tokens) * tokenEvery;
      return Math.round(extractMs + pacingMs);
    },
    // Exposed for tests.
    _refill() {
      refill();
    },
    _tokens() {
      return tokens;
    },
  };
}

/**
 * In-memory TTL cache of extracted stream URLs, keyed by video id. Only the
 * tiny fields needed to pipe the stream are stored (the full
 * --dump-single-json blob is several MB and must not live here). Oldest-entry
 * eviction keeps the map bounded.
 */
function createStreamCache({ ttlMs = 180 * 60 * 1000, maxEntries = 500 } = {}) {
  const map = new Map();
  return {
    size() {
      return map.size;
    },
    get(videoId) {
      if (!videoId) return null;
      const hit = map.get(videoId);
      if (!hit) return null;
      if (Date.now() - hit.at >= ttlMs) {
        map.delete(videoId);
        return null;
      }
      return hit;
    },
    put(videoId, entry) {
      if (!videoId) return;
      if (map.size >= maxEntries) {
        const oldest = map.keys().next().value;
        if (oldest !== undefined) map.delete(oldest);
      }
      map.set(videoId, {
        url: entry.url,
        ext: entry.ext,
        size: entry.size,
        source: entry.source,
        at: Date.now(),
      });
    },
  };
}

// ── Error classification ─────────────────────────────────────────────────
// Order matters: LOGIN first (a hard, non-retryable verdict), then BOTCHECK
// (the guest-session rate/bot block — retryable, YouTube-side), then generic
// TRANSIENT network noise, then UNKNOWN.
const LOGIN_RE =
  /LOGIN_REQUIRED|Private video|This video is private|available to members only|members-only|age.restrict|confirm your age|Sign in to view this video/i;
const BOTCHECK_RE =
  /Sign in to confirm you're not a bot|HTTP Error 429|HTTP Error 403/i;
const TRANSIENT_RE =
  /timed out|timeout|ECONNRESET|EPIPE|ETIMEDOUT|ENETUNREACH|ENOTFOUND|EAI_AGAIN|temporary failure/i;

function errorText(e) {
  const stderr = (e && e.stderr) || '';
  const message = (e && e.message) || '';
  return `${String(stderr)}\n${String(message)}`;
}

/**
 * Maps a raw extraction failure to { code, status, retryable, message }.
 * status doubles as the HTTP status the server answers with:
 *   403 login-required → shown to the user immediately (the app does not
 *       retry <500), 503 bot-check/rate-limit → retried by the app,
 *   502 transient network, 500 unknown.
 */
function classifyExtractionError(e) {
  const text = errorText(e);
  if (LOGIN_RE.test(text)) {
    return {
      code: 'login',
      status: 403,
      retryable: false,
      message:
        "This video requires a YouTube sign-in (private, age-restricted, or members-only) and can't be downloaded without one.",
    };
  }
  if (BOTCHECK_RE.test(text)) {
    return {
      code: 'botcheck',
      status: 503,
      retryable: true,
      message:
        "YouTube is blocking this server's requests right now (bot check). Try again in a few minutes.",
    };
  }
  if (TRANSIENT_RE.test(text)) {
    return {
      code: 'transient',
      status: 502,
      retryable: true,
      message: 'The connection to YouTube failed. Try again in a moment.',
    };
  }
  return {
    code: 'unknown',
    status: 500,
    retryable: true,
    message:
      'Could not fetch this audio right now. YouTube changes its internals often — try again in a few minutes.',
  };
}

/** True when the failure is worth a single backoff retry across the chains. */
function isTransientExtractionError(e) {
  const code = classifyExtractionError(e).code;
  return code === 'botcheck' || code === 'transient';
}

module.exports = {
  createExtractionQueue,
  createStreamCache,
  classifyExtractionError,
  isTransientExtractionError,
  msPerToken,
};
