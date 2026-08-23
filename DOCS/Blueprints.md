# Blueprints

[Documentation home](README.md)

Blueprints describe launchable server types. SLS-LITE recursively loads
`.yml` and `.yaml` files below `plugins/sls-lite/blueprints/`. Folder names are
only organization; `blueprint.type` is the dynamic registry used by commands.
Blueprint IDs must be globally unique.

## Complete Supported Shape

```yaml
blueprint:
  id: biome_run
  name: Biome Run
  type: minigame

server:
  software: paper
  version: "1.18.2"
  # Optional modern SLS local adaptations:
  # image: java_17
  # path: paper/1.18.2
  limits:
    memory_limit: 1024
    max_players: 12
    max_instances: 2
  configs:
    server.properties:
      parser: properties
      find:
        enable-command-block: true
        view-distance: 8
    bukkit.yml:
      parser: yaml
      find:
        settings:
          allow-end: false

save: false

state:
  volumes:
    - name: world
      source: volumes/worlds/minigames/biome_run
      target: /world
      mode: cow

annotations:
  sls-lite:
    keep-alive: false
    idle-shutdown-seconds: 180
    stop-when-empty: true
```

Unknown structural fields are rejected. `annotations` is intentionally
open-ended so modern SLS and third-party metadata can survive loading.
SLS-LITE reads the local lifecycle keys and the vSLS compatibility keys
documented below.

## Metadata

| Field | Required | Rules |
| --- | --- | --- |
| `blueprint.id` | yes | Lowercase `[a-z0-9][a-z0-9_-]{0,63}`; globally unique. |
| `blueprint.name` | yes | Non-blank display name. |
| `blueprint.type` | yes | Non-blank dynamic registry name. |
| `server.software` | yes | ID of a loaded software profile. |
| `server.version` | yes | Exact Minecraft/software version string. |
| `server.image` | no | Modern `java_<major>` selector; requires a matching local Java runtime unless it matches the proxy JVM. |
| `server.path` | no | Relative manually prepared base path below `plugins/sls-lite/software/`; bypasses provider installation. |
| `server.limits.memory_limit` | no | Positive MiB; inherits a modern software definition's `limits.memory_limit`, otherwise defaults to `1024`. |
| `server.limits.max_players` | no | Positive public player slots per instance; full-SLS/vSLS default `10000`. |
| `server.limits.max_instances` | no | Positive concurrent instances; defaults to `unlimited` in operator output, represented internally by the full-SLS/vSLS value `2147483647`. Host memory, process, and port admission still bound actual concurrency. |
| `save` | no | Boolean persistence policy; default `false`. |

SLS-LITE prefers a ready instance with capacity. It creates another instance
only when needed and allowed by `max_instances`, resource admission, process
slots, and available ports. Queued and in-flight joins reserve player capacity.
The public limit is enforced for matchmaking, direct joins, and native Velocity
server-selection routes.

For `save: true`, new creation does not silently replace an inactive retained
instance of the same blueprint. Restart or reset the named retained instance to
reuse its exact ID, or delete it explicitly before creating a replacement. This
also prevents a failed start or unresolved persistent-file conflict from
accumulating additional saved instance directories through repeated start or
matchmaking requests.

After a proxy restart, matchmaking resumes the blueprint's single retained
persistent instance instead of allocating a new ID. If legacy storage contains
multiple retained IDs for one blueprint, matchmaking refuses to choose between
them and names the conflict so an operator can delete the unwanted copies.

The generated backend `max-players` may be higher than this public limit. That
bounded technical headroom exists only so an authorized administrator can use
`/sls join player <player> --force` without also needing Paper operator access.
It does not increase ordinary capacity: SLS-LITE enforces `max_players` at the
proxy for every non-forced route.

### Base Template Decision

SLS-LITE does not add a second blueprint-level `base_template` field. The
selected software directory is already the instance base. Modern
`state.volumes` then supplies clean directory trees with private `cow` or
source-protecting `ro` semantics, while `state.copy` overlays individual
operator assets. Together these cover the useful local behavior of a separate
template without introducing another source tree, precedence rule, ownership
model, or reset path.

Use a manually prepared `server.path` only when the entire software base is
operator-managed. Use `state.volumes` for clean worlds or directory trees and
`state.copy` for plugins, icons, packs, and other files. Persistent restart
reuses the owned instance; reset reconstructs it from those declared sources.

## Persistence

`save: false` creates an ephemeral instance. Its private directory is removed
after a clean stop and is eligible for idle shutdown.

`save: true` preserves the instance directory and composite ID. Persistent
instances can be restarted or reset and are resumed when they are the managed
lobby. SLS-LITE records an ownership schema and definition fingerprint.
Definition drift blocks a normal restart so old data is not silently paired
with new software, properties, annotations, volumes, or persistence policy.

```text
/sls restart <instance-id>
/sls reset <instance-id>
```

Restart reuses the directory. Reset transactionally rebuilds it from current
software and volume sources. Review reset carefully because it replaces the
managed copy. The complete reload and lifecycle decision table is in
[Applying Changes Safely](Change_Application.md).

## Server Properties

The supported modern-style patch target is:

```yaml
server:
  configs:
    server.properties:
      parser: properties
      find:
        key: value
```

Keys may contain letters, numbers, dots, underscores, and hyphens. Values must
be a scalar string, number, or boolean without line breaks. SLS-LITE writes
these properties atomically, then enforces managed values such as loopback
address, allocated port, backend online mode, and player capacity.

Nested YAML map patches are supported for contained `.yml` and `.yaml` targets:

```yaml
server:
  configs:
    spigot.yml:
      parser: yaml
      find:
        settings:
          moved-wrongly-threshold: 1000
```

SLS-LITE recursively merges the configured map into the existing file and
writes it atomically. Target traversal, symbolic links, non-map YAML roots, and
unsupported value types are rejected. JSON, TOML, and arbitrary properties
targets remain unsupported.

`view-distance` and `simulation-distance` are validated as integer values from
`2` through `32`. When both are set, simulation distance may not exceed view
distance. A recognized Minecraft version older than `1.18` rejects
`simulation-distance`; provider-specific version schemes that cannot be mapped
reliably are range-checked without guessing a Minecraft release. Per-create
`--view-distance` and `--simulation-distance` overrides follow the same rules
and persist across restart/reset.

String values in properties, nested YAML patches, and text-file replacement
outputs may use these runtime placeholders:

| Placeholder | Value |
| --- | --- |
| `{instance_id}` | Allocated composite instance ID. |
| `{blueprint_id}` | Loaded blueprint ID. |
| `{version}` | Exact configured software/Minecraft version. |
| `{port}` | Allocated loopback backend port. |
| `{max_players}` | Effective public per-instance player limit. |
| `{memory_mib}` | Effective managed-memory reservation in MiB. |

Unknown lowercase runtime placeholders reject preparation rather than leaking
an unresolved token into a child configuration. Patch keys and text matching
prefixes are literal and are never expanded.

Startup patch precedence is deterministic:

1. Existing files from the prepared software/volume/copy template provide the
   base document.
2. Software-profile `server.properties` defaults override that base.
3. Blueprint properties override profile defaults; nested blueprint YAML maps
   recursively merge, and text patches replace matching complete lines.
4. Validated per-create overrides modify the effective blueprint properties.
5. SLS-LITE-owned network, forwarding, port, online-mode, and capacity values
   win last.

Every target is resolved below the prepared instance root. Traversal,
symbolic-link paths, wrong file types, ambiguous prefixes, and input or output
larger than 8 MiB are rejected. Properties, YAML, forwarding, and text writes
use randomized sibling temporary files and atomic replacement when the
filesystem supports it. JSON/TOML parsers are not added because no approved
retained blueprint requires them.

### Memory Input Contract

`server.limits.memory_limit` is a YAML integer measured in MiB. It must be
positive and fit Java's signed 32-bit integer range. Quoted values, decimals,
units such as `2G` or `2048MiB`, zero, negatives, booleans, and expressions are
rejected. The create-time `--memory=<MiB>` form likewise accepts only a
positive base-10 integer; it does not parse unit suffixes.

### Text File Patches

Modern SLS `parser: file` performs line-prefix replacement:

```yaml
server:
  configs:
    whitelist.json:
      parser: file
      find:
        "[]": '[{"uuid":"...","name":"admin"}]'
```

For every existing line beginning with a `find` key, SLS-LITE replaces the
whole line with the mapped scalar value. It does not append a replacement when
the prefix is absent. A missing target is created as an empty file, matching
SLS main behavior. Prefixes may not overlap; definitions such as `server-`
and `server-port=` are rejected because one input line could match both.

Targets must remain inside the instance and may not traverse symbolic links.
Files must be UTF-8 regular files no larger than 8 MiB. Writes use a sibling
temporary file and atomic replacement when supported; failures preserve the
original target. Output line endings are normalized to LF with a final newline
when the input contained at least one line.

## Volumes

SLS-LITE accepts modern SLS `state.volumes` entries using `cow`, `ro`, or `rw`.
The portable `cow` implementation copies and merges sources into each isolated
instance. `ro` becomes a source-protecting private snapshot. Explicit `rw`
creates a verified directory link to shared host data. That source persists
independently of `save`; concurrent instances share it, so `max_instances: 1`
is recommended unless the software safely coordinates concurrent access.

Mapping and shorthand forms are accepted:

```yaml
state:
  volumes:
    - name: world
      source: volumes/worlds/archive
      target: /world
      mode: cow
    - "nether:volumes/worlds/archive/DIM-1:/world_nether/DIM-1:cow"
```

The shorthand shape is `name:source:target[:mode]`; omitted mode defaults to
`cow`.

- `source` is relative to `plugins/sls-lite/`.
- `target` is an instance path such as `/world`.
- exact same-target `cow` entries merge in declaration order, with the first
  source winning file collisions.
- source paths, symlinks, traversal, ancestor/descendant overlap, and template
  collisions are rejected.

See [Blueprint Volumes](Blueprint_Volumes.md) for transactional and filesystem
details.

## State Copies

Modern SLS `state.copy` entries place blueprint assets into a prepared instance
after the software base and volumes:

```yaml
state:
  copy:
    # Shared plugin bundle for this blueprint or game category.
    - source: volumes/plugins/minigames/
      target: plugins/
    # Optional one-off plugin.
    - source: volumes/plugins/example.jar
      target: plugins/example.jar
    - "volumes/server-icon.png:server-icon.png"
```

Both mapping and `source:target` shorthand forms are accepted. Sources are
relative to `plugins/sls-lite/`; targets are relative to the instance root.
Files replace an existing file, while directories merge and replace matching
files in declaration order. A directory source such as
`volumes/plugins/minigames/` therefore copies every plugin and subdirectory in
that bundle into the instance's `plugins/` directory. Organize bundles by game
type, blueprint, or any other operator convention; SLS-LITE does not assign
meaning to the folder name. Later copy entries can deliberately replace a file
from an earlier bundle.

Copy preparation is transactional with the software and volumes. Missing
sources, traversal, symbolic links, special files, and file/directory type
conflicts fail preparation and remove the incomplete instance. Persistent
instances refresh copy sources during `/sls reset`, not every restart, so an
operator-controlled source change cannot silently mutate an existing server.

## Persistent Files

`state.persistent_files` is for small root files that Minecraft rewrites and
that must outlive a disposable or resettable instance, such as
`whitelist.json`, `ops.json`, `banned-players.json`, or `server-icon.png`:

```yaml
state:
  persistent_files:
    - name: whitelist
      source: volumes/whitelists/lobby/whitelist.json
      target: whitelist.json
```

The source is the canonical operator-owned file below
`plugins/sls-lite/volumes/` and must already exist. Restricting write-back to
that operator state root prevents a managed server from overwriting host
configuration, blueprints, software, or SLS-LITE metadata. SLS-LITE imports it
as an ordinary private file after
volumes and copies, then publishes the stopped server's version atomically back
to the source. This supports applications such as Paper that replace JSON files
rather than editing them in place; it does not use symbolic links, bind mounts,
or `/dev/fuse`.

Each canonical source has exactly one active writer. A second instance is
rejected until the first stops. On restart, an external source edit is imported
when the stopped instance copy is unchanged. If both copies changed
independently, SLS-LITE preserves the canonical source, stores the instance
candidate below `internal/persistent-file-conflicts/`, and reports an actionable
failure instead of guessing. The previous canonical value is retained below
`internal/persistent-file-backups/` before a changed value is published.

To resolve a conflict, keep the affected instance stopped and inspect both the
canonical source and preserved `candidate` named in the diagnostic. Back up
both values, copy the chosen or manually merged result into the canonical
source, and remove the `candidate` only after that result is safely in place.
The next start or explicit reset imports the resolved canonical value. Do not
delete a conflict candidate merely to silence the diagnostic: it is the only
copy of the instance-side edit SLS-LITE deliberately refused to overwrite.

Only non-symbolic regular files are accepted. A blueprint may declare at most
32 persistent files; each file is limited to 8 MiB and the aggregate mapping is
limited to 32 MiB. Normal stop, crash finalization, persistent reset/delete,
and startup reconciliation publish before instance storage is unmounted or
removed. `save` controls the assembled directory, not the canonical file: a
persistent-file source survives even when `save: false`.

## State Environment

Modern SLS `state.env` values are passed to the locally managed child process:

```yaml
state:
  env:
    FEATURE_FLAG: "true"
    PUBLIC_ENDPOINT: "https://example.test"
```

Names use portable environment syntax and values must be strings. A blueprint
may define at most 64 variables, each value is limited to 8 KiB, and the total
declaration is limited to 64 KiB. Names that can alter Java startup, executable
resolution, native library loading, or SLS-LITE-owned behavior are rejected,
including `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`,
`CLASSPATH`, `PATH`, `LD_*`, `DYLD_*`, and `SLS_*`.

Environment variable names appear in administrative blueprint details, but
SLS-LITE does not log values or show them in hover text. The child process and
its plugins can still print their own environment. Blueprint files are
plaintext; `state.env` is not a secret store.

## Lifecycle Annotations

SLS-LITE reads these values under `annotations.sls-lite`:

| Key | Type | Behavior |
| --- | --- | --- |
| `keep-alive` | boolean | `true` excludes the blueprint from idle cleanup. |
| `stop-when-empty` | boolean | `false` is also treated as keep-alive. |
| `idle-shutdown-seconds` | non-negative integer | Overrides the host idle delay; `0` disables it for this blueprint. |
| `queue-timeout-seconds` | non-negative integer | Overrides the host matchmaking queue lifetime; `0` explicitly disables expiry for this blueprint. |
| `startup-timeout-seconds` | integer `1-3600` | Overrides the software profile readiness deadline for this blueprint. |
| `stop-timeout-seconds` | integer `1-600` | Overrides the software profile graceful-stop deadline for this blueprint. |
| `restart-on-crash` | boolean | Opts a persistent non-lobby instance into bounded automatic recovery. Default: `false`. |
| `restart-max-attempts` | integer `0-100` | Maximum recovery attempts before the instance remains stopped. Default: `3`. |
| `restart-initial-backoff-seconds` | integer `1-86400` | Delay before the first recovery attempt. Default: `5`. |
| `restart-max-backoff-seconds` | integer `1-86400` | Cap for exponential recovery backoff; not less than the initial delay. Default: `60`. |
| `restart-stable-after-seconds` | integer `1-86400` | Ready runtime required before resetting the consumed attempt budget. Default: `120`. |

Persistent instances and the active managed lobby are excluded from ordinary
idle cleanup regardless of annotation.

Crash recovery requires `save: true`; ephemeral recovery is rejected because
there is no durable identity to restart safely. Do not enable it on the managed
lobby, whose independently bounded `lobby.recovery` policy owns routing and
generation control. Operator stop, kill, restart, reset, delete, maintenance,
and proxy shutdown never count as crashes. See
[Lifecycle Concurrency](Lifecycle_Concurrency.md).

SLS-LITE also accepts these established vSLS annotations:

```yaml
annotations:
  vsls:
    dont-stop-when-empty: true
    max-instances: 2
    matchmaking:
      maxPlayers: 12
      gameType: party
    on-join:
      - run: "say Welcome {PLAYER_NAME}"
```

`dont-stop-when-empty` excludes the blueprint from idle cleanup.
`max-instances` and `matchmaking.maxPlayers` supply capacity defaults when the
SLS-LITE `server.limits.max_instances` and `max_players` extensions are
omitted. Explicit local limits take precedence. Missing capacity values follow
full SLS: `10000` players and `2147483647` instances. Invalid values reject the
blueprint with an actionable error instead of silently selecting a default.

`matchmaking.gameType` groups blueprints into one local matchmaking pool. A
join still names a blueprint in its normal `blueprint.type` registry, but
SLS-LITE may reuse or provision another blueprint with the same game type when
capacity requires it.

`on-join` accepts at most 32 single-line `run` actions of at most 4096
characters each. SLS-LITE sends them to the managed backend console after the
player connects and replaces `{PLAYER_NAME}` and `{PLAYER_UUID}` with the
connecting player's identity. Actions run once per backend transition and are
cleared when the player disconnects. Malformed actions reject the definition
during reload.

`start-on-proxy-start` is not supported. Because annotations are open-ended,
the key can be preserved in an imported blueprint, but it has no effect.

## Resource Packs

A copied `resources.zip` is preserved but is not automatically reachable by
clients. Set supported `server.properties` resource-pack fields to a public
HTTP(S) URL. See [Resource Packs](Resource_Packs.md).

## Modern SLS Boundary

The field shape above is the implemented local subset. Unsupported modern SLS
structural fields are rejected with their path; unknown annotations are
preserved in memory. See the
[SLS main compatibility matrix](SLS_Main_Compatibility.md) for the maintained,
field-by-field boundary.

Blueprint parsing does not require volume source directories to exist. This
allows definitions to be reviewed and reloaded before optional world content
is installed. Starting an affected blueprint still fails with an actionable
content error if its required source is absent.

The bundled blueprint template is the canonical editable example. Exact
software versions are quoted, optional policy overrides remain commented until
selected, and its volume/copy/environment examples use the same confined paths
and private-storage semantics enforced by the parser.
