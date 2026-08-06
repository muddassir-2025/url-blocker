#!/usr/bin/env bash
# Local end-to-end test for the ClearView audio backend.
# Starts server.js (which spawns its own PO-token provider), waits for the
# boot-check verdict lines, then exercises GET /api/audio (twice — the second
# call must hit the stream cache) and prints a summary.
set -u
ROOT='C:/Users/mukht/AndroidStudioProjects/urlblocker2/backend'
LOG=/tmp/server_test2.log

# 1. Make sure no stale provider is squatting on 4416.
for PID in $(netstat -ano | grep ':4416' | grep LISTENING | awk '{print $NF}' | sort -u); do
  taskkill //PID "$PID" //F >/dev/null 2>&1
done
sleep 1

# 2. Start the server.
cd "$ROOT" || exit 1
node server.js > "$LOG" 2>&1 &
SERVER_PID=$!
echo "server pid $SERVER_PID"

# 3. Wait for HTTP to come up.
for i in $(seq 1 30); do
  if curl -s -m 2 http://127.0.0.1:3000/health >/dev/null 2>&1; then
    echo "server up after ${i}s"
    break
  fi
  sleep 1
done

# 4a. Wait for the FIRST probe A verdict (the 503 gate opens right after it).
#     The early /ping + version lines are NOT enough. Cold solve up to ~120s.
echo 'waiting for the first probe-A verdict (cold BotGuard solve up to ~120s)...'
for i in $(seq 1 60); do
  if grep -qE '\[boot-check\] PRIMARY chain .*extraction|DEBUG_BOOT_CHECK=false' "$LOG" 2>/dev/null; then
    echo "boot check (gate) finished after ~$((i * 3))s"
    break
  fi
  sleep 3
done

# 4b. Wait for ALL videos' probe-A verdicts (they run in the background, so
#     the per-video summary below is complete). Skipped when probes are off.
if grep -q 'DEBUG_BOOT_CHECK=false' "$LOG" 2>/dev/null; then
  echo 'probes skipped (DEBUG_BOOT_CHECK=false) — skipping the per-video wait'
else
  PROBE_COUNT=$(grep -oE 'probing [0-9]+ video' "$LOG" | grep -oE '[0-9]+' | head -1)
  PROBE_COUNT=${PROBE_COUNT:-4}
  echo "waiting for all $PROBE_COUNT probe-A verdicts..."
  for i in $(seq 1 50); do
    DONE=$(grep -cE '\[boot-check\] PRIMARY chain .*extraction' "$LOG" 2>/dev/null || echo 0)
    if [ "${DONE:-0}" -ge "$PROBE_COUNT" ]; then
      echo "all $PROBE_COUNT probe-A verdicts in after ~$((i * 3))s"
      break
    fi
    sleep 3
  done
fi
sleep 2

echo '=== boot-check / provider lines ==='
grep -E 'boot-check|PO-token provider|get_pot probe|Generating POT|guest cookiejar|version' "$LOG" | head -25

echo '=== per-video probe verdicts ==='
grep -E 'probing [0-9]+ video|PRIMARY chain .*extraction|FALLBACK mobile chain .*extraction|provider GENERATED|provider saw NO token|output tail' "$LOG"

echo '=== /health ==='
curl -s -m 5 http://127.0.0.1:3000/health
echo

echo '=== /api/audio test 1 (cold extraction) ==='
time curl -s -m 120 'http://127.0.0.1:3000/api/audio?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DjNQXAC9IVRw' -o /tmp/audio_test.m4a -D /tmp/audio_test_headers.txt -w 'HTTP %{http_code} size %{size_download}\n'
head -c 32 /tmp/audio_test.m4a | xxd | head -2
grep -i 'x-audio-source\|x-audio-error-code' /tmp/audio_test_headers.txt

echo '=== /api/audio test 2 (must be a CACHE hit — no extraction) ==='
curl -s -m 30 'http://127.0.0.1:3000/api/audio?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DjNQXAC9IVRw' -o /tmp/audio_test2.m4a -D /tmp/audio_test_headers2.txt -w 'HTTP %{http_code} size %{size_download}\n'
grep -i 'x-audio-source\|x-audio-error-code' /tmp/audio_test_headers2.txt

echo '=== /api/audio test 3 (a REAL video from the RSS feed this app serves) ==='
time curl -s -m 180 'http://127.0.0.1:3000/api/audio?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DC_iHHP8LfGk' -o /tmp/audio_feed.m4a -D /tmp/audio_feed_headers.txt -w 'HTTP %{http_code} size %{size_download}\n'
grep -i 'x-audio-source\|x-audio-error-code' /tmp/audio_feed_headers.txt

echo '=== guest cookiejar (should now contain visitor cookies) ==='
GUEST_JAR="$(node -e "console.log(require('os').tmpdir() + '/clearview-guest-cookies.txt')")"
wc -l "$GUEST_JAR" 2>/dev/null || echo 'guest jar not found'
grep -ciE 'youtube|google' "$GUEST_JAR" 2>/dev/null | sed 's/^/youtube\/google cookie rows: /'

echo '=== request-path log lines (FULL COMMAND + cache + chains) ==='
grep -E 'FULL COMMAND|\[cache\]|chain .*failed|request activity' "$LOG" | tail -8

echo '=== server log tail ==='
tail -8 "$LOG"

kill "$SERVER_PID" 2>/dev/null
