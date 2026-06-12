#!/usr/bin/env bash
# Generate a self-signed dev TLS keystore (for the server) and a matching
# truststore (for the daemon). FOR LOCAL DEVELOPMENT ONLY.
#
# Usage: scripts/gen-dev-certs.sh [out-dir] [store-pass] [extra-SANs]
#   extra-SANs: comma-separated, appended to the cert (e.g. "ip:192.168.1.9,dns:myhost")
#               so daemons connecting to that address pass hostname verification.
set -euo pipefail

OUT="${1:-./certs}"
PASS="${2:-devpass}"
EXTRA_SANS="${3:-}"
KEYSTORE="$OUT/dev-keystore.p12"
TRUSTSTORE="$OUT/dev-truststore.p12"
CERT="$OUT/dev-cert.pem"

SAN="dns:localhost,ip:127.0.0.1"
[ -n "$EXTRA_SANS" ] && SAN="$SAN,$EXTRA_SANS"

mkdir -p "$OUT"
rm -f "$KEYSTORE" "$TRUSTSTORE" "$CERT"

# Server keypair + self-signed cert. SAN must list every address testers connect to.
keytool -genkeypair \
  -alias fschat -keyalg RSA -keysize 2048 -validity 3650 \
  -storetype PKCS12 -keystore "$KEYSTORE" -storepass "$PASS" \
  -dname "CN=localhost, O=fschat-dev" \
  -ext "SAN=$SAN"

# Export the cert and import it into a client truststore.
keytool -exportcert -rfc \
  -alias fschat -keystore "$KEYSTORE" -storepass "$PASS" -file "$CERT"

keytool -importcert -noprompt \
  -alias fschat -file "$CERT" \
  -storetype PKCS12 -keystore "$TRUSTSTORE" -storepass "$PASS"

echo "wrote:"
echo "  keystore   : $KEYSTORE   (server --keystore, --keystore-pass $PASS)"
echo "  truststore : $TRUSTSTORE (daemon --truststore, --truststore-pass $PASS)"
