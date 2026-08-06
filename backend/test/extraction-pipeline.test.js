/**
 * Unit tests for backend/lib/extraction-pipeline.js (plain node, no deps).
 * Run:  node test/extraction-pipeline.test.js
 */
'use strict';

const assert = require('assert');
const {
  createExtractionQueue,
  createStreamCache,
  classifyExtractionError,
  isTransientExtractionError,
  msPerToken,
} = require('../lib/extraction-pipeline');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0;
const ok = (name) => {
  passed++;
  console.log(`  ok - ${name}`);
};

(async () => {
  // ── msPerToken ─────────────────────────────────────────────────────────
  console.log('# msPerToken');
  assert.strictEqual(msPerToken(300), 12000, '300/hr = 1 token per 12 s');
  assert.strictEqual(msPerToken(250), 14400, '250/hr = 1 token per 14.4 s');
  ok('hourly budget maps to a token interval');

  // ── Queue: serial order + depth + positions ────────────────────────────
  console.log('# createExtractionQueue');
  {
    const q = createExtractionQueue({ perHour: 100000, burst: 100, avgExtractMs: 1 }); // effectively unlimited
    const order = [];
    const h1 = q.push(async () => { await sleep(5); order.push('a'); return 'A'; });
    const h2 = q.push(async () => { await sleep(5); order.push('b'); return 'B'; });
    const h3 = q.push(async () => { await sleep(5); order.push('c'); return 'C'; });
    assert.strictEqual(h1.position(), 1);
    assert.strictEqual(h2.position(), 2);
    assert.strictEqual(h3.position(), 3);
    // The first job is dequeued synchronously by pump(), so depth() is 2.
    assert.strictEqual(q.depth(), 2, 'two still queued behind the one in flight');
    const [a, b, c] = await Promise.all([h1.promise, h2.promise, h3.promise]);
    assert.deepStrictEqual([a, b, c], ['A', 'B', 'C']);
    assert.deepStrictEqual(order, ['a', 'b', 'c'], 'strictly serial, FIFO');
    assert.strictEqual(q.depth(), 0);
    ok('jobs run strictly one at a time in FIFO order with positions');
  }

  // ── Queue: a job cancelled while queued is skipped and never starts ────
  {
    const q = createExtractionQueue({ perHour: 100000, burst: 100 });
    let secondStarted = false;
    const h1 = q.push(async () => { await sleep(20); return 1; });
    const h2 = q.push(async () => { secondStarted = true; return 2; });
    h2.cancel(); // cancel the QUEUED job (the first is already in flight)
    assert.strictEqual(await h1.promise, 1);
    await sleep(30);
    assert.strictEqual(secondStarted, false, 'cancelled job must never start');
    assert.strictEqual(q.depth(), 0, 'queue drains after the cancelled job is skipped');
    ok('a job cancelled while queued is skipped');
  }

  // ── Queue: handles expose started() (for the wait-cap timer) ───────────
  {
    const q = createExtractionQueue({ perHour: 100000, burst: 100 });
    const h1 = q.push(async () => { await sleep(20); return 1; });
    const h2 = q.push(async () => 2);
    assert.strictEqual(h1.started(), true, 'dequeued job reports started immediately');
    assert.strictEqual(h2.started(), false, 'queued job reports not started');
    await Promise.all([h1.promise, h2.promise]);
    ok('handles expose started()');
  }

  // ── Queue: cancelled-while-queued jobs do NOT consume rate-bucket tokens ─
  {
    // 7200/hr → 1 token / 500 ms, burst 2. h1 takes the first token; h2 is
    // cancelled while queued; h3 must get the SPARE token immediately — if the
    // cancelled job had eaten it, h3 would wait ~500 ms for a refill.
    const q = createExtractionQueue({ perHour: 7200, burst: 2 });
    const order = [];
    const t0 = Date.now();
    let h3StartedAt = 0;
    const h1 = q.push(async () => { await sleep(10); order.push('a'); });
    const h2 = q.push(async () => { order.push('b'); });
    const h3 = q.push(async () => { h3StartedAt = Date.now(); order.push('c'); });
    h2.cancel();
    await Promise.all([h1.promise, h3.promise]);
    const wait = h3StartedAt - t0;
    assert.deepStrictEqual(order, ['a', 'c'], 'cancelled job skipped');
    assert.ok(wait < 300, `third job used the spare token (waited ${wait}ms)`);
    ok('cancelled-while-queued jobs do not consume rate-bucket tokens');
  }

  // ── Queue: token bucket paces back-to-back pushes ──────────────────────
  {
    // 7200/hr → one token every 500 ms, burst 1 → the 2nd job must wait ~500 ms.
    const q = createExtractionQueue({ perHour: 7200, burst: 1 });
    const startedAt = Date.now();
    let secondStartedAt = 0;
    const h1 = q.push(async () => { await sleep(10); return 1; });
    const h2 = q.push(async () => { secondStartedAt = Date.now(); await sleep(10); return 2; });
    assert.strictEqual(h2.position(), 2);
    assert.strictEqual(q.estimateWaitMs(2) > 0, true, 'wait estimate positive under budget pressure');
    await h1.promise;
    await sleep(50);
    assert.strictEqual(secondStartedAt, 0, 'second job must still be waiting for a token');
    await h2.promise;
    const wait = secondStartedAt - startedAt;
    assert.ok(wait >= 300, `second job was paced by the bucket (waited ~${wait}ms)`);
    ok('token bucket paces jobs below the hourly ceiling');
  }

  // ── Queue: rejection propagates and the queue keeps working ────────────
  {
    const q = createExtractionQueue({ perHour: 100000, burst: 100 });
    const bad = q.push(async () => { throw new Error('boom'); });
    const good = q.push(async () => 'fine');
    await assert.rejects(bad.promise, /boom/);
    assert.strictEqual(await good.promise, 'fine');
    ok('a rejected job does not wedge the queue');
  }

  // ── Route wait-cap semantics: 503 only while the job is still QUEUED ───
  {
    const q = createExtractionQueue({ perHour: 100000, burst: 100 });
    const cap = 50;
    // (a) A job that has already STARTED must ride out its own extraction:
    //     the wait-cap timer must NOT cancel it or the client gets a 503 while
    //     its work completes and is discarded.
    const result = await new Promise((resolve, reject) => {
      const handle = q.push(async () => { await sleep(150); return 'ok'; });
      const timer = setTimeout(() => {
        if (!handle.started()) {
          handle.cancel();
          reject(new Error('QUEUE_TIMEOUT'));
        }
      }, cap);
      handle.promise.then(
        (v) => { clearTimeout(timer); resolve(v); },
        (e) => { clearTimeout(timer); reject(e); }
      );
    });
    assert.strictEqual(result, 'ok', 'started job completes despite the wait-cap timer');

    // (b) A job still queued past the cap IS cancelled → QUEUE_TIMEOUT.
    const q2 = createExtractionQueue({ perHour: 100000, burst: 1 });
    const slow = q2.push(async () => { await sleep(200); return 'slow'; });
    const queued = q2.push(async () => 'fast');
    let outcome;
    await new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (!queued.started()) {
          queued.cancel();
          outcome = 'QUEUE_TIMEOUT';
        }
        resolve();
      }, cap);
      queued.promise.then(() => { clearTimeout(timer); resolve(); });
    });
    assert.strictEqual(outcome, 'QUEUE_TIMEOUT', 'queued job past the cap is shed');
    assert.strictEqual(await slow.promise, 'slow');
    ok('wait-cap sheds queued jobs but never discards started work');
  }

  // ── Stream cache ───────────────────────────────────────────────────────
  console.log('# createStreamCache');
  {
    const c = createStreamCache({ ttlMs: 60000, maxEntries: 3 });
    c.put('v1', { url: 'u1', ext: 'm4a', size: 10, source: 'yt-dlp' });
    const hit = c.get('v1');
    assert.ok(hit, 'cache hit');
    assert.strictEqual(hit.url, 'u1');
    assert.strictEqual(hit.source, 'yt-dlp');
    assert.strictEqual(c.size(), 1);
    assert.strictEqual(c.get('missing'), null);
    ok('put/get round-trips and misses return null');
  }
  {
    // TTL expiry.
    const c = createStreamCache({ ttlMs: 20, maxEntries: 3 });
    c.put('v1', { url: 'u1' });
    await sleep(30);
    assert.strictEqual(c.get('v1'), null, 'expired entry is a miss and is removed');
    assert.strictEqual(c.size(), 0);
    ok('entries expire after ttlMs');
  }
  {
    // Oldest-entry eviction when at capacity.
    const c = createStreamCache({ ttlMs: 60000, maxEntries: 2 });
    c.put('a', { url: 'ua' });
    c.put('b', { url: 'ub' });
    c.put('c', { url: 'uc' });
    assert.strictEqual(c.size(), 2);
    assert.strictEqual(c.get('a'), null, 'oldest evicted');
    assert.ok(c.get('b') && c.get('c'));
    ok('oldest entries are evicted at capacity');
  }

  // ── Error classification ───────────────────────────────────────────────
  console.log('# classifyExtractionError');
  {
    const mk = (stderr = '', message = '') => Object.assign(new Error(message), { stderr });

    let v = classifyExtractionError(mk('ERROR: [youtube] xyz: LOGIN_REQUIRED'));
    assert.strictEqual(v.code, 'login');
    assert.strictEqual(v.status, 403);
    assert.strictEqual(v.retryable, false);

    v = classifyExtractionError(mk('', 'This video is private'));
    assert.strictEqual(v.code, 'login');

    v = classifyExtractionError(mk('This video is age-restricted and only available on YouTube'));
    assert.strictEqual(v.code, 'login');

    v = classifyExtractionError(mk('This video is available to members only'));
    assert.strictEqual(v.code, 'login');
    ok('login-required failures classify as 403 login');

    v = classifyExtractionError(mk('ERROR: unable to download video data: HTTP Error 429: Too Many Requests'));
    assert.strictEqual(v.code, 'botcheck');
    assert.strictEqual(v.status, 503);
    assert.strictEqual(v.retryable, true);

    v = classifyExtractionError(mk('', "Sign in to confirm you're not a bot"));
    assert.strictEqual(v.code, 'botcheck');
    ok('bot-check / 429 classify as retryable 503 botcheck');

    v = classifyExtractionError(mk('ERROR: [generic] timed out'));
    assert.strictEqual(v.code, 'transient');
    assert.strictEqual(v.status, 502);

    v = classifyExtractionError(mk('some unknown python traceback'));
    assert.strictEqual(v.code, 'unknown');
    assert.strictEqual(v.status, 500);
    ok('timeouts → transient 502, everything else → unknown 500');

    assert.strictEqual(isTransientExtractionError(mk('HTTP Error 429')), true);
    assert.strictEqual(isTransientExtractionError(mk('LOGIN_REQUIRED')), false);
    assert.strictEqual(isTransientExtractionError(mk('weird error')), false);
    ok('isTransientExtractionError only true for botcheck/transient');
  }

  console.log(`\n${passed} assertions passed.`);
})().catch((e) => {
  console.error('FAIL:', e.message);
  console.error(e.stack);
  process.exit(1);
});
