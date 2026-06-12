#!/usr/bin/env bash
#
# Mint a single-use registration invite code from the running fschat server.
#
#   scripts/new-invite.sh            # print one fresh code
#   scripts/new-invite.sh 5          # print five
#
# Talks to POST /admin/invite on the local server, authenticated with the admin
# token that deploy-server.sh generated at ~/.fschat-deploy/admin-token. Run it on
# the server host (it hits the auth port on 127.0.0.1).
set -euo pipefail

DEPLOY_DIR="${FSCHAT_DEPLOY_DIR:-$HOME/.fschat-deploy}"
ADMIN_FILE="$DEPLOY_DIR/admin-token"
PORTS_FILE="$DEPLOY_DIR/ports"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

[ -s "$ADMIN_FILE" ] || die "no admin token at $ADMIN_FILE — deploy with scripts/deploy-server.sh first"
ADMIN="$(cat "$ADMIN_FILE")"

PORT=7443
[ -s "$PORTS_FILE" ] && PORT="$(. "$PORTS_FILE"; echo "$HTTPS_PORT")"
PORT="${FSCHAT_HTTPS_PORT:-$PORT}"

COUNT="${1:-1}"
[[ "$COUNT" =~ ^[0-9]+$ ]] || die "count must be a number"

for _ in $(seq 1 "$COUNT"); do
  resp="$(curl -fsSk -X POST "https://127.0.0.1:${PORT}/admin/invite" \
    -H "Authorization: Bearer $ADMIN" 2>/dev/null)" \
    || die "request failed (is the server up on :$PORT with TLS? check: podman logs fschat-server)"
  # Extract the JSON "code" value without needing jq.
  code="$(printf '%s' "$resp" | sed -E 's/.*"code"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/')"
  [ -n "$code" ] && [ "$code" != "$resp" ] || die "unexpected response: $resp"
  printf '%s\n' "$code"
done
