#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
state_dir="${PTERO_STATE_DIR:-/srv/pterodactyl-docker}"
environment_file="${state_dir}/.env"

if [[ ! -f "${environment_file}" ]]; then
    echo "Missing ${environment_file}; run migrate-from-wsl.sh first." >&2
    exit 1
fi

exec docker compose \
    --env-file "${environment_file}" \
    -f "${script_dir}/docker-compose.yml" \
    "$@"
