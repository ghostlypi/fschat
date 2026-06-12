#!/usr/bin/env bash
#
# fschat server installer. Intended for:
#
#   curl -fsSL https://github.com/<org>/<repo>/releases/latest/download/install-server.sh | bash
#
# Downloads the prebuilt server distribution from the GitHub release, installs it
# under ~/.fschat, and symlinks the launcher onto your PATH.
#
# Env overrides:
#   FSCHAT_REPO        GitHub "org/repo"      (default: ghostlypi/fschat)
#   FSCHAT_PREFIX      install dir            (default: ~/.fschat)
#   FSCHAT_BIN         dir for the launcher   (default: ~/.local/bin)
#   FSCHAT_SERVER_TAR  tar URL or local path  (default: latest release asset)
set -euo pipefail

REPO="${FSCHAT_REPO:-ghostlypi/fschat}"
PREFIX="${FSCHAT_PREFIX:-$HOME/.fschat}"
BIN_DIR="${FSCHAT_BIN:-$HOME/.local/bin}"
TAR_SRC="${FSCHAT_SERVER_TAR:-https://github.com/$REPO/releases/latest/download/fschat-server.tar}"

say() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

command -v java >/dev/null 2>&1 || die "Java 21+ is required, but 'java' was not found on PATH."
say "Using $(java -version 2>&1 | head -1)"
command -v tar >/dev/null 2>&1 || die "'tar' is required."

say "Installing the fschat server to $PREFIX ..."
mkdir -p "$PREFIX"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
if [ -f "$TAR_SRC" ]; then
  cp "$TAR_SRC" "$tmp/s.tar"
else
  command -v curl >/dev/null 2>&1 || die "'curl' is required to download $TAR_SRC"
  curl -fL --progress-bar "$TAR_SRC" -o "$tmp/s.tar" || die "download failed: $TAR_SRC"
fi
rm -rf "$PREFIX/fschat-server"
tar -xf "$tmp/s.tar" -C "$PREFIX"
[ -x "$PREFIX/fschat-server/bin/fschat-server" ] || die "archive did not contain bin/fschat-server"

mkdir -p "$BIN_DIR"
ln -sf "$PREFIX/fschat-server/bin/fschat-server" "$BIN_DIR/fschat-server"
say "Installed launcher: $BIN_DIR/fschat-server"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) say "NOTE: $BIN_DIR is not on your PATH. Add this to your shell profile:"
     say "        export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac

say ""
say "Done. Start the server:"
say "  export FSCHAT_JWT_SECRET=\"\$(head -c 32 /dev/urandom | base64)\"   # keep this stable"
say "  fschat-server --db ~/fschat.db --https-port 8443 --ws-port 8444"
say "For TLS add:  --keystore <p12> --keystore-pass <password>  (see scripts/gen-dev-certs.sh)"
