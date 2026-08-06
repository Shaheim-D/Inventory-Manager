#!/usr/bin/env bash
# =====================================================================
# Inventory Manager -- the backup command for Unimus (or any SSH collector)
# =====================================================================
# Unimus backs things up by opening an SSH or Telnet session, running a
# command, and storing what comes back on stdout as that device's
# configuration. It then diffs each capture against the last one, which is
# what makes it useful: you see when something changed and what.
#
# So this emits the database as **plain SQL text on stdout** and nothing else.
# Point Unimus's backup command at it and the inventory database becomes just
# another device it tracks, with a readable history of every change.
#
#     /opt/inventory-manager/scripts/unimus-backup.sh
#
# ---------------------------------------------------------------------
# READ THIS BEFORE RELYING ON IT
#
# **This is not a complete backup, and cannot be.** A backup of this
# application is two things -- the database and the uploaded attachment files
# -- and a text stream can only carry the first. Attachment bytes live on disk
# with only their path in the database, so restoring from this alone brings
# back every attachment row pointing at a file that is not there.
#
# Use this for change visibility and as a second copy of the database in a
# system you already trust. Keep scripts/backup.sh as the recovery path: it is
# the one that captures both halves, and it is the one restore.sh reads.
#
# To make the gap visible rather than silent, a manifest of the attachment
# files is appended as SQL comments. It cannot restore them, but it tells you
# exactly which files a restore from this text is missing -- and it makes an
# attachment appearing or disappearing show up in Unimus's diff.
#
# **Whoever can read Unimus's backup store can read this entire database**,
# including password hashes and every cost field, regardless of what field
# visibility shows anyone in the application. That is the same property any
# database dump has; it is worth stating because this one is being handed to
# a second system with its own access rules.
# ---------------------------------------------------------------------
#
# The SSH account Unimus logs in as needs to be able to run this script, which
# means reaching the database -- the same access scripts/backup.sh needs.
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT/scripts/lib/runtime.sh"

# Diagnostics go to stderr, always. Anything on stdout is part of the captured
# configuration, and a stray progress line would show up as a change in the
# next diff.
runtime_load_env "$ROOT" >&2

emit_manifest() {
    local directory="$1"
    echo ""
    echo "-- ==================================================================="
    echo "-- Attachment manifest"
    echo "-- ==================================================================="
    echo "-- The bytes are NOT in this file. Attachments are files on disk with"
    echo "-- only their path in the database, and a text capture cannot carry"
    echo "-- them. This list is here so a restore from this file knows exactly"
    echo "-- what it is missing, and so an attachment appearing or disappearing"
    echo "-- shows up as a change rather than passing unnoticed."
    echo "--"
    echo "-- The complete backup, with these files in it, is scripts/backup.sh."
    echo "-- ==================================================================="

    if [[ ! -d "$directory" ]]; then
        echo "-- (no attachment directory yet)"
        return
    fi

    # Sorted, so the list is stable between runs and a diff shows only real
    # changes. Size rather than a checksum: this runs on every backup, and
    # hashing an attachment volume that has grown to gigabytes would turn a
    # quick capture into a long one. The per-file SHA-256 check belongs to
    # scripts/restore-drill.sh, where it runs once and matters.
    (
        cd "$directory" || return
        find . -type f -printf '%s\t%p\n' 2> /dev/null | sort -k2 \
            | while IFS=$'\t' read -r size path; do
                  echo "-- attachment: ${size} bytes  ${path}"
              done
    )
    echo "-- attachment count: $(find "$directory" -type f | wc -l)"
}

{
    echo "-- ==================================================================="
    echo "-- Inventory Manager -- database capture for configuration tracking"
    echo "-- Database: ${DB_NAME}"
    #
    # Deliberately no timestamp in here. The collector records when it took a
    # capture; putting the time in the payload would differ on every run and
    # report a change every time -- the same trap as the pg_dump nonce below,
    # and just as effective at making the diff useless.
    echo "--"
    echo "-- NOT a complete backup: attachments are listed at the end but their"
    echo "-- bytes are not here. See scripts/backup.sh and docs/RUNBOOK.md."
    echo "-- ==================================================================="
    echo ""

    # pg_dump 16.9 and later wrap the dump in \restrict / \unrestrict with a
    # random nonce, hardening a restore against crafted object names. The nonce
    # is different on every run, so two dumps of completely unchanged data
    # differ on those two lines -- which for a tool whose whole job is diffing
    # successive captures means every single backup reports a change, and the
    # diff stops meaning anything.
    #
    # They are stripped here because this file's purpose is the diff. The
    # binary artefact that scripts/restore.sh actually restores from is
    # untouched and keeps the protection.
    db_dump_plain | grep -v '^\\\(un\)\?restrict '

    emit_manifest "${APP_ATTACHMENTS_DIRECTORY}"
}
