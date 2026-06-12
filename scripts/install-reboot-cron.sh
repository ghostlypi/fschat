#!/usr/bin/env bash
#
# Add a @reboot cron job that restarts the fschat server container after a reboot.
# Idempotent: re-running replaces the existing entry.
set -euo pipefail

NAME="fschat-server"
LOG="$HOME/.fschat-deploy/reboot.log"
MARKER="# fschat-server-autostart"

RT="$(command -v podman || command -v docker || true)"
[ -n "$RT" ] || { echo "error: need podman or docker on PATH" >&2; exit 1; }
command -v crontab >/dev/null 2>&1 || { echo "error: 'crontab' not found" >&2; exit 1; }
mkdir -p "$(dirname "$LOG")"

# Give the system time to bring up networking + the user runtime, then start the
# container. $(id -u) stays literal so cron's shell expands it at boot.
LINE="@reboot sleep 30 && XDG_RUNTIME_DIR=/run/user/\$(id -u) $RT start $NAME >> $LOG 2>&1 $MARKER"
( crontab -l 2>/dev/null | grep -vF "$MARKER" || true; printf '%s\n' "$LINE" ) | crontab -

echo "Installed @reboot entry:"
crontab -l | grep -F "$MARKER"

# Rootless podman needs the user's runtime (/run/user/<uid>) at boot — enable lingering.
if [ "$(basename "$RT")" = "podman" ] && command -v loginctl >/dev/null 2>&1; then
  if [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null || echo no)" = "yes" ]; then
    echo "Linger already enabled for $USER."
  elif loginctl enable-linger "$USER" 2>/dev/null; then
    echo "Enabled linger for $USER (rootless podman will have /run/user at boot)."
  else
    echo "NOTE: could not enable linger non-interactively. For the @reboot start to work"
    echo "      with rootless podman, run once:   sudo loginctl enable-linger $USER"
  fi
fi
