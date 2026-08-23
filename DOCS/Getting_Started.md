# Getting Started

[Documentation home](README.md)

SLS-LITE is designed for a Velocity allocation whose host permits additional
local Java processes. Test host capabilities before importing a real network.

## The Simple Mental Model

Think of SLS-LITE as an assembly line for servers. A blueprint is the build
sheet that tells it which clean server base to use, which world and plugins to
add, and whether to keep the finished instance after it stops. You prepare the
parts; SLS-LITE assembles and runs the result.

If this is your first install, use this path and ignore advanced storage
internals until the first server works:

1. Complete [Install](#install) and
   [Forwarding and First Connection](#forwarding-and-first-connection).
2. Build one server from a copyable [Blueprint Recipe](Blueprint_Recipes.md).
3. Start Velocity and read its single `Setup checklist` line. `READY` means the
   host checks passed; `ACTION NEEDED` names the first fixes and points back to
   this page. Exact probe details remain in `/sls system` and the detail log.
4. Run `/sls reload blueprints`, `/sls blueprints`, and then `/sls join`.
5. Only after that works, use the full [Blueprint Schema](Blueprints.md) and
   [advanced storage guide](Blueprint_Volumes.md).

The generated defaults favor safety and portability. `forwarding.mode: none`
is a deliberate development-only choice, while `storage.strategy: auto` may
select `portable-copy` on restricted hosting and still be fully functional.

`/sls blueprints` labels every loaded build sheet as `ready`, `action needed`,
or `temporarily unavailable`. Use `/sls blueprint <id>` for exact reasons. Fix
only the affected blueprint; valid siblings remain usable.

## Before Installing

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
2. Remove or replace Velocity's unreachable example servers. Keep only the
   static backends that actually exist; managed SLS-LITE instances are not
   static Velocity entries.
3. Place the shaded SLS-LITE JAR in Velocity's `plugins/` directory.
4. Start Velocity and wait for `SLS-LITE initialized`.
5. Stop Velocity before making the first host-wide configuration changes.
6. Review `plugins/sls-lite/config.yml`. If a complete replacement is easier,
   use the [copyable canonical configuration](Copyable_Config.md) and then adapt
   its host-specific values before restarting Velocity.
7. Follow [Forwarding and First Connection](#forwarding-and-first-connection)
   for either a real network or an isolated development proxy.
8. Review `software-profiles/paper.yml` and `vanilla.yml`.
9. After accepting the Minecraft EULA, set `accept_eula: true` in each automatic
   software profile you approve, or set host-wide
   `software.auto_accept_eula: true` in `config.yml`. Both remain false by
   default.
10. Copy `blueprints/template.yml.example` to a `.yml` file, then customize it
    and add any required source worlds. The
    [Blueprint Recipe Book](Blueprint_Recipes.md) provides complete copyable
    world, plugin, whitelist, configuration, and import examples.
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

This compact example is enough for an experienced operator. If `source`,
`target`, `cow`, or `save` is unfamiliar, start with the visual
[Blueprint Recipe Book](Blueprint_Recipes.md).

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
    max_instances: 1
    memory_limit: 1536

save: false

annotations:
  sls-lite:
    max-players: 12

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

## Forwarding and First Connection

SLS-LITE runs inside Velocity. It starts managed backend processes, registers
them with the running proxy when they are ready, and removes those dynamic
registrations when they stop. Do not add each managed instance to `[servers]`
in `velocity.toml`; only operator-owned static backends belong there.

SLS-LITE also writes the loopback address, allocated port, backend
`online-mode=false`, player capacity, and supported forwarding settings into
each managed Paper instance. Operators configure the proxy-facing half once.

### Choose The Lobby Route

Choose this before copying either forwarding example:

| `lobby.mode` | Primary destination | What remains in `velocity.toml` |
| --- | --- | --- |
| `velocity` | Velocity's normal initial, `try`, and forced-host selection | Keep real static `[servers]` entries. Set `try` to the order players should use. Keep `[forced-hosts]` only where wanted. Remove the generated unreachable example entry. |
| `external` | The exact static server named by `lobby.server` | Add that backend to `[servers]`. Native `try` and forced-host routes are optional and remain Velocity-owned. |
| `managed` | The blueprint selected by `lobby.registry` and `lobby.server` | Do not add the managed lobby or its instances to `[servers]`, `try`, or `[forced-hosts]`. SLS-LITE registers it dynamically. |

SLS-Limbo is a fallback, not a lobby mode. Keep it enabled unless another safe
fallback is already proven. In `velocity` mode, SLS-LITE preserves Velocity's
native routes; an unreachable example server can therefore prevent the intended
first connection and must be removed or corrected.

### Production Velocity And Paper

Use this path for a real network. It authenticates players at Velocity and uses
Velocity modern forwarding between the proxy and managed Paper backends.

1. Stop Velocity. In the Velocity working directory, edit `velocity.toml`:

   ```toml
   online-mode = true
   player-info-forwarding-mode = "modern"
   forwarding-secret-file = "forwarding.secret"
   ```

2. Create `forwarding.secret` beside `velocity.toml`, not below
   `plugins/sls-lite/`. It must contain one private, cryptographically random
   value of at least 32 characters. If a Linux shell and OpenSSL are available:

   ```sh
   umask 077
   openssl rand -hex 32 > forwarding.secret
   chmod 600 forwarding.secret
   ```

   The resulting path is `<Velocity working directory>/forwarding.secret` and
   only the account running Velocity should be able to read it. On a managed
   panel without a shell, create the same file through its private file manager
   and ensure other users or allocations cannot read it. Never paste the value
   into chat, logs, an issue, a blueprint, or source control.

3. In `plugins/sls-lite/config.yml`, use the matching settings:

   ```yaml
   forwarding:
     mode: modern
     online_mode: true
     secret_file: forwarding.secret
   ```

   `forwarding.online_mode` describes the proxy's authentication choice and
   must exactly match `online-mode` in `velocity.toml`. The relative secret path
   is resolved from the Velocity working directory, so both files above refer
   to the same secret.

4. Configure one lobby mode using the table above. For example, a static lobby
   owned by Velocity might use:

   ```toml
   [servers]
   lobby = "127.0.0.1:25566"
   try = ["lobby"]
   ```

   Do not copy this example unless a real lobby is listening at that address.
   `external` mode additionally needs `lobby.server: lobby`; `managed` mode
   needs a valid lobby blueprint instead and no static entry.

5. Fully restart Velocity. `/sls reload` does not reload host forwarding,
   lobby mode, or `velocity.toml`. SLS-LITE applies the Paper half when it
   prepares an instance: modern forwarding is enabled, the same secret is
   installed, BungeeCord forwarding is disabled, and backend online mode stays
   false because Velocity performs authentication.

   Paper 1.18.2 and older store these values under
   `settings.velocity-support` in `paper.yml`; newer Paper stores them under
   `proxies.velocity` in `config/paper-global.yml`. SLS-LITE selects the correct
   version-specific file and shape automatically. Do not copy a modern
   `paper-global.yml` into a legacy Paper server and assume it will be read.

This automatic patching applies only to software profiles whose `configurator`
is `paper`. Vanilla does not support Velocity modern forwarding, and SLS-LITE
rejects a vanilla blueprint while `forwarding.mode: modern` is active. A custom
Paper-compatible fork should use the `paper` configurator only when its config
contract is genuinely compatible. Fabric, Forge, and other software require
their own forwarding integration and are not configured automatically in this
release.

PaperMC's upstream references remain useful for the protocol itself:
[player-information forwarding](https://docs.papermc.io/velocity/player-information-forwarding/)
and [backend security](https://docs.papermc.io/velocity/security/). The
SLS-LITE steps above are the canonical instructions for managed instances.

### Isolated Development Only

This path is intentionally insecure. Use it only on a private loopback or
otherwise isolated test proxy that untrusted players cannot reach:

```toml
# velocity.toml
online-mode = false
player-info-forwarding-mode = "none"
```

```yaml
# plugins/sls-lite/config.yml
forwarding:
  mode: none
  online_mode: false
  secret_file: forwarding.secret

security:
  allow_insecure_offline_administrators: false
```

`secret_file` remains a required configuration field but is not read when the
mode is `none`. Offline players have spoofable names and UUIDs, lose secure
forwarded identity and address information, and must not be trusted as
administrators. Keep `allow_insecure_offline_administrators: false`; use the
Velocity console for administration. If a disposable test absolutely requires
an in-game claim, enabling that option accepts the impersonation risk. Fully
restart Velocity after changing either file.

### Verify Before Importing A Network

After the full restart:

1. Confirm the startup log reaches `SLS-LITE initialized` without a forwarding,
   lobby, or secret-file error.
2. Run `/sls system` from the Velocity console and resolve every forwarding or
   selected-lobby failure. A restricted but supported storage fallback is not a
   forwarding failure.
3. Run `/sls reload blueprints` and confirm the intended blueprint is loaded.
4. Join with a real Minecraft client through Velocity. Do not connect directly
   to a managed backend port.
5. Run `/sls join <registry> <blueprint>`, wait for readiness, and confirm the
   same client transfers successfully with the expected UUID, skin, and
   permissions. A status ping or protocol bot does not prove login or
   forwarding.

Common first-connection symptoms:

| Symptom | Check |
| --- | --- |
| Velocity reports that modern forwarding is not enabled, or Paper rejects the login | Both modes must be `modern`; fully restart Velocity and create a new instance after correcting them. |
| `Unable to read`, `empty`, or invalid forwarding secret | The regular, non-symbolic file must exist beside `velocity.toml`, be readable by Velocity, and match both relative paths. Do not reveal it while diagnosing permissions. |
| SLS-LITE reports mismatched online mode | Make `forwarding.online_mode` exactly equal Velocity's `online-mode`; use `true` for the production example. |
| Login tries an example server or immediately disconnects | Remove Velocity's generated unreachable `[servers]`/`try` entry or replace it with a real static backend. Managed instances must not be listed there. |
| External lobby is unavailable | Confirm `lobby.server` exactly names a reachable server in Velocity's `[servers]` table. |
| Managed lobby does not start | Confirm the registry/blueprint pair exists, its software can install, EULA acceptance is explicit, and the host has a process slot and enough managed memory. |
| Player remains in SLS-Limbo | Check the selected primary route and `/sls system`, then verify forwarding, backend readiness, compatible protocols, and ViaVersion mappings where required. A deliberate `/server sls-limbo` selection does not auto-return. |

For deeper diagnosis, use [Troubleshooting](Troubleshooting.md) without exposing
the forwarding secret.

## Updating

Candidate upgrades are supported only where the current
[Migration guide](Migration.md) explicitly defines a tested path. Read both
that guide and the current release notes before replacing an earlier candidate.

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
