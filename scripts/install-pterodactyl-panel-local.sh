#!/usr/bin/env bash
set -euo pipefail

panel_root="/var/www/pterodactyl"
database_password="${PTERODACTYL_DB_PASSWORD:-PteroDbLocal2026!}"

systemctl enable --now mariadb redis-server php8.3-fpm nginx cron

mariadb <<SQL
CREATE DATABASE IF NOT EXISTS panel;
CREATE USER IF NOT EXISTS 'pterodactyl'@'127.0.0.1' IDENTIFIED BY '${database_password}';
ALTER USER 'pterodactyl'@'127.0.0.1' IDENTIFIED BY '${database_password}';
GRANT ALL PRIVILEGES ON panel.* TO 'pterodactyl'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

mkdir -p "${panel_root}"
cd "${panel_root}"
if [[ ! -f artisan ]]; then
    curl -Lo /tmp/pterodactyl-panel.tar.gz \
        https://github.com/pterodactyl/panel/releases/latest/download/panel.tar.gz
    tar -xzf /tmp/pterodactyl-panel.tar.gz
fi

chmod -R 755 storage bootstrap/cache
[[ -f .env ]] || cp .env.example .env
COMPOSER_ALLOW_SUPERUSER=1 composer install \
    --no-dev \
    --optimize-autoloader \
    --no-interaction

sed -i -E \
    -e 's|^APP_ENV=.*|APP_ENV=production|' \
    -e 's|^APP_DEBUG=.*|APP_DEBUG=false|' \
    -e 's|^APP_URL=.*|APP_URL=http://localhost:8088|' \
    -e 's|^APP_TIMEZONE=.*|APP_TIMEZONE=America/Los_Angeles|' \
    -e 's|^APP_SERVICE_AUTHOR=.*|APP_SERVICE_AUTHOR=admin@local.test|' \
    -e 's|^DB_HOST=.*|DB_HOST=127.0.0.1|' \
    -e 's|^DB_PORT=.*|DB_PORT=3306|' \
    -e 's|^DB_DATABASE=.*|DB_DATABASE=panel|' \
    -e 's|^DB_USERNAME=.*|DB_USERNAME=pterodactyl|' \
    -e "s|^DB_PASSWORD=.*|DB_PASSWORD=${database_password}|" \
    -e 's|^CACHE_DRIVER=.*|CACHE_DRIVER=redis|' \
    -e 's|^SESSION_DRIVER=.*|SESSION_DRIVER=redis|' \
    -e 's|^QUEUE_CONNECTION=.*|QUEUE_CONNECTION=redis|' \
    -e 's|^REDIS_HOST=.*|REDIS_HOST=127.0.0.1|' \
    .env

if grep -q '^APP_KEY=$' .env; then
    php artisan key:generate --force
fi

php artisan migrate --seed --force

if ! mariadb panel -N -e \
    "SELECT email FROM users WHERE email = 'admin@local.test'" \
    | grep -qx 'admin@local.test'; then
    php artisan p:user:make \
        --email=admin@local.test \
        --username=admin \
        --name-first=SLS \
        --name-last=Admin \
        --password='SlsLiteLocal2026!' \
        --admin=1 \
        --no-interaction
fi

cat >/etc/nginx/sites-available/pterodactyl.conf <<'NGINX'
server {
    listen 8088;
    server_name localhost;
    root /var/www/pterodactyl/public;
    index index.php;
    charset utf-8;

    location / {
        try_files $uri $uri/ /index.php?$query_string;
    }

    location = /favicon.ico { access_log off; log_not_found off; }
    location = /robots.txt  { access_log off; log_not_found off; }

    access_log off;
    error_log /var/log/nginx/pterodactyl.app-error.log error;
    client_max_body_size 100m;
    client_body_timeout 120s;
    sendfile off;

    # The browser must connect directly to Wings for console WebSockets and
    # signed file transfers. Proxy those routes through WSL's working listener.
    location ^~ /api/servers/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }

    location ^~ /api/system {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location ~ \.php$ {
        fastcgi_split_path_info ^(.+\.php)(/.+)$;
        fastcgi_pass unix:/run/php/php8.3-fpm.sock;
        fastcgi_index index.php;
        include fastcgi_params;
        fastcgi_param PHP_VALUE "upload_max_filesize = 100M \n post_max_size=100M";
        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;
        fastcgi_param HTTP_PROXY "";
        fastcgi_intercept_errors off;
        fastcgi_buffer_size 16k;
        fastcgi_buffers 4 16k;
        fastcgi_connect_timeout 300;
        fastcgi_send_timeout 300;
        fastcgi_read_timeout 300;
    }

    location ~ /\.ht {
        deny all;
    }
}
NGINX

rm -f /etc/nginx/sites-enabled/default
ln -sfn /etc/nginx/sites-available/pterodactyl.conf \
    /etc/nginx/sites-enabled/pterodactyl.conf

cat >/etc/systemd/system/pteroq.service <<'SERVICE'
[Unit]
Description=Pterodactyl Queue Worker
After=redis-server.service

[Service]
User=www-data
Group=www-data
Restart=always
ExecStart=/usr/bin/php /var/www/pterodactyl/artisan queue:work --queue=high,standard,low --sleep=3 --tries=3
StartLimitInterval=180
StartLimitBurst=30
RestartSec=5s

[Install]
WantedBy=multi-user.target
SERVICE

cat >/etc/cron.d/pterodactyl <<'CRON'
* * * * * root php /var/www/pterodactyl/artisan schedule:run >> /dev/null 2>&1
CRON
chmod 644 /etc/cron.d/pterodactyl

chown -R www-data:www-data "${panel_root}"
nginx -t
systemctl daemon-reload
systemctl enable --now pteroq
systemctl restart php8.3-fpm nginx cron
