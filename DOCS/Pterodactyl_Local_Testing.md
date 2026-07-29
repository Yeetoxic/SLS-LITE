# Local Pterodactyl Test Environment

This environment runs the Pterodactyl control plane and game servers in Docker
Desktop. Persistent Panel, Wings, database, and server data remains in Ubuntu
WSL2.

## Access

- Panel: <http://localhost:8088>
- Velocity console: <http://localhost:8088/server/c165ae9c>
- Optional external lobby console: created by the lobby-mode helper when needed.
- Velocity address: `127.0.0.1:25565`

The Docker environment stores generated local credentials in the ignored
`infra/pterodactyl/.env.local` file. The legacy WSL installer requires
`PTERODACTYL_DB_PASSWORD` and `PTERODACTYL_ADMIN_PASSWORD` to be set explicitly;
it has no fallback passwords. Configure TLS and rotate all credentials before
exposing the Panel to a LAN or the internet.

## Installed Services

- Pterodactyl Panel 1.14.1
- Pterodactyl Wings 1.13.1
- MariaDB 11.4 and Redis 7
- One node named `Local Wings`
- One server named `SLS-LITE Velocity`
- An optional separately allocated Paper server named `SLS-LITE External Lobby`
- Eclipse Temurin Java 25 container images
- Velocity with the current SLS-LITE build

The browser-facing Wings API, signed file routes, and console WebSocket are
proxied through NGINX at `localhost:8088`. Wings listens internally on port
`8080`.

Docker Desktop must be running. Check the stack from PowerShell with:

```powershell
docker ps --filter "name=sls-ptero-"
```

## Lobby Modes

Switch to the separately hosted Paper lobby:

```powershell
.\scripts\set-pterodactyl-lobby-mode.ps1 -Mode external
```

This command creates or reuses the Paper allocation on port `25566`, registers
it as Velocity server `lobby`, changes SLS-LITE to `lobby.mode: external`, and
restarts the affected servers through Wings.

Restore the self-contained managed Paper lobby:

```powershell
.\scripts\set-pterodactyl-lobby-mode.ps1 -Mode managed
```

Managed mode stops the separate Paper allocation, removes the static lobby
registration, and restores SLS-LITE's managed `lobby/lobby` blueprint.

## Manual External-Lobby Test

Switch to external mode first. Connect a Minecraft client to
`127.0.0.1:25565` and verify:

1. The first connection lands on `SLS-LITE External Lobby`.
2. `/sls info` reports the lobby as external.
3. `/sls join minigame biome_run` starts and transfers you to a managed Paper
   server.
4. `/sls stop <instance-id>` returns you to the external lobby.
5. Stopping or kicking from a managed backend redirects you to the external
   lobby without a reconnect loop.
6. Stopping the external lobby redirects new arrivals to SLS-Limbo rather than
   selecting an arbitrary game backend.
7. `/sls info` and authorized proxy-level commands remain available there.
8. Restarting the external lobby automatically moves tracked SLS-Limbo players
   back after its health probe succeeds.
9. A normal `/sls join` queue from a healthy backend leaves the player on that
   backend until the requested destination is ready.

After the test, start the external lobby again from its Panel console before
continuing, or switch back to managed mode with the command above.

The fallback's architecture, configuration, limitations, and full manual test
are documented in [SLS_Limbo.md](SLS_Limbo.md).

## Manual Managed-Lobby Test

Switch to managed mode, connect to `127.0.0.1:25565`, and verify:

```text
/sls blueprints minigame
/sls start minigame biome_run
/sls list
/sls status <instance-id>
/sls stop <instance-id>
```

The historical SLS v2.1.2 network is the primary local fixture. Its lobby,
minigames, and adventure launch inside the same Pterodactyl allocation as
Velocity. The 6 GiB Pterodactyl memory limit applies to Velocity and all child
processes together. See [Velocity_Testing.md](Velocity_Testing.md) for the
version matrix and complete run.

If the Panel loads but its console reports a connection problem, hard-refresh
the page after confirming that `sls-ptero-panel` and `sls-ptero-wings` are
running.

## Reprovisioning

The Docker migration and management scripts are documented in
`infra/pterodactyl/README.md`. Recreate or repair the Velocity allocation with:

```powershell
docker cp scripts/create-pterodactyl-velocity-local.php sls-ptero-panel:/tmp/create-pterodactyl-velocity-local.php
docker exec -e PANEL_ROOT=/app sls-ptero-panel php /tmp/create-pterodactyl-velocity-local.php
```

Both server provisioning helpers are idempotent. They accept `--start`,
`--stop`, `--restart`, or `--kill` for infrastructure troubleshooting.
