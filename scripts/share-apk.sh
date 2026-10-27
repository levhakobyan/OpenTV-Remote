#!/usr/bin/env bash
# Run this yourself to share only the Android phone APK via a temporary Cloudflare URL.
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="$PROJECT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
PORT="${PORT:-8787}"
command -v cloudflared >/dev/null || { echo 'Install cloudflared first: brew install cloudflared'; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3 is required.'; exit 1; }
[[ -f "$APK_PATH" ]] || { echo 'APK missing. From the project root, build it first: ./android/gradlew -p android assembleDebug'; exit 1; }
[[ "$PORT" =~ ^[0-9]+$ ]] && (( PORT > 1024 && PORT < 65536 )) || { echo 'PORT must be between 1025 and 65535'; exit 1; }
SHARE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/opentv-remote-apk.XXXXXX")"
SERVER_PID=''
cleanup() {
  if [[ -n "$SERVER_PID" ]]; then kill "$SERVER_PID" 2>/dev/null || true; fi
  rm -rf -- "$SHARE_DIR"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
python3 "$PROJECT_DIR/tools/prepare_downloads.py" --output "$SHARE_DIR"
APK_SHA="$(shasum -a 256 "$SHARE_DIR/opentv-remote-debug.apk" | awk '{print $1}')"
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$SHARE_DIR" > "$SHARE_DIR/server.log" 2>&1 &
SERVER_PID=$!
# Verify this exact server started; do not tunnel an unrelated process already on the port.
READY=false
for _ in 1 2 3 4 5; do
  kill -0 "$SERVER_PID" 2>/dev/null || { cat "$SHARE_DIR/server.log"; exit 1; }
  if python3 - "$PORT" "$APK_SHA" <<'PY'
import sys, urllib.request
try:
    with urllib.request.urlopen('http://127.0.0.1:'+sys.argv[1]+'/',timeout=1) as r:
        assert sys.argv[2] in r.read().decode()
except Exception: sys.exit(1)
PY
  then READY=true; break; fi
  sleep 0.3
done
kill -0 "$SERVER_PID" 2>/dev/null || { cat "$SHARE_DIR/server.log"; exit 1; }
[[ "$READY" == true ]] || { echo 'The APK server did not become ready.'; exit 1; }
echo 'Open the https://…trycloudflare.com URL printed below on your phone.'
echo 'Temporary public link: keep this terminal open. Ctrl-C stops sharing.'
echo "APK SHA-256: $APK_SHA"
cloudflared tunnel --url "http://127.0.0.1:$PORT" --no-autoupdate
