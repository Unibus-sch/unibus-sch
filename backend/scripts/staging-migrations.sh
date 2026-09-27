#!/usr/bin/env bash

set -Eeuo pipefail

readonly UNIBUS_MODE="${1:---dry-run}"
if [[ "${UNIBUS_MODE}" != "--dry-run" && "${UNIBUS_MODE}" != "--apply" ]]; then
  printf 'Usage: %s [--dry-run|--apply]\n' "$0" >&2
  exit 2
fi

readonly UNIBUS_SCRIPT_DIR="$(
  cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd
)"
readonly UNIBUS_REPOSITORY_ROOT="$(
  cd -- "${UNIBUS_SCRIPT_DIR}/../.." && pwd
)"

# Always use the migrations from the worktree that owns this script, even when
# the script is launched from a different checkout.
cd -- "${UNIBUS_REPOSITORY_ROOT}"

read -r -s -p "Staging DB password: " unibus_staging_db_password
printf '\n'

# Supabase CLI requires credentials embedded in --db-url to be percent-encoded.
# Keep both the original and encoded values in memory only; neither is printed.
unibus_encoded_db_password="$(
  jq -rn --arg value "${unibus_staging_db_password}" '$value | @uri'
)"

cleanup() {
  unset unibus_staging_db_password
  unset unibus_encoded_db_password
}
trap cleanup EXIT

run_migrations() {
  local label="$1"
  local username="$2"
  local hostname="$3"
  local port="$4"
  local database_url="postgresql://${username}:${unibus_encoded_db_password}@${hostname}:${port}/postgres?sslmode=require"
  local -a push_options=()

  if [[ "${UNIBUS_MODE}" == "--dry-run" ]]; then
    push_options+=(--dry-run)
  else
    push_options+=(--yes)
  fi

  printf '\nTrying %s...\n' "${label}"
  supabase db push \
    "${push_options[@]}" \
    --db-url "${database_url}"
}

if [[ "${UNIBUS_MODE}" == "--apply" ]]; then
  printf 'APPLY: migrations will be pushed to the staging database.\n'
else
  printf 'DRY RUN: migrations will not be pushed to the database.\n'
fi

run_migrations \
  "direct connection" \
  "postgres" \
  "db.srxzkpdtxmqrtcxgpeyl.supabase.co" \
  "5432" \
  || run_migrations \
    "session pooler" \
    "postgres.srxzkpdtxmqrtcxgpeyl" \
    "aws-0-ap-northeast-2.pooler.supabase.com" \
    "5432" \
  || run_migrations \
    "transaction pooler" \
    "postgres.srxzkpdtxmqrtcxgpeyl" \
    "aws-0-ap-northeast-2.pooler.supabase.com" \
    "6543"
