#!/usr/bin/env bash
#
# Stop the fschat test server and all daemons started by deploy-test.sh.
#
# Usage:
#   scripts/teardown-test.sh           # stop processes, keep data
#   scripts/teardown-test.sh --purge   # stop processes AND delete the env dir
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/test-env.sh"

PURGE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --purge|--wipe) PURGE=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
  shift
done

stop_pid() { # PIDFILE NAME
  local pf="$1" name="$2" pid
  if is_running "$pf"; then
    pid="$(cat "$pf")"
    echo "==> stopping $name (pid $pid)"
    kill "$pid" 2>/dev/null || true
    # Wait up to ~3s for graceful JVM shutdown, then force-kill.
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
    if kill -0 "$pid" 2>/dev/null; then kill -9 "$pid" 2>/dev/null || true; fi
  fi
  rm -f "$pf"
}

if [ -d "$ENV_DIR/accounts" ]; then
  for d in "$ENV_DIR"/accounts/*/; do
    [ -d "$d" ] || continue
    stop_pid "${d}daemon.pid" "daemon $(basename "$d")"
  done
fi
stop_pid "$SERVER_PID_FILE" "server"

# Belt-and-suspenders: catch anything started from the install dirs that lost its pidfile.
pkill -f "$ROOT/fschat-server/build/install" 2>/dev/null || true
pkill -f "$ROOT/fschat-daemon/build/install" 2>/dev/null || true

if [ "$PURGE" -eq 1 ]; then
  echo "==> purging $ENV_DIR"
  rm -rf "$ENV_DIR"
fi

echo "done."
