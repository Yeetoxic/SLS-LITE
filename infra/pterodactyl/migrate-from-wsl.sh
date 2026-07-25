#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run this migration as root inside the Ubuntu WSL distribution." >&2
    exit 1
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose_file="${script_dir}/docker-compose.yml"
state_dir="${PTERO_STATE_DIR:-/srv/pterodactyl-docker}"
environment_file="${state_dir}/.env"
migration_dir="${state_dir}/migration"
database_dump="${migration_dir}/panel.sql"
wings_source="/etc/pterodactyl/config.yml"
panel_environment="/var/www/pterodactyl/.env"

if [[ ! -f "${panel_environment}" || ! -f "${wings_source}" ]]; then
    echo "The existing WSL Panel or Wings configuration was not found." >&2
    exit 1
fi

mkdir -p \
    "${migration_dir}" \
    "${state_dir}/database" \
    "${state_dir}/redis" \
    "${state_dir}/panel-var" \
    "${state_dir}/panel-logs" \
    "${state_dir}/panel-nginx" \
    "${state_dir}/wings"

app_key="$(grep -m1 '^APP_KEY=' "${panel_environment}" | cut -d= -f2-)"
if [[ -z "${app_key}" ]]; then
    echo "The existing Panel APP_KEY is missing." >&2
    exit 1
fi

if [[ ! -f "${environment_file}" ]]; then
    umask 077
    cat >"${environment_file}" <<EOF
PTERO_STATE_DIR=${state_dir}
PTERO_APP_KEY=${app_key}
PTERO_DB_PASSWORD=$(openssl rand -hex 32)
PTERO_DB_ROOT_PASSWORD=$(openssl rand -hex 32)
EOF
fi

set -a
source "${environment_file}"
set +a

echo "Stopping Pterodactyl game containers before control-plane migration..."
while IFS= read -r container_id; do
    [[ -z "${container_id}" ]] || docker stop --time 60 "${container_id}"
done < <(docker ps -q --filter "label=Service=Pterodactyl")

echo "Updating the local node for the same-origin Docker proxy..."
if [[ ! -f "${migration_dir}/panel-before-docker.sql" ]]; then
    mariadb-dump \
        --single-transaction \
        --quick \
        --routines \
        --triggers \
        panel >"${migration_dir}/panel-before-docker.sql"
fi
if [[ ! -f "${migration_dir}/wings-config-before-docker.yml" ]]; then
    cp -a "${wings_source}" "${migration_dir}/wings-config-before-docker.yml"
fi

mariadb panel <<'SQL'
UPDATE nodes
SET fqdn = 'localhost',
    scheme = 'http',
    behind_proxy = 1,
    daemonListen = 8088,
    upload_size = 1024;
SQL

echo "Exporting the existing Panel database..."
mariadb-dump \
    --single-transaction \
    --quick \
    --routines \
    --triggers \
    panel >"${database_dump}"

cp -a "${wings_source}" "${state_dir}/wings/config.yml"
cp -a "${panel_environment}" "${state_dir}/panel-var/.env"
cp -a "${script_dir}/panel-nginx.conf" "${state_dir}/panel-nginx/panel.conf"
sed -i -E \
    -e 's|^remote:.*|remote: http://panel|' \
    -e 's|^allowed_origins:.*|allowed_origins: ["http://localhost:8088"]|' \
    -e 's|^  upload_limit:.*|  upload_limit: 1024|' \
    "${state_dir}/wings/config.yml"
# Docker Desktop can turn Wings' per-server machine-id file into a directory
# when the daemon resolves the WSL bind mount. It is not needed for local tests.
sed -i -E \
    '/^  machine_id:/,/^  [a-z_]+:/ s/^    enabled: true/    enabled: false/' \
    "${state_dir}/wings/config.yml"
chmod 600 "${state_dir}/wings/config.yml" "${environment_file}"

if ! docker network inspect pterodactyl_nw >/dev/null 2>&1; then
    docker network create \
        --driver bridge \
        --subnet 172.18.0.0/16 \
        --gateway 172.18.0.1 \
        pterodactyl_nw
fi

echo "Stopping the old WSL services..."
systemctl disable --now \
    pteroq \
    wings \
    nginx \
    php8.3-fpm \
    mariadb \
    redis-server \
    cron

echo "Starting the Docker database and cache..."
docker compose \
    --env-file "${environment_file}" \
    -f "${compose_file}" \
    up -d database cache

for attempt in {1..60}; do
    if docker exec sls-ptero-database healthcheck.sh \
        --connect --innodb_initialized >/dev/null 2>&1; then
        break
    fi
    if [[ "${attempt}" -eq 60 ]]; then
        echo "The Docker database did not become healthy." >&2
        exit 1
    fi
    sleep 2
done

if [[ ! -f "${migration_dir}/database-imported" ]]; then
    echo "Importing the Panel database..."
    docker exec -i sls-ptero-database \
        mariadb -uroot "-p${PTERO_DB_ROOT_PASSWORD}" panel <"${database_dump}"
    touch "${migration_dir}/database-imported"
fi

echo "Starting the containerized Panel and Wings..."
docker compose \
    --env-file "${environment_file}" \
    -f "${compose_file}" \
    up -d

echo
echo "Containerized Pterodactyl is starting at http://localhost:8088"
echo "State: ${state_dir}"
echo "Environment: ${environment_file}"
