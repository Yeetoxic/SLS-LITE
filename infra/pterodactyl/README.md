# Local Docker Pterodactyl

This stack runs the local SLS-LITE test Panel and Wings in Docker while retaining
the existing WSL database, node credentials, and game-server files.

## Services

- Panel v1.14.1: `http://localhost:8088`
- Wings v1.13.1: proxied through the Panel for browser traffic
- Wings SFTP: `localhost:2022`
- MariaDB 11.4 and Redis 7 on a private Docker network
- Existing game allocations: `/var/lib/pterodactyl/volumes`

Panel and Wings uploads are capped at 1 GiB. The Panel NGINX proxy streams
signed Wings uploads without request buffering. The migration was verified with
a 128 MiB signed upload through the same endpoint used by the browser.

Because this stack is only exposed on localhost, Panel reCAPTCHA and Wings'
per-server machine-ID injection are disabled. reCAPTCHA requires a real browser
domain, while machine-ID injection is incompatible with Docker Desktop's WSL
bind-path translation. Neither setting is required for SLS-LITE testing.
Wings keeps its internal Panel URL at `http://panel` and separately allows only
`http://localhost:8088` as the browser websocket origin.

## Commands

Run from Ubuntu WSL:

```bash
sudo /mnt/c/Users/Administrator/Documents/SLS-LITE/infra/pterodactyl/migrate-from-wsl.sh
```

Manage the stack afterward:

```bash
sudo ./manage.sh ps
sudo ./manage.sh logs -f panel wings
sudo ./manage.sh restart panel wings
sudo ./manage.sh down
sudo ./manage.sh up -d
```

Persistent control-plane state lives in the gitignored `state/` directory next
to the Compose file. Local secrets live in the gitignored `.env.local` file.
Keeping this state on the Windows-backed project filesystem prevents Docker
Desktop from resolving `/srv` inside a different WSL or Docker VM after a
restart. The original Ubuntu WSL files remain available under
`/srv/pterodactyl-docker` for rollback.

`PTERO_DAEMON_DATA_DIR` must be Docker Desktop's Linux VM path for the same
Windows `state/game-data` directory. Wings uses this identical absolute path
when it asks the Docker daemon to bind a game server's files into
`/home/container`.
