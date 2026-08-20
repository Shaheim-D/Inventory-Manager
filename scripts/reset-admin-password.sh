#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- break-glass administrator password reset
# =====================================================================
# For the morning nobody can sign in: the bootstrap password was never written
# down, the person who set it has left, or the only administrator is locked out.
#
# ---------------------------------------------------------------------
# WHAT THIS DOES AND DOES NOT SECURE -- read this before hardening it further
# ---------------------------------------------------------------------
# This script grants NO privilege that its caller did not already have. To run
# it you need a shell on this machine and the ability to read .env, which holds
# DB_PASSWORD. Anyone holding those can already run:
#
#     psql -c "UPDATE app_user SET password_hash = '...' WHERE username = 'admin'"
#
# and own the application. So a password prompt on this script would protect
# nothing -- an attacker would simply not use the script. Believing otherwise is
# worse than knowing it, because it invites relaxing the controls that do work
# on the strength of one that does not.
#
# The boundary is therefore the database credential, and it is enforced in three
# places, none of them here:
#
#   * .env is chmod 600 and not committed. THIS is the control. The script
#     refuses to run when the file is group- or world-readable, because at that
#     point every account on the box is an administrator of this application.
#   * There is no web endpoint, no API route and no "forgot password" flow that
#     reaches this. Recovery deliberately requires host access, so a stolen
#     session or an application-layer bug can never lead here.
#   * Postgres is not published to the network in the shipped stack.
#
# What this script DOES add is that a reset cannot happen quietly. The reason
# is mandatory, the audit row is written in the same transaction as the change
# so the two cannot be separated, and the event goes to syslog as well -- which
# an attacker holding only the database credential cannot edit. Detection is the
# real control on an action whose prevention already lives elsewhere.
#
# ---------------------------------------------------------------------
#     ./reset-admin-password.sh --reason "why" [username] [--force]
#
# Defaults to the bootstrap username. The new password is RANDOM and printed
# once: this never sets a value from a file or an argument, so a password
# cannot arrive here from somewhere it was written down, and shell history
# never contains one. The account is flagged must-change-password, so the
# printed value is a one-time credential rather than the account's password.
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/lib/runtime.sh"

USERNAME=""
REASON=""
FORCE="no"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --reason) REASON="${2:-}"; shift 2 ;;
    --reason=*) REASON="${1#*=}"; shift ;;
    --force) FORCE="yes"; shift ;;
    -h|--help) sed -n '1,50p' "$0"; exit 0 ;;
    -*) echo "Unknown option: $1" >&2; exit 2 ;;
    *) USERNAME="$1"; shift ;;
  esac
done

# Mandatory, and mandatory BEFORE anything is touched. A reset with no stated
# reason is indistinguishable in the audit trail from an attacker's, and the
# whole value of this script over a raw UPDATE is that it cannot produce one.
if [[ -z "$REASON" ]]; then
  echo "Refusing to reset without --reason." >&2
  echo "It is recorded in Audit History and in syslog, and it is the only thing" >&2
  echo "that will later distinguish this from somebody else doing the same." >&2
  echo >&2
  echo "  $0 --reason \"bootstrap password never recorded, ticket 412\"" >&2
  exit 2
fi

# ---- The credential boundary ----------------------------------------
# Checked before the file is sourced, because sourcing it is the moment the
# credential is in play. A group- or world-readable .env means every local
# account already holds DB_PASSWORD, and this script would be the least of it.
ENV_FILE="${IM_ENV_FILE:-$ROOT/.env}"
if [[ -f "$ENV_FILE" ]]; then
  MODE="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || stat -f '%Lp' "$ENV_FILE" 2>/dev/null || echo '')"
  if [[ -n "$MODE" && "${MODE: -2}" != "00" ]]; then
    echo "Refusing to run: ${ENV_FILE} is mode ${MODE}." >&2
    echo >&2
    echo "It holds DB_PASSWORD. While it is readable beyond its owner, every local" >&2
    echo "account can reset this password directly in psql and resetting it here" >&2
    echo "achieves nothing. Fix the real problem first:" >&2
    echo >&2
    echo "  chmod 600 ${ENV_FILE}" >&2
    exit 1
  fi
fi

runtime_load_env "$ROOT"
USERNAME="${USERNAME:-${APP_ADMIN_USERNAME:-admin}}"

sql_literal() { printf "%s" "${1//\'/\'\'}"; }

# ---- Who is being reset, and is this even the right tool? ------------
ACCOUNT="$(db_psql -tAF'|' -c "
    SELECT u.id,
           u.is_active,
           u.auth_provider,
           coalesce(u.locked_until > now(), false),
           (SELECT count(*) FROM user_role ur
              JOIN role r ON r.id = ur.role_id
             WHERE ur.user_id = u.id AND r.name = 'Administrator')
      FROM app_user u
     WHERE lower(u.username) = lower('$(sql_literal "$USERNAME")')" | tr -d '\r')"

if [[ -z "$ACCOUNT" ]]; then
  echo "No account named '${USERNAME}'." >&2
  echo >&2
  echo "Accounts that hold Administrator:" >&2
  db_psql -tAc "SELECT '  ' || u.username FROM app_user u
                  JOIN user_role ur ON ur.user_id = u.id
                  JOIN role r ON r.id = ur.role_id
                 WHERE r.name = 'Administrator' ORDER BY u.username" >&2
  echo >&2
  echo "If app_user is empty instead, this is a fresh install: set" >&2
  echo "APP_ADMIN_INITIAL_PASSWORD and restart, and the first administrator is" >&2
  echo "created from it. That path only works on an empty table." >&2
  exit 1
fi

IFS='|' read -r USER_ID IS_ACTIVE AUTH_PROVIDER IS_LOCKED IS_ADMIN <<< "$ACCOUNT"

# ---- Prefer the boring path ------------------------------------------
# Break-glass should stay rare enough to be noticeable. If somebody else can
# still sign in and manage users, the Users screen is the right tool: it is
# permission-checked, attributable to a person, and audited as their action
# rather than as an unattributed host event.
OTHER_ADMINS="$(db_psql -tAc "
    SELECT count(*) FROM app_user u
      JOIN user_role ur ON ur.user_id = u.id
      JOIN role r ON r.id = ur.role_id
     WHERE r.name = 'Administrator'
       AND u.is_active = true
       AND coalesce(u.locked_until > now(), false) = false
       AND u.id <> ${USER_ID}" | tr -d '\r')"

if [[ "${OTHER_ADMINS:-0}" -gt 0 && "$FORCE" != "yes" ]]; then
  echo "Refusing: ${OTHER_ADMINS} other administrator account(s) can still sign in." >&2
  echo >&2
  echo "Reset '${USERNAME}' from Manage > Users instead. Doing it there records WHO" >&2
  echo "did it; doing it here records only that it happened on this host." >&2
  echo "If those accounts are also unusable, re-run with --force." >&2
  exit 1
fi

if [[ "$IS_LOCKED" == "t" ]]; then
  echo "Note: '${USERNAME}' is currently locked out. This clears the lockout too --"
  echo "      if a forgotten password is the whole problem, waiting out the lockout"
  echo "      and signing in normally leaves a cleaner record than a reset."
  echo
fi
if [[ "$AUTH_PROVIDER" != "LOCAL" ]]; then
  echo "Note: '${USERNAME}' signs in through ${AUTH_PROVIDER}. This sets a local"
  echo "      password and switches the account to LOCAL so it can be used now."
  echo
fi

# ---- Say what is about to happen, out loud ---------------------------
echo "==========================================================="
echo " Break-glass password reset"
echo "   account   : ${USERNAME} (id ${USER_ID})"
echo "   is admin  : $([[ "$IS_ADMIN" -gt 0 ]] && echo yes || echo no)"
echo "   active    : ${IS_ACTIVE}"
echo "   locked    : ${IS_LOCKED}"
echo "   reason    : ${REASON}"
echo "==========================================================="
echo

if [[ "$FORCE" != "yes" ]]; then
  # Typing the username rather than pressing y: this is the one action in the
  # repository that hands somebody the application, and a reflexive "y" is not
  # a decision.
  read -r -p "Type the username to confirm: " CONFIRM
  if [[ "$CONFIRM" != "$USERNAME" ]]; then
    echo "Did not match. Nothing was changed." >&2
    exit 1
  fi
fi

# ---- A password nobody chose ----------------------------------------
# Generated here and never accepted as input, so this script cannot be used to
# set a password somebody already knows, and none is ever in shell history or
# in a file. Printed once; the account must change it at sign-in.
if command -v openssl > /dev/null 2>&1; then
  NEW_PASSWORD="Im$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)1"
else
  NEW_PASSWORD="Im$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | cut -c1-20)1"
fi

# ---- The change and its record, inseparable --------------------------
# One statement, one transaction. The audit row cannot be omitted by someone
# using this script, because there is no path through it that writes the
# password without writing the row. (Someone with psql can of course write
# either alone -- see the header. This makes the honest path honest, and leaves
# the dishonest one looking different in the audit trail, which is the point.)
#
# The hash is computed by pgcrypto INSIDE the database: bcrypt at cost 12, the
# same $2a$ format the application's encoder produces and reads. That keeps the
# plaintext out of the process table and off the host, and means this needs no
# hashing tool installed anywhere.
if ! db_psql -qtAc "CREATE EXTENSION IF NOT EXISTS pgcrypto" > /dev/null 2>&1; then
  echo "Could not enable pgcrypto, which this needs to hash the password." >&2
  echo "DB_USER may lack rights to create an extension. Ask a superuser for:" >&2
  echo "  CREATE EXTENSION pgcrypto;" >&2
  exit 1
fi

db_psql -v ON_ERROR_STOP=1 -qtA <<SQL > /dev/null
BEGIN;

UPDATE app_user
   SET password_hash         = crypt('$(sql_literal "$NEW_PASSWORD")', gen_salt('bf', 12)),
       auth_provider         = 'LOCAL',
       is_active             = true,
       locked_until          = NULL,
       failed_login_attempts = 0,
       must_change_password  = true
 WHERE id = ${USER_ID};

INSERT INTO audit_event (entity_type, entity_id, user_id, action, field_name,
                         previous_value, new_value, reason)
VALUES ('APP_USER', ${USER_ID}, NULL, 'UPDATE', 'password_hash',
        NULL,
        'Break-glass reset via scripts/reset-admin-password.sh on $(hostname) by $(id -un)',
        '$(sql_literal "$REASON")');

COMMIT;
SQL

# Syslog as well as the audit table, deliberately. Somebody holding only the
# database credential can delete the audit row; the host's log pipeline is a
# different trust domain and usually ships elsewhere.
if command -v logger > /dev/null 2>&1; then
  logger -p auth.warning -t inventory-manager \
    "break-glass password reset for '${USERNAME}' by $(id -un): ${REASON}" || true
fi

echo
echo "==========================================================="
echo " Password reset for '${USERNAME}'."
echo
echo "   ${NEW_PASSWORD}"
echo
echo " Shown once. It is a one-time credential: the account must set a"
echo " real password at sign-in (8 characters minimum)."
echo
echo " Recorded in Audit History against ${USERNAME} and in syslog. If you"
echo " did not expect to be reading this, treat it as an incident: somebody"
echo " with a shell on this host and DB_PASSWORD just took the account."
echo "==========================================================="
