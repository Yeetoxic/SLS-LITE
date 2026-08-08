# Getting Started

[Documentation home](README.md)

SLS-LITE is designed for a Velocity allocation whose host permits additional
local Java processes. Test host capabilities before importing a real network.

## Host Checklist

- The pinned Velocity generation used by this release runs on Java 25.
  SLS-LITE's own classes target Java 21 bytecode, but installing an older JDK
  does not make this Velocity build runnable.
- Source builds require Maven 3.9.6 or newer and JDK 25; the release build
  rejects a different Java feature generation.
- Every Minecraft version has a compatible Java executable.
- Velocity may create child processes and writable directories.
- Managed servers may bind unused ports on `127.0.0.1`.
- The allocation has enough memory and disk for all simultaneous instances.
- Provider-backed installation has outbound HTTPS access.
- The hosting provider permits this use of the allocation.

SLS-LITE cannot override panel process, memory, port, filesystem, or network
restrictions. `resources.total_memory_mib` is only SLS-LITE admission
accounting.

## Install

1. Stop Velocity.
2. Remove or replace Velocity's unreachable example servers. With the default
   `lobby.mode: velocity`, keep the real `[servers]`, `try`, and `[forced-hosts]`
   routes you want Velocity to own. SLS-LITE registers its managed instances
   itself and never rewrites `velocity.toml`.
3. Place the shaded SLS-LITE JAR in Velocity's `plugins/` directory.
4. Start Velocity and wait for `SLS-LITE initialized`.
5. Stop Velocity before making the first host-wide configuration changes.
6. Review `plugins/sls-lite/config.yml`. If a complete replacement is easier,
   use the [copyable canonical configuration](Copyable_Config.md) and then adapt
   its host-specific values before restarting Velocity.
7. Configure player forwarding for the test or production environment.
8. Review `software-profiles/paper.yml` and `vanilla.yml`.
9. After accepting the Minecraft EULA, set `accept_eula: true` in each automatic
   software profile you approve, or set host-wide
   `software.auto_accept_eula: true` in `config.yml`. Both remain false by
   default.
10. Copy `blueprints/template.yml.example` to a `.yml` file, then customize it
    and add any required source worlds.
11. Start Velocity and inspect `/sls system`.

The generated defaults are intentionally conservative: forwarding is disabled,
provider downloads require an explicit EULA choice, child output is not
mirrored into the proxy console, normal rather than test-level detailed
diagnostics are retained, and SLS-Limbo is enabled as a fallback. The 2048 MiB
managed budget and 20-port range are portable starting points, not detected
host capacity; resize both for the real allocation before importing a network.
The generated blueprint template has an `.example` suffix and is not loaded
until it is copied or renamed to `.yml`.

## First Administrator

The Velocity console is always an SLS-LITE administrator.

1. Run `/sls admin code` in the Velocity console.
2. Join the proxy.
3. Run `/sls admin claim <code>` before the code expires.
4. Confirm access with `/sls admin list` and `/sls system`.

Administrators are stored by Velocity-provided UUID in
`administrators.properties`. Claims are blocked in offline mode unless
`security.allow_insecure_offline_administrators: true` is explicitly enabled.
Do not enable that exception on a public network.

Permission plugins remain supported. Grant `sls.command.admin` for full access
or the granular nodes in [Commands](Commands.md).

## Add A World

Place source content below the SLS-LITE data directory:

```text
plugins/sls-lite/volumes/worlds/minigames/example/
```

Then create `blueprints/minigames/example.yml`:

```yaml
blueprint:
  id: example
  name: Example Game
  type: minigame

server:
  software: paper
  version: "1.20.4"
  limits:
    max_players: 12
    max_instances: 1
    memory_limit: 1536

save: false

state:
  volumes:
    - name: world
      source: volumes/worlds/minigames/example
      target: /world
      mode: cow
```

Reload and test:

```text
/sls reload blueprints
/sls blueprints minigame
/sls join minigame example
```

The source world is never used as the live world. SLS-LITE prepares a private
writable instance view using the selected COW strategy or portable-copy
fallback. Keep an independent backup anyway.

## Forwarding

For an isolated offline smoke test, `forwarding.mode: none` is acceptable. For
a real Paper network:

1. Configure Velocity `player-info-forwarding-mode = "modern"`.
2. Replace Velocity's placeholder secret with a cryptographically random value
   of at least 32 characters and restrict the secret file to the account that
   runs Velocity. Do not reuse, publish, or commit this value.
3. Set `forwarding.mode: modern`.
4. Set `forwarding.online_mode` to Velocity's `online-mode`.
5. Point `forwarding.secret_file` to the same secret file.

SLS-LITE patches managed Paper instances. Vanilla servers do not support modern
Velocity forwarding.

## Updating

There is no stable public upgrade contract before the first release.

1. Stop Velocity and all managed children through normal proxy shutdown.
2. Back up the SLS-LITE data directory.
3. Read the release or migration notes.
4. Replace only the plugin JAR.
5. Start Velocity and review reconciliation, migration, and capability logs.
6. Run `/sls system`, `/sls info`, and a representative start/join/stop test.

Never delete persistent instance metadata to silence a migration error. Use the
documented restart or reset path after reviewing the reported definition drift.

## Backups

Back up:

- `config.yml`
- `administrators.properties`
- `blueprints/`
- `volumes/` (including source worlds and per-instance plugin assets)
- `software-profiles/`
- any persistent `instances/`
- manually supplied `software/` and `runtimes/` that cannot be reproduced

Provider-downloaded software caches and extracted SLS-Limbo files are
reproducible, but retaining them avoids downloads and first-start delays.

## Removal

1. Stop Velocity normally.
2. Confirm no SLS-LITE child Java processes remain.
3. Remove the plugin JAR.
4. Keep or archive `plugins/sls-lite/` until persistent worlds are recovered.
5. Remove SLS-LITE's dynamic/static Velocity entries only if you added any
   manually.

Deleting the data directory permanently removes persistent managed instances,
source worlds stored there, blueprints, administrators, and caches.
