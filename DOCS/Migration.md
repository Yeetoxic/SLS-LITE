# Migration

Back up the source installation before converting any network. SLS-LITE does
not modify another SLS installation in place and has no automatic migration
command yet.

## From Historical Velocity-Only SLS

The SLS v2.1.2 single-host implementation used registry-specific YAML such as
`minigames.yml`, `adventureMaps.yml`, and `archive.yml`, plus prepared server
folders. SLS-LITE does not load those registry files directly.

For each historical entry:

1. Copy the clean world/server content into an organized source directory below
   `plugins/sls-lite/worlds/`.
2. Identify the exact Minecraft version, Java requirement, memory, startup
   behavior, reset policy, command-block setting, plugins, and resource pack.
3. Select or create a software profile.
4. Create one modern-style blueprint with the old category represented by
   `blueprint.type`.
5. Map resettable worlds to `save: false`; map intentionally durable servers to
   `save: true`.
6. Map the clean world to a `state.volumes` `cow` entry.
7. Test install, start, join, logs, stop, source immutability, and cleanup.

Do not point a volume at an old live writable server directory. Preserve the
old installation as a read-only migration source until the new network passes.

Historical top-level command names `shutdown` and `config` are deliberately not
retained as aliases: `shutdown` is ambiguous with proxy shutdown and bypasses
the explicit target/evacuation language of `/sls stop`, while `config` suggests
a live host mutation that SLS-LITE does not perform. Use modern `/sls stop`,
`/sls reload`, and blueprint inspection commands. The only retained `config`
token is the explicit `/sls reload config` response explaining that host-wide
settings require a Velocity restart.

## From Modern SLS

SLS-LITE currently accepts the subset in [Blueprints](Blueprints.md) directly:

- blueprint ID, name, and type;
- software ID and exact version;
- memory, player, and instance limits;
- `server.properties` patches;
- persistence;
- `cow` state volumes;
- annotations.

Copy representative definitions into `blueprints/` and run:

```text
/sls reload blueprints
```

Unsupported structural fields are rejected with their YAML path. Do not delete
an unsupported field merely to make parsing succeed until its operational
intent is understood. Distributed node placement, container mounts, shared
read-write storage, and remote services remain outside local mode. Contained
`state.copy`, validated `state.env`, nested YAML patches, and line-prefix file
patches have documented local equivalents. `state.mounts` is rejected with a
contained `cow`/`ro` alternative.

Unknown annotation trees, including YAML null values, are preserved. Documented
SLS-LITE lifecycle annotations and vSLS lifecycle, capacity, `gameType`, and
bounded `on-join` annotations affect local behavior.

The compatibility suite uses an exact-ID corpus gate for unmodified modern SLS
fixtures. The authoritative accepted/adapted/rejected matrix remains the source
of truth for each definition.

## From An Earlier SLS-LITE Snapshot

1. Stop Velocity normally.
2. Back up the entire SLS-LITE data directory.
3. Keep `config.yml`, blueprints, worlds, profiles, administrators, software
   caches, runtimes, and persistent instances together.
4. Replace the plugin JAR.
5. Start Velocity and read configuration, reconciliation, metadata migration,
   and lobby recovery logs.

Persistent schema-1 and schema-2 directories are adopted non-destructively into
schema 3 on first restart when the current blueprint remains persistent. A
current `save: false` definition blocks that adoption to prevent accidental
deletion. Restore the intended persistence setting before proceeding.

Definition drift blocks ordinary restart. Review the reported blueprint/profile
change and use reset only when replacing the managed copy is intended.

Configurations using deprecated `lobby.emergency` remain accepted, but should
be renamed to `lobby.limbo`. Do not define both keys.

Early development JARs included root-level `Data_Versions` and
`Protocol_Versions` text tables. They were never operator configuration or
runtime sidecars and are not migrated. Current protocol support comes from the
checksum-pinned SLS-Limbo runtime and the installed ViaVersion integration.

## Acceptance

A migration is complete only after:

- every definition reloads without ignored intent;
- exact software versions are cached or manually present;
- source worlds remain unchanged after instance activity;
- persistent data survives a proxy restart;
- ephemeral data cleans up;
- queues and capacity behave as configured;
- lobby failure routes through SLS-Limbo without a reconnect loop;
- commands and permissions match the operator's intended access model;
- resource packs and protocol translation are tested with real clients.
