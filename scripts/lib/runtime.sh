#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- where the database and the app actually are
# =====================================================================
# backup.sh and restore.sh need to reach two things: a PostgreSQL database and
# the directory holding attachment bytes. Both scripts used to reach them one
# way only -- `docker compose exec postgres` and `docker compose exec app` --
# which quietly made them false in a deployment the runbook itself describes.
# RUNBOOK §6 says moving Postgres out of the stack is "a configuration change,
# not a port": change DB_HOST and stop starting the local service. Do that, and
# `docker compose exec postgres` has nothing to exec into. Backups stop, and
# they stop in the way that is only discovered during a restore.
#
# So the two ways of reaching a database are named, and every script goes
# through the same small set of verbs.
#
#   DEPLOY_MODE=compose   (default) Postgres and the app are services in
#                         deploy/docker-compose.yml. Client tools do not need
#                         to exist on the host.
#   DEPLOY_MODE=direct    Postgres is reachable at DB_HOST:DB_PORT and the
#                         PostgreSQL client tools are on PATH. The attachment
#                         directory is a path on this machine. This covers an
#                         externalized database, a managed instance, and a
#                         restore rehearsal on a machine with no Docker.
#
# The verbs below are the entire surface. Anything that needs the database or
# the attachment directory calls one of them rather than assuming a topology.
# =====================================================================

# ---- Loading the environment ----------------------------------------
# IM_ENV_FILE exists so a rehearsal can point at a throwaway environment
# without editing the real one. Unset, it means the deployment's own .env.
runtime_load_env() {
  local root="$1"
  local env_file="${IM_ENV_FILE:-$root/.env}"

  if [[ ! -f "$env_file" ]]; then
    echo "No environment file at ${env_file}." >&2
    echo "Copy .env.example to .env and fill it in first." >&2
    exit 1
  fi

  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a

  DEPLOY_MODE="${DEPLOY_MODE:-compose}"
  DB_PORT="${DB_PORT:-5432}"
  APP_ATTACHMENTS_DIRECTORY="${APP_ATTACHMENTS_DIRECTORY:-/var/lib/inventory-manager/attachments}"

  case "$DEPLOY_MODE" in
    compose)
      # Every compose verb runs from the directory holding the compose file.
      cd "$root/deploy"
      ;;
    direct)
      # pg_dump and pg_restore must match the server's major version or the
      # dump is unrestorable in ways that surface at the worst moment. Check
      # now, in the open, rather than letting a version error appear halfway
      # through a restore.
      for tool in psql pg_dump pg_restore; do
        command -v "$tool" > /dev/null 2>&1 || {
          echo "DEPLOY_MODE=direct needs ${tool} on PATH." >&2
          exit 1
        }
      done
      export PGPASSWORD="${DB_PASSWORD:-}"
      ;;
    *)
      echo "Unknown DEPLOY_MODE: ${DEPLOY_MODE}. Use 'compose' or 'direct'." >&2
      exit 1
      ;;
  esac
}

# ---- Database -------------------------------------------------------

# db_dump <output-file>
db_dump() {
  local out="$1"
  case "$DEPLOY_MODE" in
    compose) docker compose exec -T postgres pg_dump -Fc -U "$DB_USER" "$DB_NAME" > "$out" ;;
    direct)  pg_dump -Fc -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" "$DB_NAME" > "$out" ;;
  esac
}

# db_dump_plain -- plain SQL to stdout, for a collector that stores text.
# Not a replacement for db_dump: this is the human-readable, diffable form,
# and it carries no attachments.
db_dump_plain() {
  case "$DEPLOY_MODE" in
    compose) docker compose exec -T postgres pg_dump --format=plain --no-owner --no-acl -U "$DB_USER" "$DB_NAME" ;;
    direct)  pg_dump --format=plain --no-owner --no-acl -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" "$DB_NAME" ;;
  esac
}

# db_restore <input-file>
db_restore() {
  local in="$1"
  case "$DEPLOY_MODE" in
    compose) docker compose exec -T postgres pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner < "$in" ;;
    direct)  pg_restore -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" --no-owner < "$in" ;;
  esac
}

# db_psql <psql-args...> -- against the application database.
db_psql() {
  case "$DEPLOY_MODE" in
    compose) docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" "$@" ;;
    direct)  psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" "$@" ;;
  esac
}

# db_psql_maintenance <psql-args...> -- against `postgres`, so the application
# database can be dropped without the connection being inside it.
db_psql_maintenance() {
  case "$DEPLOY_MODE" in
    compose) docker compose exec -T postgres psql -U "$DB_USER" -d postgres "$@" ;;
    direct)  psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres "$@" ;;
  esac
}

# ---- Attachments ----------------------------------------------------
# Read and written through the app container in compose mode so this works the
# same whether the volume is a named volume or a bind mount -- guessing where
# Docker put a named volume on the host is exactly the sort of thing that is
# right until it is not.

# attachments_tar_to <output-file>
attachments_tar_to() {
  local out="$1"
  case "$DEPLOY_MODE" in
    compose)
      docker compose exec -T app tar -czf - -C "$APP_ATTACHMENTS_DIRECTORY" . > "$out"
      ;;
    direct)
      # A deployment that has never had an upload has no directory yet. That is
      # a legitimate empty backup, not a failure.
      mkdir -p "$APP_ATTACHMENTS_DIRECTORY"
      tar -czf - -C "$APP_ATTACHMENTS_DIRECTORY" . > "$out"
      ;;
  esac
}

# attachments_untar_from <input-file>
attachments_untar_from() {
  local in="$1"
  case "$DEPLOY_MODE" in
    compose)
      docker compose run --rm -T --entrypoint sh app -c \
        "mkdir -p '$APP_ATTACHMENTS_DIRECTORY' && tar -xzf - -C '$APP_ATTACHMENTS_DIRECTORY'" < "$in"
      ;;
    direct)
      mkdir -p "$APP_ATTACHMENTS_DIRECTORY"
      tar -xzf - -C "$APP_ATTACHMENTS_DIRECTORY" < "$in"
      ;;
  esac
}

# ---- The application ------------------------------------------------
# In direct mode there is no single right answer for how the app is run --
# systemd, a supervisor, another compose file elsewhere -- so it is configured
# rather than guessed. Unset, the scripts say what they need and stop, which is
# the honest outcome: a restore that ran while the app was writing is worse
# than one that refused to start.

app_stop() {
  case "$DEPLOY_MODE" in
    compose) docker compose stop app ;;
    direct)
      if [[ -n "${APP_STOP_COMMAND:-}" ]]; then
        eval "$APP_STOP_COMMAND"
      else
        echo "      DEPLOY_MODE=direct with no APP_STOP_COMMAND set."
        echo "      Stop the application yourself before continuing -- a restore"
        echo "      that runs while the app is writing corrupts what it restores."
        runtime_confirm "Is the application stopped? [y/N] "
      fi
      ;;
  esac
}

app_start() {
  case "$DEPLOY_MODE" in
    compose) docker compose start app ;;
    direct)
      if [[ -n "${APP_START_COMMAND:-}" ]]; then
        eval "$APP_START_COMMAND"
      else
        echo "      DEPLOY_MODE=direct with no APP_START_COMMAND set."
        echo "      Start the application yourself, then smoke-test it."
      fi
      ;;
  esac
}

# Prints nothing, returns 0 when the application reports UP.
app_healthy() {
  case "$DEPLOY_MODE" in
    compose)
      docker compose exec -T app sh -c \
        "wget -qO- http://localhost:8080/actuator/health | grep -q '\"status\":\"UP\"'" 2>/dev/null
      ;;
    direct)
      local url="${APP_HEALTH_URL:-http://localhost:8080/actuator/health}"
      curl -fsS --max-time 5 "$url" 2>/dev/null | grep -q '"status":"UP"'
      ;;
  esac
}

# True when there is an application to wait on at all. In direct mode with
# nothing configured to start, waiting for health would hang forever on
# something this script was never told how to run.
app_is_managed() {
  [[ "$DEPLOY_MODE" == "compose" || -n "${APP_START_COMMAND:-}" ]]
}

# ---- Confirmations --------------------------------------------------
# Every destructive prompt goes through here so there is exactly one place that
# knows how to skip them. RESTORE_NONINTERACTIVE is for rehearsal drills and
# automated tests; it is deliberately not a command-line flag, because a flag
# is something a person types under pressure on a real database.

runtime_confirm() {
  local prompt="$1"
  if [[ "${RESTORE_NONINTERACTIVE:-0}" == "1" ]]; then
    echo "${prompt}y   [RESTORE_NONINTERACTIVE]"
    return 0
  fi
  local answer
  read -r -p "$prompt" answer
  [[ "$answer" == "y" || "$answer" == "Y" ]] || { echo "Aborted."; exit 1; }
}

# The one prompt that is not y/N: typing the database name, so that confirming
# a restore requires knowing which database is about to be replaced.
runtime_confirm_database() {
  if [[ "${RESTORE_NONINTERACTIVE:-0}" == "1" ]]; then
    echo "Type the database name to confirm: ${DB_NAME}   [RESTORE_NONINTERACTIVE]"
    return 0
  fi
  local answer
  read -r -p "Type the database name to confirm: " answer
  [[ "$answer" == "$DB_NAME" ]] || { echo "Aborted."; exit 1; }
}
