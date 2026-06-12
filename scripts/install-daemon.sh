#!/usr/bin/env bash
#
# fschat daemon (client) installer. Intended for:
#
#   curl -fsSL https://github.com/<org>/<repo>/releases/latest/download/install-daemon.sh | bash
#
# Downloads the prebuilt daemon distribution from the GitHub release, installs it
# under ~/.fschat, symlinks the launcher onto your PATH, and (optionally) adds the
# bundled Vim plugin to your ~/.vimrc so `vim <file>.chat` just works.
#
# Env overrides:
#   FSCHAT_REPO        GitHub "org/repo"        (default: ghostlypi/fschat)
#   FSCHAT_PREFIX      install dir              (default: ~/.fschat)
#   FSCHAT_BIN         dir for the launcher     (default: ~/.local/bin)
#   FSCHAT_DAEMON_TAR  tar URL or local path    (default: latest release asset)
#   VIMRC              vimrc to edit            (default: ~/.vimrc)
set -euo pipefail

REPO="${FSCHAT_REPO:-ghostlypi/fschat}"
PREFIX="${FSCHAT_PREFIX:-$HOME/.fschat}"
BIN_DIR="${FSCHAT_BIN:-$HOME/.local/bin}"
TAR_SRC="${FSCHAT_DAEMON_TAR:-https://github.com/$REPO/releases/download/v0.0.1/fschat-daemon.tar}"
VIMRC="${VIMRC:-$HOME/.vimrc}"

say() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- prerequisites ---------------------------------------------------------
command -v java >/dev/null 2>&1 || die "Java 21+ is required, but 'java' was not found on PATH."
say "Using $(java -version 2>&1 | head -1)"
command -v tar >/dev/null 2>&1 || die "'tar' is required."

# --- fetch + extract -------------------------------------------------------
say "Installing the fschat daemon to $PREFIX ..."
mkdir -p "$PREFIX"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
if [ -f "$TAR_SRC" ]; then
  cp "$TAR_SRC" "$tmp/d.tar"
else
  command -v curl >/dev/null 2>&1 || die "'curl' is required to download $TAR_SRC"
  curl -fL --progress-bar "$TAR_SRC" -o "$tmp/d.tar" || die "download failed: $TAR_SRC"
fi
rm -rf "$PREFIX/fschat-daemon"
tar -xf "$tmp/d.tar" -C "$PREFIX"
[ -x "$PREFIX/fschat-daemon/bin/fschat-daemon" ] || die "archive did not contain bin/fschat-daemon"

# --- launcher on PATH ------------------------------------------------------
mkdir -p "$BIN_DIR"
ln -sf "$PREFIX/fschat-daemon/bin/fschat-daemon" "$BIN_DIR/fschat-daemon"
say "Installed launcher: $BIN_DIR/fschat-daemon"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) say "NOTE: $BIN_DIR is not on your PATH. Add this to your shell profile:"
     say "        export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac

# --- Vim plugin ------------------------------------------------------------
PLUGIN_DIR="$PREFIX/fschat-daemon/vim"
if [ -d "$PLUGIN_DIR" ]; then
  if grep -qs 'fschat plugin (managed)' "$VIMRC" 2>/dev/null; then
    say "fschat Vim plugin already configured in $VIMRC"
  else
    # Answer non-interactively with FSCHAT_ADD_VIMRC=yes|no, else prompt on the terminal.
    ans="${FSCHAT_ADD_VIMRC:-}"
    if [ -z "$ans" ]; then
      printf 'Add the fschat Vim plugin to %s so `vim <file>.chat` works? [y/N] ' "$VIMRC"
      [ -r /dev/tty ] && read -r ans </dev/tty || true
    fi
    case "$ans" in
      [yY] | [yY][eE][sS])
        {
          printf '\n" >>> fschat plugin (managed) >>>\n'
          printf 'set runtimepath^=%s\n' "$PLUGIN_DIR"
          printf '" <<< fschat plugin (managed) <<<\n'
        } >> "$VIMRC"
        say "Added the plugin to $VIMRC"
        ;;
      *)
        say "Skipped. To enable it later, add to your vimrc:"
        say "        set runtimepath^=$PLUGIN_DIR"
        ;;
    esac
  fi
fi

say ""
say "Done. Get started:"
say "  fschat-daemon register <username> --host <server-host>"
say "  fschat-daemon start --host <server-host> &"
say "  fschat-daemon create <user>        # then open the .chat file it prints with vim"
