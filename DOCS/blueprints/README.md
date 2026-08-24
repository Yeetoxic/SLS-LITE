# Blueprint Recipe Book

[Documentation home](../README.md)

In this branch: [blueprint schema](Schema.md), [volumes](Volumes.md), and
[resource packs](Resource_Packs.md).

Think of SLS-LITE as an assembly line for Minecraft servers. A blueprint is the
build sheet: it selects a clean server base, lists the parts to add, and tells
SLS-LITE whether to keep or discard the finished instance.

## Start Here

For a first server, skip directly to one of these copyable recipes:

- [Disposable world](#1-disposable-world): starts clean each time.
- [Persistent SMP world](#2-persistent-smp-world): keeps one assembled server.

Replace the example version, IDs, and paths, then run:

```text
/sls reload blueprints
/sls blueprints
/sls join <type> <id>
```

If a blueprint is rejected, `/sls blueprint <id>` explains what to fix. Return
to the terminology below only when a recipe uses an unfamiliar field.

<details>
<summary>Blueprint terminology and choosing a storage tool</summary>

Every path in these recipes has one of two viewpoints:

- `source` is the supply location below `plugins/sls-lite/`;
- `target` is where that supply appears inside the assembled server.

For example, `source: volumes/worlds/spleef` reads from
`plugins/sls-lite/volumes/worlds/spleef`, while `target: /world` produces the
`world/` directory at the root of each prepared instance. SLS-LITE never runs a
managed server directly from a `cow`, `ro`, or `copy` source.

Six terms cover most beginner blueprints:

- `source`: the supply shelf under `plugins/sls-lite/`;
- `target`: where that part belongs in the finished server;
- `copy`: place a fresh file or directory into each new assembly;
- `persistent_files`: import a private file and safely return its changes to one
  canonical source below `volumes/` when the server stops;
- `cow`: give each instance a private writable directory while protecting the
  source (copy-on-write, with a portable full-copy fallback when needed);
- `save`: keep the assembled instance and its changes after it stops.

Use `cow` for a normal world and `copy` for plugin files. `rw` is advanced: it
connects every instance directly to the same live source directory, so one
server changes what the others see. Use it only when that sharing is deliberate
and normally limit the blueprint to one instance.

Replace every `REPLACE_WITH_EXACT_VERSION` value with an exact version supported
by the selected software profile. Change the example IDs and names before
loading more than one recipe.

## Pick The Right Assembly Tool

Start with the first two rows. The remaining choices solve specific advanced
cases and are not required for a first server.

| Goal | Use | Source kind | Persistence behavior |
| --- | --- | --- | --- |
| Give each instance a private writable directory such as a world | `state.volumes` with `mode: cow` | Directory only | Source stays unchanged; `save` decides whether the assembled instance survives. |
| Take a private source-protecting directory snapshot | `state.volumes` with `mode: ro` | Directory only | Instance may write its private result; source stays unchanged. |
| Deliberately share one live host directory | `state.volumes` with `mode: rw` | Directory only | Source itself changes and outlives restart, reset, and deletion. Use one instance. |
| Place or replace files, plugin JARs, or directory bundles | `state.copy` | File or directory | Fresh creation and reset read the source; restart reuses the existing persistent result. |
| Persist one Minecraft-managed file such as `whitelist.json` | `state.persistent_files` | Regular file only | Imports a private file and atomically writes it back after stop; one active writer per source. |
| Use a complete operator-prepared server as the software base | `server.path` | Complete directory below `software/` | SLS-LITE copies the base into the managed instance and then applies its owned settings. |

`save` and `rw` solve different problems. `save: true` keeps one assembled
instance, including its private `cow` changes. `rw` changes the shared source
itself, regardless of `save`. `save: false` discards the assembled instance
after it stops. A persistent restart reuses it; `/sls reset` rebuilds it from
the current software base, volumes, and copies.

## Persistent Whitelist File

Use a persistent file when Paper must be able to change a root file through its
normal commands without sharing the whole instance directory.

Source:

```text
plugins/sls-lite/
`-- volumes/whitelists/lobby/
    `-- whitelist.json
```

Initialize `whitelist.json` with a valid empty JSON list:

```json
[]
```

Minimal blueprint:

```yaml
blueprint:
  id: persistent_whitelist
  name: Persistent Whitelist
  type: lobby
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  limits:
    memory_limit: 1024
    max_instances: 1
annotations:
  sls-lite:
    max-players: 20
state:
  persistent_files:
    - name: whitelist
      source: volumes/whitelists/lobby/whitelist.json
      target: whitelist.json
save: true
```

Running instance:

```text
plugins/sls-lite/instances/<instance-id>/
`-- whitelist.json
```

Paper reads and replaces the ordinary instance file. After the process stops,
SLS-LITE publishes the complete file back to the source atomically. Commands
such as `/whitelist add <player>` therefore persist without making the entire
server an `rw` volume. Only one active instance may use that canonical source;
give independently running instances separate sources.

</details>

## 1. Disposable World

Use this for a minigame or test world that should start clean every time.

Source:

```text
plugins/sls-lite/
`-- volumes/worlds/spleef/
    |-- level.dat
    `-- region/
```

Blueprint:

```yaml
blueprint:
  id: disposable_world
  name: Disposable World
  type: minigame
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  limits:
    memory_limit: 1024
    max_instances: 2
annotations:
  sls-lite:
    max-players: 12
save: false
state:
  volumes:
    - name: world
      source: volumes/worlds/spleef
      target: /world
      mode: cow
```

Each result is private:

```text
instances/disposable_world.<suffix>/
|-- paper.jar
`-- world/
    |-- level.dat
    `-- region/
```

Player changes affect only that instance. Stopping and cleaning it removes the
changes; the source world remains unchanged.

## 2. Persistent SMP World

Use the same private `cow` mapping with `save: true` when one assembled server
must retain gameplay changes.

Source:

```text
plugins/sls-lite/
`-- volumes/worlds/smp-seed/
    |-- level.dat
    `-- region/
```

Blueprint:

```yaml
blueprint:
  id: persistent_smp
  name: Persistent SMP
  type: smp
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  limits:
    memory_limit: 2048
    max_instances: 1
annotations:
  sls-lite:
    max-players: 20
save: true
state:
  volumes:
    - name: world
      source: volumes/worlds/smp-seed
      target: /world
      mode: cow
```

Result after players build:

```text
instances/persistent_smp.<suffix>/
`-- world/                 <- retained live world
    |-- level.dat
    `-- region/

volumes/worlds/smp-seed/   <- unchanged seed
```

`/sls restart` reuses the retained live world. Back it up before `/sls reset`:
reset replaces the managed instance with a fresh assembly from `smp-seed`.

## 3. One Plugin JAR

Volumes cannot map a file. Use `state.copy` for one plugin JAR.

Source:

```text
plugins/sls-lite/
`-- volumes/plugins/one-off/MyPlugin.jar
```

Blueprint:

```yaml
blueprint:
  id: one_plugin
  name: One Plugin Example
  type: game
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  limits:
    max_instances: 1
save: false
state:
  copy:
    - source: volumes/plugins/one-off/MyPlugin.jar
      target: plugins/MyPlugin.jar
```

Result:

```text
instances/one_plugin.<suffix>/
|-- paper.jar
`-- plugins/
    `-- MyPlugin.jar
```

The source JAR is not the running JAR. Replacing it affects future assemblies
and persistent resets, not an already-running or normally restarted instance.

## 4. Shared Plugin Bundle

A directory copy places the directory's contents into the target. It does not
add another directory level.

Source:

```text
plugins/sls-lite/
`-- volumes/plugins/minigames/
    |-- LuckPerms.jar
    |-- WorldEdit.jar
    `-- shared-config/
        `-- settings.yml
```

Blueprint:

```yaml
blueprint:
  id: plugin_bundle
  name: Plugin Bundle Example
  type: minigame
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  limits:
    max_instances: 1
save: false
state:
  copy:
    - source: volumes/plugins/minigames/
      target: plugins/
```

Result:

```text
instances/plugin_bundle.<suffix>/
`-- plugins/
    |-- LuckPerms.jar
    |-- WorldEdit.jar
    `-- shared-config/
        `-- settings.yml
```

The trailing `/` is optional and does not change behavior. Directory sources
always merge their contents into the declared target.

## 5. Multiple Plugin Bundles

Copy entries run in declaration order. Later entries replace same-path files
from earlier entries, which makes a specific bundle a useful override.

Source:

```text
plugins/sls-lite/volumes/plugins/
|-- common/
|   |-- Permissions.jar
|   `-- settings.yml       <- common version
`-- spleef/
    |-- Spleef.jar
    `-- settings.yml       <- game-specific version
```

Blueprint:

```yaml
blueprint:
  id: merged_bundles
  name: Merged Plugin Bundles
  type: minigame
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  limits:
    max_instances: 1
save: false
state:
  copy:
    - source: volumes/plugins/common
      target: plugins
    - source: volumes/plugins/spleef
      target: plugins
```

Result:

```text
instances/merged_bundles.<suffix>/plugins/
|-- Permissions.jar
|-- Spleef.jar
`-- settings.yml           <- game-specific version wins
```

File-versus-directory type conflicts still fail the whole transactional
assembly instead of guessing.

## 6. Seed A Persistent Whitelist

Use a file copy to seed `whitelist.json`. With `save: true`, `/whitelist add`
and `/whitelist remove` change the retained instance file normally.

Source:

```text
plugins/sls-lite/
`-- volumes/whitelists/smp/whitelist.json
```

Blueprint:

```yaml
blueprint:
  id: whitelisted_smp
  name: Whitelisted SMP
  type: smp
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  configs:
    server.properties:
      parser: properties
      find:
        white-list: true
  limits:
    max_instances: 1
save: true
state:
  copy:
    - source: volumes/whitelists/smp/whitelist.json
      target: whitelist.json
```

Result:

```text
instances/whitelisted_smp.<suffix>/
`-- whitelist.json         <- live, command-modifiable retained copy

volumes/whitelists/smp/whitelist.json
                             <- unchanged seed
```

A normal restart retains command changes. Reset discards the live file and
copies the current seed again. This is not a shared or independently persistent
single-file mapping; that is a separate feature from current `state.copy`.

## 7. Patch Generated Configuration

Use `server.configs` when the desired value belongs in a known server file.
SLS-LITE applies patches after assembling the base and before enforcing its own
network, forwarding, port, online-mode, and capacity values.

Starting base:

```text
software/paper/<version>/
|-- server.properties
`-- bukkit.yml
```

Blueprint:

```yaml
blueprint:
  id: configured_game
  name: Configured Game
  type: game
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  configs:
    server.properties:
      parser: properties
      find:
        enable-command-block: true
        view-distance: 8
        simulation-distance: 6
    bukkit.yml:
      parser: yaml
      find:
        settings:
          allow-end: false
  limits:
    max_instances: 1
annotations:
  sls-lite:
    max-players: 16
save: false
```

Result:

```text
instances/configured_game.<suffix>/
|-- server.properties      <- requested values plus SLS-LITE-owned networking
`-- bukkit.yml             <- settings.allow-end is false
```

Do not patch SLS-LITE-owned ports, bind addresses, backend online mode,
forwarding, or capacity and expect the patch to win.

## 8. Same-Target COW Layers

Multiple `cow` directories may merge into one exact target. The first source
wins collisions; later sources only fill missing paths. This precedence is the
opposite of ordered `state.copy` replacements.

Sources:

```text
plugins/sls-lite/volumes/plugins/
|-- core/
|   |-- Core.jar
|   `-- settings.yml       <- first version
`-- game/
    |-- Game.jar
    `-- settings.yml       <- ignored collision
```

Blueprint:

```yaml
blueprint:
  id: cow_layers
  name: COW Layer Merge
  type: game
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  limits:
    max_instances: 1
save: false
state:
  volumes:
    - name: core_plugins
      source: volumes/plugins/core
      target: /plugins
      mode: cow
    - name: game_plugins
      source: volumes/plugins/game
      target: /plugins
      mode: cow
```

Result:

```text
instances/cow_layers.<suffix>/plugins/
|-- Core.jar
|-- Game.jar
`-- settings.yml           <- core version wins
```

Same-target merging is allowed only when every entry at that target is `cow`.
Ancestor/descendant targets such as `/plugins` and `/plugins/game` are rejected.

## 9. Private `ro` Snapshot

Local `ro` protects the source by making a private snapshot; it is not a strict
read-only filesystem inside the child server.

Source:

```text
plugins/sls-lite/
`-- volumes/worlds/archive/
    `-- region/
```

Blueprint:

```yaml
blueprint:
  id: archive_copy
  name: Archive Copy
  type: adventure
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  limits:
    max_instances: 1
save: false
state:
  volumes:
    - name: archive
      source: volumes/worlds/archive
      target: /world
      mode: ro
```

Result:

```text
instances/archive_copy.<suffix>/world/  <- private and writable by this server
volumes/worlds/archive/                 <- protected source
```

Use this when source protection is the important promise. Use `cow` when the
intent is explicitly an isolated writable working view selected through the
configured COW strategy.

## 10. Deliberately Shared `rw` Directory

`rw` links the instance target to one live host directory. This is an advanced
single-writer tool, not a faster default for worlds.

Source:

```text
plugins/sls-lite/
`-- volumes/shared/plugin-data/
    `-- database.db
```

Blueprint:

```yaml
blueprint:
  id: shared_directory
  name: Shared Directory
  type: service
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  limits:
    max_instances: 1
save: true
state:
  volumes:
    - name: shared_plugin_data
      source: volumes/shared/plugin-data
      target: /shared-data
      mode: rw
```

Result:

```text
instances/shared_directory.<suffix>/shared-data
    -> plugins/sls-lite/volumes/shared/plugin-data/
```

Writes immediately change the source. Restart, reset, and instance deletion do
not roll them back. The host must permit directory symbolic links, configuration
patches cannot traverse the link, and the consuming software must be trusted.
Keep `max_instances: 1` unless the application explicitly coordinates
concurrent writers. `save: true` retains the rest of this assembled instance;
the `rw` source would outlive it even with `save: false`. SLS-LITE does not back
up shared content.

## 11. Import A Complete Existing Paper Server

Use `server.path` when the entire directory is already a prepared Paper base.
Do not map that directory as a volume targeting `/`: both volume and copy
targets must name content inside the instance, and a root overlay would make
ownership, precedence, networking, and reset behavior ambiguous.

Prepared base:

```text
plugins/sls-lite/
`-- software/imports/existing-paper/
    |-- paper.jar
    |-- server.properties
    |-- plugins/
    |-- world/
    `-- eula.txt
```

Blueprint:

```yaml
blueprint:
  id: imported_paper
  name: Imported Paper Server
  type: smp
server:
  software: paper
  version: "REPLACE_WITH_EXACT_VERSION"
  path: imports/existing-paper
  limits:
    memory_limit: 2048
    max_instances: 1
annotations:
  sls-lite:
    max-players: 20
save: true
```

Result:

```text
software/imports/existing-paper/       <- unchanged operator template

instances/imported_paper.<suffix>/     <- SLS-LITE-owned live copy
|-- paper.jar
|-- plugins/
|-- world/
`-- server.properties                 <- loopback/port/capacity rewritten
```

The selected `paper` profile still defines the runtime, `configurator`, launch
arguments, readiness, shutdown, and expected `server_jar` name. The prepared
base must therefore contain the profile's `paper.jar` and genuinely match the
declared exact version. SLS-LITE will not identify or upgrade an arbitrary JAR.
Review and accept the Minecraft EULA before launch.

Before copying an old server into the template, consider removing logs, crash
reports, caches, stale lock files, temporary downloads, old backups, and other
reproducible waste. Keep worlds, plugin data, and configuration you actually
intend to clone. SLS-LITE overrides the bind address, allocated port, backend
online mode, forwarding, and capacity in the live copy.

A normal restart reuses the managed copy. Reset destructively replaces that
managed copy with a fresh copy of the current template; it does not modify the
original `software/imports/existing-paper/` directory. Back up the live
instance before resetting it.

## Rules That Prevent Surprises

- `state.volumes` sources must be directories. Use `state.copy` for a file.
- Volume targets may start with `/`; copy targets must be relative. Both must
  identify a path below the instance root. `/`, an empty target, and traversal
  such as `../outside` are rejected.
- A directory source copies or maps its contents into the target. A trailing
  slash is optional and never changes precedence.
- A volume cannot collide with content already supplied by the software base.
  Exact same-target `cow` layers are the only volume exception.
- Same-target COW is first-wins. Ordered `state.copy` is later-wins.
- `cow`, `ro`, and `state.copy` leave their source unchanged. `rw` deliberately
  changes its source.
- Persistent restart reuses the assembled instance. Reset reconstructs it from
  current sources and can destroy live changes.
- Source paths and copied trees may not contain symbolic links or special files.
- Use `/` separators in blueprint paths on every operating system.
- Never edit a source directory while SLS-LITE is assembling an instance from
  it. Keep independent backups of important templates and live persistent data.

After adding a recipe, run `/sls reload blueprints`, confirm the loaded count,
inspect it with `/sls blueprint <id>`, and test with disposable data before
importing anything important. See [Blueprints](Schema.md) for the complete
schema and [Blueprint Volumes](Volumes.md) for storage internals.
