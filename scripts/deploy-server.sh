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

# 2b. Admin token (generated once). Its presence makes registration INVITE-ONLY and
# enables POST /admin/invite to mint codes. Generate invites with scripts/new-invite.sh.
ADMIN_FILE="$DEPLOY_DIR/admin-token"
if [ ! -s "$ADMIN_FILE" ]; then
  head -c 32 /dev/urandom | base64 | tr -d '\n' > "$ADMIN_FILE"
  chmod 600 "$ADMIN_FILE"
  say "Generated an admin token at $ADMIN_FILE (registration is now invite-only)"
fi
ADMIN="$(cat "$ADMIN_FILE")"

# 2c. Public domain (remembered): added to the self-signed cert SAN so TLS verifies
# for it until a Let's Encrypt cert takes over.
DOMAIN_FILE="$DEPLOY_DIR/domain"
if [ -n "${FSCHAT_PUBLIC_DOMAIN:-}" ]; then
  DOMAIN="$FSCHAT_PUBLIC_DOMAIN"
  printf '%s' "$DOMAIN" > "$DOMAIN_FILE"
elif [ -s "$DOMAIN_FILE" ]; then
  DOMAIN="$(cat "$DOMAIN_FILE")"
else
  DOMAIN=""
fi

HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "$HOST_IP" ] || HOST_IP="127.0.0.1"

# 3. TLS. Prefer a Let's Encrypt keystore (le-keystore.p12) if one has been installed
#    by the renewal hook; otherwise generate a self-signed cert covering localhost,
#    the LAN IP, and the public domain. Either way the server reads /certs/<file>.
SERVER_ARGS=(--host 0.0.0.0 --db /data/fschat.db --https-port 8443 --ws-port 8444)
MOUNTS=()
SCHEME="http"
CURL_TLS=()
if [ "$TLS" -eq 1 ]; then
  CERT_DIR="$DEPLOY_DIR/certs"
  mkdir -p "$CERT_DIR"
  if [ -s "$CERT_DIR/le-keystore.p12" ]; then
    KS_FILE="le-keystore.p12"
    say "Using Let's Encrypt keystore $CERT_DIR/le-keystore.p12"
  else
    KS_FILE="dev-keystore.p12"
    EXTRA_SANS="ip:$HOST_IP"
    [ -n "$DOMAIN" ] && EXTRA_SANS="$EXTRA_SANS,dns:$DOMAIN"
    say "Generating self-signed TLS cert (SAN: localhost,127.0.0.1,$EXTRA_SANS) ..."
    "$REPO_ROOT/scripts/gen-dev-certs.sh" "$CERT_DIR" "$KEYSTORE_PASS" "$EXTRA_SANS" >/dev/null
  fi
  # ':Z' relabels the dir for SELinux (Fedora enforcing) so the container can read it.
  MOUNTS=(-v "$CERT_DIR:/certs:ro,Z")
  SERVER_ARGS+=(--keystore "/certs/$KS_FILE" --keystore-pass "$KEYSTORE_PASS")
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
  -e FSCHAT_ADMIN_TOKEN="$ADMIN" \
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
  say "Registration is INVITE-ONLY. Mint a code for each tester:"
  say "    scripts/new-invite.sh"
  say ""
  if [ "$KS_FILE" = "le-keystore.p12" ]; then
    say "Public Let's Encrypt cert in use — testers need only the daemon + an invite"
    say "(host ${DOMAIN:-the public domain}, ports, and TLS are baked into the client):"
    say "  fschat-daemon register <name> --invite <code>"
    say "  fschat-daemon create <peer>   # then edit the .chat file it prints in vim"
  else
    say "Self-signed cert: also give each tester the truststore (password: $KEYSTORE_PASS):"
    say "    $DEPLOY_DIR/certs/dev-truststore.p12"
    say "  export FSCHAT_TRUSTSTORE=<path>/dev-truststore.p12 FSCHAT_TRUSTSTORE_PASS=$KEYSTORE_PASS"
    say "  fschat-daemon register <name> --invite <code>   # host/ports/TLS baked in"
    say "  (off-LAN testers reach ${DOMAIN:-your domain}; on-LAN use $HOST_IP)"
  fi
else
  say "Beta testers connect their daemon to this host:"
  say "  fschat-daemon register <name> --host $HOST_IP --auth-port $HTTPS_PORT"
  say "  fschat-daemon start    --host $HOST_IP --ws-port $WS_PORT &"
  say "  NOTE: PLAINTEXT — passwords/messages are not encrypted. Use --tls for a networked beta."
fi
say ""
say "  Manage:  $RT logs -f $NAME   |   $RT restart $NAME   |   $RT stop $NAME"
