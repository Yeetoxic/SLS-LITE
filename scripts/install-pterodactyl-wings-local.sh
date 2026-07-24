#!/usr/bin/env bash
set -euo pipefail

panel_root="/var/www/pterodactyl"
config_root="/etc/pterodactyl"

cd "${panel_root}"

location_id="$(
    mariadb panel -N -e "SELECT id FROM locations WHERE short = 'local' LIMIT 1"
)"
if [[ -z "${location_id}" ]]; then
    php artisan p:location:make \
        --short=local \
        --long='Local SLS-LITE Testing' \
        --no-interaction
    location_id="$(
        mariadb panel -N -e "SELECT id FROM locations WHERE short = 'local' LIMIT 1"
    )"
fi

node_id="$(
    mariadb panel -N -e "SELECT id FROM nodes WHERE name = 'Local Wings' LIMIT 1"
)"
if [[ -z "${node_id}" ]]; then
    php artisan p:node:make \
        --name='Local Wings' \
        --description='WSL2 and Docker Desktop test node for SLS-LITE' \
        --locationId="${location_id}" \
        --fqdn=127.0.0.1 \
        --public=1 \
        --scheme=http \
        --proxy=0 \
        --maintenance=0 \
        --maxMemory=12288 \
        --overallocateMemory=0 \
        --maxDisk=100000 \
        --overallocateDisk=0 \
        --uploadSize=100 \
        --daemonListeningPort=8080 \
        --daemonSFTPPort=2022 \
        --daemonBase=/var/lib/pterodactyl/volumes \
        --no-interaction
    node_id="$(
        mariadb panel -N -e "SELECT id FROM nodes WHERE name = 'Local Wings' LIMIT 1"
    )"
fi

mkdir -p "${config_root}" /var/lib/pterodactyl/volumes

# Generate Wings' internal listener configuration first. The node is advertised
# through NGINX afterward because Windows is not reliably forwarding WSL port
# 8080 directly to the browser.
mariadb panel -e "
    UPDATE nodes
    SET fqdn = '127.0.0.1',
        scheme = 'http',
        behind_proxy = 0,
        daemonListen = 8080
    WHERE id = ${node_id};
"
php artisan p:node:configuration "${node_id}" >"${config_root}/config.yml"
mariadb panel -e "
    UPDATE nodes
    SET fqdn = 'localhost',
        scheme = 'http',
        behind_proxy = 1,
        daemonListen = 8088
    WHERE id = ${node_id};
"

curl -L -o /tmp/pterodactyl-wings \
    https://github.com/pterodactyl/wings/releases/latest/download/wings_linux_amd64
chmod 755 /tmp/pterodactyl-wings
mv -f /tmp/pterodactyl-wings /usr/local/bin/wings

cat >/etc/systemd/system/wings.service <<'SERVICE'
[Unit]
Description=Pterodactyl Wings Daemon
After=network-online.target

[Service]
User=root
WorkingDirectory=/etc/pterodactyl
LimitNOFILE=4096
ExecStart=/usr/local/bin/wings
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
SERVICE

systemctl daemon-reload
systemctl enable wings
systemctl restart wings

echo "node_id=${node_id}"
