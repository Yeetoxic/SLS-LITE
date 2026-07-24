# Local Pterodactyl Test Environment

This environment runs a real Pterodactyl Panel and Wings node in Ubuntu under
WSL2. Wings uses Docker Desktop's Linux Docker engine.

## Access

- Panel: <http://localhost:8088>
- Server console: <http://localhost:8088/server/c165ae9c>
- Username: `admin`
- Email: `admin@local.test`
- Password: `SlsLiteLocal2026!`
- Velocity address: `127.0.0.1:25565`

These credentials are only suitable for a local, loopback-only test panel.
Change the password and configure TLS before exposing the Panel to a LAN or the
internet.

## Installed Services

- Pterodactyl Panel 1.12
- Pterodactyl Wings 1.13.1
- MariaDB, Redis, NGINX, PHP-FPM, cron, and the `pteroq` queue worker
- One node named `Local Wings`
- One server named `SLS-LITE Velocity`
- Eclipse Temurin Java 25 container image
- Velocity 4.0.0 with the current SLS-LITE build

The browser-facing Wings API and console WebSocket are proxied through NGINX at
`localhost:8088`. Wings continues listening internally on port `8080`. This
avoids relying on a Windows port proxy tied to WSL's changeable private IP.

Docker Desktop must be running. Opening the Ubuntu WSL distribution starts its
systemd services. Check them from PowerShell with:

```powershell
wsl -d Ubuntu -u root -- systemctl status nginx mariadb redis-server pteroq wings
```

## Manual Console Test

Open the managed server in the Panel and use its Console tab. The server can be
started and stopped with the Panel power controls.

Run the current lifecycle smoke test yourself:

```text
sls start smoke
sls list
sls status <instance-id>
sls stop <instance-id>
```

The `smoke` blueprint launches Paper inside the same Pterodactyl allocation as
Velocity. The 6 GiB Pterodactyl memory limit applies to Velocity and all child
processes together. SLS-LITE currently reserves up to 1 GiB for managed child
servers in this fixture.

If the Panel loads but the console reports a connection problem, hard-refresh
the page after confirming that both `nginx` and `wings` are active.

The proxy currently has no static fallback server, and player queue/join routing
is not implemented yet. A Minecraft client can ping the proxy, but a complete
join-to-managed-server test should wait until that routing work is complete.

## Reprovisioning

The install scripts are intended for the existing Ubuntu WSL environment:

```powershell
wsl -d Ubuntu -u root -- bash /mnt/c/Users/Administrator/Documents/SLS-LITE/scripts/install-pterodactyl-panel-local.sh
wsl -d Ubuntu -u root -- bash /mnt/c/Users/Administrator/Documents/SLS-LITE/scripts/install-pterodactyl-wings-local.sh
wsl -d Ubuntu -u root -- php /mnt/c/Users/Administrator/Documents/SLS-LITE/scripts/create-pterodactyl-velocity-local.php
```

The server provisioning helper is idempotent. It also accepts `--start`,
`--stop`, `--restart`, or `--kill` for infrastructure troubleshooting.
