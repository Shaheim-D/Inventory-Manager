#!/bin/sh
# =====================================================================
# Inventory Manager -- reverse proxy entrypoint
# =====================================================================
# Picks the nginx config that can actually start, given what exists on disk,
# and keeps re-checking so the answer can change while it runs.
#
# The problem this solves: nginx will not start when `ssl_certificate` names a
# file that is not there. That single fact made the shipped stack unreachable
# on a fresh machine -- the proxy crash-looped, so nothing served the ACME
# challenge, so no certificate was ever issued, so the proxy kept crash-looping.
# The documented first install told you to start nginx and then run certbot
# through it, which could never work.
#
# Three modes, set by TLS_MODE in .env:
#
#   none         Plain HTTP. For a VM on an internal network, reached by IP or
#                by an internal DNS name, where there is no certificate to have
#                and no public address to prove ownership of. Nothing is
#                encrypted: correct for a private LAN, wrong for the internet.
#
#   provided     TLS using a certificate the operator supplies -- an internal
#                CA, or a self-signed pair from scripts/make-selfsigned-cert.sh.
#                Mount them in and name them in TLS_CERT_FILE / TLS_KEY_FILE.
#
#   letsencrypt  TLS with automatic issuance and renewal. Needs a real public
#                hostname resolving to this machine and inbound port 80 from
#                the internet, so it does not apply to an internal VM.
#                Starts on HTTP so certbot has something to answer the
#                challenge through, then picks up the certificate by itself.
# =====================================================================
set -eu

TLS_MODE="${TLS_MODE:-none}"
APP_HOSTNAME="${APP_HOSTNAME:-localhost}"
RENDERED=/etc/nginx/conf.d/default.conf
TEMPLATES=/etc/nginx/app-templates

render() {
    cert=""
    key=""

    case "$TLS_MODE" in
        none)
            template="$TEMPLATES/app-http.conf.template"
            ;;
        provided)
            cert="${TLS_CERT_FILE:-/etc/nginx/certs/fullchain.pem}"
            key="${TLS_KEY_FILE:-/etc/nginx/certs/privkey.pem}"
            if [ -f "$cert" ] && [ -f "$key" ]; then
                template="$TEMPLATES/app-https.conf.template"
            else
                # Serving plainly beats not serving. Say so loudly rather than
                # refusing to start and leaving somebody reading crash logs.
                echo "nginx: TLS_MODE=provided but ${cert} or ${key} is missing." >&2
                echo "nginx: falling back to plain HTTP so the application stays reachable." >&2
                template="$TEMPLATES/app-http.conf.template"
            fi
            ;;
        letsencrypt)
            cert="/etc/letsencrypt/live/${APP_HOSTNAME}/fullchain.pem"
            key="/etc/letsencrypt/live/${APP_HOSTNAME}/privkey.pem"
            if [ -f "$cert" ] && [ -f "$key" ]; then
                template="$TEMPLATES/app-https.conf.template"
            else
                echo "nginx: no certificate for ${APP_HOSTNAME} yet; serving HTTP so the" >&2
                echo "nginx: ACME challenge can be answered. Will switch to TLS once issued." >&2
                template="$TEMPLATES/app-http.conf.template"
            fi
            ;;
        *)
            echo "nginx: unknown TLS_MODE '${TLS_MODE}'. Use none, provided, or letsencrypt." >&2
            exit 1
            ;;
    esac

    # An explicit variable list, so nginx's own $host, $scheme and friends are
    # left alone. The image's default entrypoint substitutes everything in the
    # environment, which is fine until a variable happens to share a name.
    APP_HOSTNAME="$APP_HOSTNAME" TLS_CERT_FILE="$cert" TLS_KEY_FILE="$key" \
        envsubst '${APP_HOSTNAME} ${TLS_CERT_FILE} ${TLS_KEY_FILE}' \
        < "$template" > "$RENDERED.new"

    if [ -f "$RENDERED" ] && cmp -s "$RENDERED" "$RENDERED.new"; then
        rm -f "$RENDERED.new"
        return 1   # unchanged
    fi
    mv "$RENDERED.new" "$RENDERED"
    return 0       # changed
}

# ---------------------------------------------------------------------
# Heal a stale upstream before doing anything else.
#
# scripts/update.sh points this at `app-next` for the length of a swap, and
# `app-next` only exists while an update is running. If the machine reboots in
# that window, nginx would come up pointing at a container that is not there --
# and nginx resolves upstream names when it LOADS the config, not per request,
# so it would refuse to start at all. A stalled update would become a total
# outage, on the reboot rather than at the time, which is the worst way to find
# out about it.
#
# `app` is the container that always exists and always carries a restart policy,
# so it is the safe thing to fall back to.
UPSTREAM=/etc/nginx/runtime/upstream.conf
mkdir -p /etc/nginx/runtime

write_default_upstream() {
    cat > "$UPSTREAM" <<'DEFAULT'
# Which application container nginx sends traffic to.
#
# Written by nginx/entrypoint.sh, and rewritten by scripts/update.sh during an
# update so traffic can move between two running versions with a reload --
# which finishes in-flight requests on the old worker before retiring it, so
# nobody sees an error and nobody is signed out (sessions are rows in Postgres,
# not memory in the container).
#
# Between updates this always names `app`. `app-next` exists only while an
# update is running.
upstream inventory_app {
    server app:8080;
}
DEFAULT
}

if [ ! -f "$UPSTREAM" ]; then
    write_default_upstream
elif grep -q 'server app-next:8080' "$UPSTREAM" 2>/dev/null && ! getent hosts app-next >/dev/null 2>&1; then
    # An update moved traffic to app-next and stopped before handing it back,
    # and then something restarted this container. nginx resolves upstream
    # names when it LOADS its config, not per request, so leaving this alone
    # would mean nginx refuses to start at all -- a stalled update turning into
    # a total outage days later, on a reboot nobody connected to it.
    #
    # `app` is the container that always exists and always carries a restart
    # policy, so it is the safe thing to come back to.
    echo "nginx: upstream names app-next, which does not resolve. Falling back to app."
    echo "nginx: an update was probably interrupted -- check the version now serving."
    write_default_upstream
fi

render || true
echo "nginx: TLS_MODE=${TLS_MODE}, serving ${APP_HOSTNAME}"

# Re-render periodically. This is what closes the loop in letsencrypt mode: the
# first certificate appears while nginx is already running on HTTP, and the next
# pass swaps in the TLS config without anybody having to remember to. It also
# picks up a renewed or replaced certificate in provided mode.
(
    while :; do
        sleep 12h
        if render; then
            echo "nginx: configuration changed, reloading"
        fi
        nginx -s reload
    done
) &

exec nginx -g 'daemon off;'
