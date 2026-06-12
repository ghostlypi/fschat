#!/usr/bin/env bash
#
# Build fschat, start the server, and bring up a daemon per account. Idempotent:
# re-running reuses an already-running server/daemon and skips re-registration.
#
# Usage:
#   scripts/deploy-test.sh [--tls] [--no-dm] [ACCOUNT ...]
#
#   ACCOUNT is a username, or LABEL=USERNAME to give two accounts the same
#   display name (usernames are not unique).
#   Default accounts: ghostlypi antighostlypi ghostlypi3.
#
# Examples:
#   scripts/deploy-test.sh                           # ghostlypi, antighostlypi, ghostlypi3
#   scripts/deploy-test.sh alice bob
#   scripts/deploy-test.sh g1=ghostlypi g2=ghostlypi # two accounts named "ghostlypi"
#   scripts/deploy-test.sh --tls ghostlypi antighostlypi ghostlypi3
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/test-env.sh"

USE_TLS=0
AUTO_DM=1
ACCOUNTS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tls) USE_TLS=1 ;;
    --no-dm) AUTO_DM=0 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    -*) echo "unknown flag: $1" >&2; exit 2 ;;
    *) ACCOUNTS+=("$1") ;;
  esac
  shift
done
[ ${#ACCOUNTS[@]} -eq 0 ] && ACCOUNTS=(ghostlypi antighostlypi ghostlypi3)

vim_cmd() { # CHATFILE  — daemon auto-discovered from the file's chat root (.fschat-port)
  echo "  vim $1"
}
# Mirror FileStore.sanitize so we can predict the group chat's filename.
sanitize_name() {
  local s; s="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9._-]+/-/g; s/^-+//; s/-+$//')"
  [ -n "$s" ] && printf '%s' "$s" || printf 'channel'
}

mkdir -p "$ENV_DIR/accounts"

echo "==> building (./gradlew installDist)"
( cd "$ROOT" && ./gradlew installDist -q )

SRV_TLS=(); DMN_TLS=()
if [ "$USE_TLS" -eq 1 ]; then
  [ -f "$CERT_DIR/dev-keystore.p12" ] || "$ROOT/scripts/gen-dev-certs.sh" "$CERT_DIR" devpass >/dev/null
  SRV_TLS=(--keystore "$CERT_DIR/dev-keystore.p12" --keystore-pass devpass)
  DMN_TLS=(--tls --truststore "$CERT_DIR/dev-truststore.p12" --truststore-pass devpass)
fi

# ---- server ---------------------------------------------------------------
if is_running "$SERVER_PID_FILE"; then
  echo "==> server already running (pid $(cat "$SERVER_PID_FILE"))"
else
  echo "==> starting server (https :$HTTPS_PORT, ws :$WS_PORT, tls=$USE_TLS)"
  FSCHAT_JWT_SECRET="$JWT_SECRET" "$SERVER_BIN" \
    --db "$SERVER_DB" --host 0.0.0.0 --https-port "$HTTPS_PORT" --ws-port "$WS_PORT" \
    "${SRV_TLS[@]}" >"$SERVER_LOG" 2>&1 &
  echo $! > "$SERVER_PID_FILE"
  wait_for_tcp 127.0.0.1 "$WS_PORT" 30 || { echo "server failed; see $SERVER_LOG" >&2; exit 1; }
fi

# ---- accounts -------------------------------------------------------------
LABELS=(); HANDLES=(); CONFIGS=(); ROOTS=()
for entry in "${ACCOUNTS[@]}"; do
  label="${entry%%=*}"; user="${entry#*=}"
  cfg="$(acct_config_dir "$label")"; root="$(acct_root "$label")"
  hfile="$(acct_handle_file "$label")"; pidf="$(acct_pid_file "$label")"; log="$(acct_log "$label")"
  mkdir -p "$cfg" "$root"

  if [ ! -f "$cfg/token" ]; then
    echo "==> registering '$user' (label: $label)"
    out="$("$DAEMON_BIN" register "$user" --password "$PASSWORD" --host "$HOST" --auth-port "$HTTPS_PORT" \
            "${DMN_TLS[@]}" --config-dir "$cfg" --root "$root" 2>&1)" \
      || { echo "register failed for $user:" >&2; echo "$out" >&2; exit 1; }
    printf '%s\n' "$out" | sed -n 's/^registered as \([^;]*\);.*/\1/p' | head -1 > "$hfile"
  fi
  handle="$(cat "$hfile" 2>/dev/null || true)"; [ -n "$handle" ] || handle="$user"

  if is_running "$pidf"; then
    echo "==> daemon '$label' already running (pid $(cat "$pidf"))"
  else
    echo "==> starting daemon '$label'"
    "$DAEMON_BIN" start --host "$HOST" --ws-port "$WS_PORT" "${DMN_TLS[@]}" \
      --config-dir "$cfg" --root "$root" >"$log" 2>&1 &
    echo $! > "$pidf"
    wait_for_file "$cfg/port" 20 || { echo "daemon '$label' failed; see $log" >&2; exit 1; }
  fi

  LABELS+=("$label"); HANDLES+=("$handle"); CONFIGS+=("$cfg"); ROOTS+=("$root")
done

# Give every daemon a moment to authenticate before issuing ops.
sleep 2

# ---- auto-create channels --------------------------------------------------
# A DM between the first two accounts, plus one group chat with everyone.
if [ "$AUTO_DM" -eq 1 ] && [ ${#LABELS[@]} -ge 2 ]; then
  echo "==> creating DM: ${LABELS[0]} <-> ${LABELS[1]}"
  # Address by handle (unambiguous), from both sides so each gets its file.
  "$DAEMON_BIN" dm "${HANDLES[1]}" --config-dir "${CONFIGS[0]}" --root "${ROOTS[0]}" >/dev/null || true
  "$DAEMON_BIN" dm "${HANDLES[0]}" --config-dir "${CONFIGS[1]}" --root "${ROOTS[1]}" >/dev/null || true

  # Group with everyone. Group creation is NOT deduped server-side, so only
  # create it if the hub doesn't already have the group file (keeps re-runs idempotent).
  hub_group_file="${ROOTS[0]}/groups/$(sanitize_name "$GROUP_NAME").chat"
  if [ ${#LABELS[@]} -ge 3 ] && [ ! -f "$hub_group_file" ]; then
    members=()
    for i in "${!HANDLES[@]}"; do [ "$i" -eq 0 ] && continue; members+=("${HANDLES[$i]}"); done
    echo "==> creating group '$GROUP_NAME' with all ${#LABELS[@]} accounts"
    "$DAEMON_BIN" group-new "$GROUP_NAME" "${members[@]}" \
      --config-dir "${CONFIGS[0]}" --root "${ROOTS[0]}" >/dev/null || true
  fi
  sleep 1
fi

# ---- summary --------------------------------------------------------------
echo
printf '%-12s %-24s %-9s %s\n' LABEL HANDLE LOCALPORT CHATROOT
printf '%-12s %-24s %-9s %s\n' ----- ------ --------- --------
for i in "${!LABELS[@]}"; do
  printf '%-12s %-24s %-9s %s\n' \
    "${LABELS[$i]}" "${HANDLES[$i]}" "$(cat "$(acct_config_dir "${LABELS[$i]}")/port")" "${ROOTS[$i]}/dms"
done

echo
if [ ! -e "$HOME/.vim/pack/fschat/start/fschat" ]; then
  echo "One-time: install the plugin so plain \`vim <file>\` works:"
  echo "  mkdir -p ~/.vim/pack/fschat/start && ln -s $ROOT/vim ~/.vim/pack/fschat/start/fschat"
  echo
fi
if [ "$AUTO_DM" -eq 1 ] && [ ${#LABELS[@]} -ge 2 ]; then
  echo "Open chats by path (the account is auto-discovered from the file's folder):"

  # DM between the first two accounts (both sides).
  echo
  echo "  # DM: ${LABELS[0]} <-> ${LABELS[1]}"
  vim_cmd "${ROOTS[0]}/dms/${HANDLES[1]/:/-}.chat"
  vim_cmd "${ROOTS[1]}/dms/${HANDLES[0]/:/-}.chat"

  # Group chat with everyone (one command per member).
  if [ ${#LABELS[@]} -ge 3 ]; then
    gfile="groups/$(sanitize_name "$GROUP_NAME").chat"
    echo
    echo "  # GROUP '$GROUP_NAME' (all ${#LABELS[@]} accounts)"
    for i in "${!LABELS[@]}"; do
      vim_cmd "${ROOTS[$i]}/$gfile"
    done
  fi
else
  echo "No channels created (--no-dm or single account). Create one with, e.g.:"
  echo "  $DAEMON_BIN create <handle> --config-dir $(acct_config_dir "${LABELS[0]}") --root $(acct_root "${LABELS[0]}")"
fi
echo
echo "Tear down with:  scripts/teardown-test.sh        (keeps data)"
echo "             or:  scripts/teardown-test.sh --purge (wipes $ENV_DIR)"
