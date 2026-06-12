#!/usr/bin/env bash
#
# Obtain a Let's Encrypt certificate for the fschat public domain using the
# Cloudflare DNS-01 challenge, convert it into the PKCS12 keystore the server
# reads (le-keystore.p12), install a renewal deploy-hook, and redeploy.
#
# Run with sudo from your normal user (certbot needs root; the container is
# rootless, so we drop back to you for the podman parts):
#
#   sudo FSCHAT_LE_EMAIL=you@example.com bash scripts/letsencrypt-setup.sh
#
# Prerequisite: a Cloudflare API token with Zone:DNS:Edit on the domain's zone,
# saved at ~/.fschat-deploy/cloudflare.ini (chmod 600) as:
#   dns_cloudflare_api_token = <TOKEN>
set -euo pipefail

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = "0" ] || die "run with sudo (certbot needs root): sudo bash scripts/letsencrypt-setup.sh"
RUN_USER="${SUDO_USER:-}"
[ -n "$RUN_USER" ] && [ "$RUN_USER" != "root" ] \
    || die "run via 'sudo' from your normal login (need rootless podman as that user)"

USER_HOME="$(getent passwd "$RUN_USER" | cut -d: -f6)"
RUN_UID="$(id -u "$RUN_USER")"
DEPLOY_DIR="${FSCHAT_DEPLOY_DIR:-$USER_HOME/.fschat-deploy}"
CERT_DIR="$DEPLOY_DIR/certs"
CF_INI="$DEPLOY_DIR/cloudflare.ini"
KEYSTORE_PASS="${FSCHAT_KEYSTORE_PASS:-devpass}"
DOMAIN="$(cat "$DEPLOY_DIR/domain" 2>/dev/null || true)"
[ -n "$DOMAIN" ] || die "no domain recorded; deploy once with FSCHAT_PUBLIC_DOMAIN=<domain> first"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIVE="/etc/letsencrypt/live/$DOMAIN"

[ -s "$CF_INI" ] || die "missing $CF_INI (Cloudflare token file — see header)"
chmod 600 "$CF_INI" 2>/dev/null || true

# 1. certbot + Cloudflare plugin. Guard on the PLUGIN, not certbot: certbot may
#    already be installed without the DNS-01 plugin (then --dns-cloudflare-* args
#    are "unrecognized"). 'certbot plugins' lists what's actually loadable.
if ! certbot plugins 2>/dev/null | grep -q 'dns-cloudflare'; then
  echo "Installing certbot + Cloudflare DNS plugin ..."
  dnf install -y certbot python3-certbot-dns-cloudflare
fi

# 2. Issue (or reuse) the certificate via DNS-01
EMAIL_ARGS=(--register-unsafely-without-email)
[ -n "${FSCHAT_LE_EMAIL:-}" ] && EMAIL_ARGS=(-m "$FSCHAT_LE_EMAIL")
echo "Requesting certificate for $DOMAIN via Cloudflare DNS-01 ..."
certbot certonly --non-interactive --agree-tos "${EMAIL_ARGS[@]}" \
  --dns-cloudflare --dns-cloudflare-credentials "$CF_INI" \
  --dns-cloudflare-propagation-seconds 30 \
  -d "$DOMAIN"

# 3. Convert PEM -> PKCS12 keystore the server reads (owned by the run user)
build_keystore() {
  mkdir -p "$CERT_DIR"
  openssl pkcs12 -export \
    -in "$LIVE/fullchain.pem" -inkey "$LIVE/privkey.pem" \
    -name fschat -passout "pass:$KEYSTORE_PASS" \
    -out "$CERT_DIR/le-keystore.p12"
  chown "$RUN_USER:$RUN_USER" "$CERT_DIR/le-keystore.p12"
  chmod 644 "$CERT_DIR/le-keystore.p12"  # world-readable so the rootless container's user can read it (key is password-protected)
}
build_keystore

# 4. Renewal deploy-hook: rebuild the keystore and redeploy as the run user.
#    Going through deploy-server.sh re-applies the SELinux ':Z' relabel on the
#    cert dir, which a bare 'podman restart' would not.
HOOK="/etc/letsencrypt/renewal-hooks/deploy/fschat.sh"
mkdir -p "$(dirname "$HOOK")"
cat > "$HOOK" <<HOOK_EOF
#!/usr/bin/env bash
set -e
openssl pkcs12 -export \\
  -in "$LIVE/fullchain.pem" -inkey "$LIVE/privkey.pem" \\
  -name fschat -passout "pass:$KEYSTORE_PASS" \\
  -out "$CERT_DIR/le-keystore.p12"
chown $RUN_USER:$RUN_USER "$CERT_DIR/le-keystore.p12"
chmod 640 "$CERT_DIR/le-keystore.p12"
sudo -u $RUN_USER XDG_RUNTIME_DIR=/run/user/$RUN_UID bash "$REPO_ROOT/scripts/deploy-server.sh" --tls
HOOK_EOF
chmod +x "$HOOK"

# 5. Make sure certbot's renewal timer is active
systemctl enable --now certbot-renew.timer 2>/dev/null \
  || systemctl enable --now certbot.timer 2>/dev/null || true

# 6. Redeploy now so the server picks up le-keystore.p12 (prefers it automatically)
echo "Redeploying with the Let's Encrypt keystore ..."
sudo -u "$RUN_USER" XDG_RUNTIME_DIR="/run/user/$RUN_UID" \
  bash "$REPO_ROOT/scripts/deploy-server.sh" --tls

echo ""
echo "Done. $DOMAIN is now served with a Let's Encrypt cert (auto-renews via the certbot timer)."
echo "Verify:  echo | openssl s_client -connect $DOMAIN:7443 -servername $DOMAIN 2>/dev/null | openssl x509 -noout -issuer -dates"
