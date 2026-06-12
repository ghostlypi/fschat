#!/usr/bin/env bash
#
# Deploy the fschat server as a persistent local container (podman or docker).
# Idempotent: re-running recreates the container but keeps the DB volume and the
# JWT secret, so existing accounts and tokens survive.
#
#   scripts/deploy-server.sh           # plaintext (trusted LAN / dev only)
#   scripts/deploy-server.sh --tls     # TLS with a self-signed cert covering this host's IP
#
# Env overrides:
#   FSCHAT_RUNTIME       podman|docker         (default: autodetect)
#   FSCHAT_HTTPS_PORT    host auth port        (default: 8443)
#   FSCHAT_WS_PORT       host stream port      (default: 8444)
#   FSCHAT_DEPLOY_DIR    secret + certs dir    (default: ~/.fschat-deploy)
#   FSCHAT_KEYSTORE_PASS keystore password     (default: devpass)
set -euo pipefail

say() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_DIR="${FSCHAT_DEPLOY_DIR:-$HOME/.fschat-deploy}"
KEYSTORE_PASS="${FSCHAT_KEYSTORE_PASS:-devpass}"
IMAGE="fschat-server:latest"
NAME="fschat-server"
VOLUME="fschat-data"

TLS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --tls) TLS=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

RT="${FSCHAT_RUNTIME:-}"
[ -n "$RT" ] || RT="$(command -v podman || command -v docker || true)"
[ -n "$RT" ] || die "need podman or docker on PATH"
RT="$(basename "$RT")"
say "Using container runtime: $RT"

# 1. Image (build if missing)
if ! $RT image inspect "$IMAGE" >/dev/null 2>&1; then
  say "Building $IMAGE ..."
  $RT build -t "$IMAGE" "$REPO_ROOT"
fi

mkdir -p "$DEPLOY_DIR"

# Host ports: an explicit FSCHAT_HTTPS_PORT/FSCHAT_WS_PORT wins AND is remembered;
# otherwise reuse the previously saved choice; otherwise fall back to the defaults.
# This lets you move off 8443/8444 once and have every later redeploy keep the change.
PORTS_FILE="$DEPLOY_DIR/ports"
if [ -n "${FSCHAT_HTTPS_PORT:-}" ] || [ -n "${FSCHAT_WS_PORT:-}" ]; then
  HTTPS_PORT="${FSCHAT_HTTPS_PORT:-8443}"
  WS_PORT="${FSCHAT_WS_PORT:-8444}"
  printf 'HTTPS_PORT=%s\nWS_PORT=%s\n' "$HTTPS_PORT" "$WS_PORT" > "$PORTS_FILE"
elif [ -s "$PORTS_FILE" ]; then
  # shellcheck disable=SC1090
  . "$PORTS_FILE"
else
  HTTPS_PORT=8443
  WS_PORT=8444
fi
say "Host ports: $HTTPS_PORT (auth/https) and $WS_PORT (stream/wss)"

# 2. Stable JWT secret (generated once, reused on redeploy so tokens keep verifying)
SECRET_FILE="$DEPLOY_DIR/jwt-secret"
if [ ! -s "$SECRET_FILE" ]; then
  head -c 32 /dev/urandom | base64 | tr -d '\n' > "$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
  say "Generated a new JWT secret at $SECRET_FILE"
fi
SECRET="$(cat "$SECRET_FILE")"

HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "$HOST_IP" ] || HOST_IP="127.0.0.1"

# 3. TLS (self-signed cert covering localhost + this host's LAN IP)
SERVER_ARGS=(--host 0.0.0.0 --db /data/fschat.db --https-port 8443 --ws-port 8444)
MOUNTS=()
SCHEME="http"
CURL_TLS=()
if [ "$TLS" -eq 1 ]; then
  CERT_DIR="$DEPLOY_DIR/certs"
  say "Generating self-signed TLS cert for localhost + $HOST_IP ..."
  "$REPO_ROOT/scripts/gen-dev-certs.sh" "$CERT_DIR" "$KEYSTORE_PASS" "ip:$HOST_IP" >/dev/null
  # ':Z' relabels the dir for SELinux (Fedora enforcing) so the container can read it.
  MOUNTS=(-v "$CERT_DIR:/certs:ro,Z")
  SERVER_ARGS+=(--keystore /certs/dev-keystore.p12 --keystore-pass "$KEYSTORE_PASS")
  SCHEME="https"
  CURL_TLS=(-k)
fi

# 4. Persistent DB volume + (re)create the container
$RT volume inspect "$VOLUME" >/dev/null 2>&1 || $RT volume create "$VOLUME" >/dev/null
$RT rm -f "$NAME" >/dev/null 2>&1 || true
say "Starting '$NAME' ($SCHEME) on :$HTTPS_PORT (auth) and :$WS_PORT (stream) ..."
# Publish on all IPv4 interfaces (loopback + LAN). NOTE: rootless podman/pasta does
# not usefully forward IPv6, so connect via 127.0.0.1 or the LAN IP, not `localhost`
# (which resolves to ::1 on some hosts). The daemon defaults to --host 127.0.0.1.
$RT run -d --name "$NAME" \
  --restart unless-stopped \
  -p "${HTTPS_PORT}:8443" -p "${WS_PORT}:8444" \
  -e FSCHAT_JWT_SECRET="$SECRET" \
  -v "${VOLUME}:/data" \
  "${MOUNTS[@]}" \
  "$IMAGE" "${SERVER_ARGS[@]}" >/dev/null

# 5. Poll the auth endpoint until it serves (bogus creds -> 401, creates no data)
code="000"
for i in $(seq 1 60); do
  code="$(curl -s "${CURL_TLS[@]}" -o /dev/null -w '%{http_code}' \
    -X POST "$SCHEME://127.0.0.1:${HTTPS_PORT}/login" \
    -H 'Content-Type: application/json' -d '{"username":"__probe__","password":"x"}' 2>/dev/null || echo 000)"
  [ "$code" = "401" ] && break
  sleep 0.25
done
[ "$code" = "401" ] \
  && say "Health probe OK ($SCHEME auth endpoint returned 401 for bogus creds)." \
  || say "WARNING: probe returned '$code' (expected 401). Check: $RT logs $NAME"

# 6. Connection info for beta testers
say ""
say "Deployed. $($RT ps --filter "name=$NAME" --format '{{.Names}} -> {{.Status}}')"
say ""
if [ "$TLS" -eq 1 ]; then
  say "TLS is on. Give each tester the truststore (password: $KEYSTORE_PASS):"
  say "    $DEPLOY_DIR/certs/dev-truststore.p12"
  say "They set these once (e.g. in ~/.bashrc), pointing at their copy of that file:"
  say "  export FSCHAT_HOST=$HOST_IP FSCHAT_AUTH_PORT=$HTTPS_PORT FSCHAT_WS_PORT=$WS_PORT FSCHAT_TLS=true"
  say "  export FSCHAT_TRUSTSTORE=<path>/dev-truststore.p12 FSCHAT_TRUSTSTORE_PASS=$KEYSTORE_PASS"
  say "and then register once (the daemon installs itself as an always-on service):"
  say "  fschat-daemon register <name>"
  say "  fschat-daemon create <peer>   # then edit the .chat file it prints in vim"
  say "  (connect via $HOST_IP — NOT 'localhost', which may resolve to IPv6 and fail)"
else
  say "Beta testers connect their daemon to this host:"
  say "  fschat-daemon register <name> --host $HOST_IP --auth-port $HTTPS_PORT"
  say "  fschat-daemon start    --host $HOST_IP --ws-port $WS_PORT &"
  say "  NOTE: PLAINTEXT — passwords/messages are not encrypted. Use --tls for a networked beta."
fi
say ""
say "  Manage:  $RT logs -f $NAME   |   $RT restart $NAME   |   $RT stop $NAME"
