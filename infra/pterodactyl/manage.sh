#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
environment_file="${PTERO_ENV_FILE:-${script_dir}/.env.local}"

if [[ ! -f "${environment_file}" ]]; then
    echo "Missing ${environment_file}; create it from .env.example first." >&2
    exit 1
fi

exec docker compose \
    --env-file "${environment_file}" \
    -f "${script_dir}/docker-compose.yml" \
    "$@"
