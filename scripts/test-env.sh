# Shared config + helpers for the fschat test environment.
# Sourced by deploy-test.sh and teardown-test.sh — not run directly.
#
# Override any of these via the environment, e.g.:
#   FSCHAT_TEST_DIR=/tmp/fsc FSCHAT_WS_PORT=9444 scripts/deploy-test.sh

TEST_ENV_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$TEST_ENV_LIB_DIR/.." && pwd)"

ENV_DIR="${FSCHAT_TEST_DIR:-$ROOT/.fschat-test}"
HOST="${FSCHAT_HOST:-localhost}"
HTTPS_PORT="${FSCHAT_HTTPS_PORT:-8443}"
WS_PORT="${FSCHAT_WS_PORT:-8444}"
JWT_SECRET="${FSCHAT_JWT_SECRET:-fschat-test-secret-change-me-0123456789}"
PASSWORD="${FSCHAT_TEST_PASSWORD:-password1}"
GROUP_NAME="${FSCHAT_GROUP_NAME:-everyone}"

SERVER_BIN="$ROOT/fschat-server/build/install/fschat-server/bin/fschat-server"
DAEMON_BIN="$ROOT/fschat-daemon/build/install/fschat-daemon/bin/fschat-daemon"

SERVER_PID_FILE="$ENV_DIR/server.pid"
SERVER_LOG="$ENV_DIR/server.log"
SERVER_DB="$ENV_DIR/server.db"
CERT_DIR="$ENV_DIR/certs"

# is_running PIDFILE -> 0 if the recorded process is alive.
is_running() {
  local pf="$1" pid
  [ -f "$pf" ] || return 1
  pid="$(cat "$pf" 2>/dev/null)" || return 1
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# wait_for_tcp HOST PORT [TIMEOUT_S]
wait_for_tcp() {
  local host="$1" port="$2" deadline=$(( SECONDS + ${3:-30} ))
  while (( SECONDS < deadline )); do
    (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null && { exec 3>&- 3<&-; return 0; }
    sleep 0.1
  done
  return 1
}

# wait_for_file PATH [TIMEOUT_S]
wait_for_file() {
  local f="$1" deadline=$(( SECONDS + ${2:-20} ))
  while (( SECONDS < deadline )); do [ -f "$f" ] && return 0; sleep 0.1; done
  return 1
}

# Per-account paths (label = directory key; may differ from username).
acct_config_dir() { echo "$ENV_DIR/accounts/$1/config"; }
acct_root()       { echo "$ENV_DIR/accounts/$1/root"; }
acct_handle_file(){ echo "$ENV_DIR/accounts/$1/handle"; }
acct_pid_file()   { echo "$ENV_DIR/accounts/$1/daemon.pid"; }
acct_log()        { echo "$ENV_DIR/accounts/$1/daemon.log"; }
