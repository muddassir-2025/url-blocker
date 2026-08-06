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

# 4. Wait for the boot check to FINISH (cold solve can take up to ~120s).
#    End-of-boot markers: the probe A / probe B verdict lines, or the
#    DEBUG_BOOT_CHECK=false skip line. The early /ping + version lines are NOT
#    enough — the 503 boot-window gate stays closed until probe A completes.
echo 'waiting for boot-check verdicts (cold BotGuard solve up to ~120s)...'
for i in $(seq 1 60); do
  if grep -qE '\[boot-check\] (PRIMARY|FALLBACK) chain .*extraction|DEBUG_BOOT_CHECK=false|provider GENERATED|provider saw NO token' "$LOG" 2>/dev/null; then
    echo "boot check finished after ~$((i * 3))s"
    break
  fi
  sleep 3
done
sleep 2

echo '=== boot-check / provider lines ==='
grep -E 'boot-check|PO-token provider|get_pot probe|Generating POT|guest cookiejar|version' "$LOG" | head -20

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

echo '=== guest cookiejar (should now contain visitor cookies) ==='
GUEST_JAR="$(node -e "console.log(require('os').tmpdir() + '/clearview-guest-cookies.txt')")"
wc -l "$GUEST_JAR" 2>/dev/null || echo 'guest jar not found'
grep -ciE 'youtube|google' "$GUEST_JAR" 2>/dev/null | sed 's/^/youtube\/google cookie rows: /'

echo '=== request-path log lines (FULL COMMAND + cache + chains) ==='
grep -E 'FULL COMMAND|\[cache\]|chain .*failed|request activity' "$LOG" | tail -8

echo '=== server log tail ==='
tail -8 "$LOG"

kill "$SERVER_PID" 2>/dev/null
