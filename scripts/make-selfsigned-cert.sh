#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- a self-signed certificate for an internal deployment
# =====================================================================
# For a VM on a private network, where Let's Encrypt cannot issue anything --
# there is no public hostname to prove ownership of and no inbound port 80 from
# the internet. This produces a certificate the deployment can serve so traffic
# is encrypted in transit.
#
#     ./scripts/make-selfsigned-cert.sh inventory.corp.local 10.20.30.40
#
# Every name and address you will actually type into a browser should be an
# argument, because a certificate is only valid for the names inside it. Reach
# the box by IP when the certificate only lists a hostname and the browser
# objects -- correctly.
#
# Then set, in .env:
#     TLS_MODE=provided
#     APP_HOSTNAME=inventory.corp.local
#
# **Browsers will still warn.** Nothing signed this but itself, so there is no
# chain of trust to check -- the warning is the browser doing its job, not a
# fault to work around. It buys encryption, not identity. If the organisation
# runs its own CA, a certificate from it is strictly better: same TLS_MODE, no
# warning, and revocation actually works. Use this when there is no such CA.
# =====================================================================
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <hostname> [additional-hostname-or-ip ...]" >&2
  echo >&2
  echo "Example: $0 inventory.corp.local 10.20.30.40" >&2
  exit 1
fi

command -v openssl > /dev/null 2>&1 || {
  echo "openssl is not on PATH. Install it, or use a certificate from your own CA." >&2
  exit 1
}

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${ROOT}/deploy/nginx/certs"
DAYS="${CERT_DAYS:-825}"
PRIMARY="$1"

mkdir -p "$OUT"

# Subject Alternative Names are what browsers actually check; the common name
# has been ignored for years. Every argument becomes one, sorted into DNS or IP
# entries by whether it parses as an address.
alt=""
index_dns=0
index_ip=0
for name in "$@"; do
  if [[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    index_ip=$((index_ip + 1))
    alt="${alt}IP.${index_ip}:${name},"
  else
    index_dns=$((index_dns + 1))
    alt="${alt}DNS.${index_dns}:${name},"
  fi
done
alt="${alt%,}"

echo "Generating a self-signed certificate"
echo "  primary name : ${PRIMARY}"
echo "  valid for    : ${alt}"
echo "  days         : ${DAYS}"
echo

openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "${OUT}/privkey.pem" \
  -out "${OUT}/fullchain.pem" \
  -days "$DAYS" \
  -subj "/CN=${PRIMARY}" \
  -addext "subjectAltName=${alt}" \
  -addext "basicConstraints=critical,CA:FALSE" \
  -addext "keyUsage=critical,digitalSignature,keyEncipherment" \
  -addext "extendedKeyUsage=serverAuth" 2> /dev/null

# The key is readable by the nginx container through a read-only bind mount, so
# it never needs to be group- or world-readable on the host.
chmod 600 "${OUT}/privkey.pem"
chmod 644 "${OUT}/fullchain.pem"

echo "Wrote:"
echo "  ${OUT}/fullchain.pem"
echo "  ${OUT}/privkey.pem"
echo
echo "Now set in .env:"
echo "  TLS_MODE=provided"
echo "  APP_HOSTNAME=${PRIMARY}"
echo
echo "Then: cd deploy && docker compose up -d nginx"
echo
echo "Expires: $(openssl x509 -enddate -noout -in "${OUT}/fullchain.pem" | cut -d= -f2)"
