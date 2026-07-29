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

Historical command names such as `shutdown` and `config` are not the current
documented forms. Use modern `/sls stop`, `/sls reload`, and blueprint
inspection commands.

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
read-write storage, environment injection, non-properties config patches, and
remote services do not yet have a general local compatibility contract.

Unknown annotations are accepted, but only documented SLS-LITE lifecycle
annotations affect local behavior.

Stage 2 will test unmodified modern SLS fixtures and publish the authoritative
accepted/adapted/rejected matrix. Until then, migration is reviewed per
definition.

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

