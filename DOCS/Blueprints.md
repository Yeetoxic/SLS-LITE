# Blueprints

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
      source: worlds/minigames/biome_run
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
| `server.limits.memory_limit` | no | Positive MiB; default `1024`. |
| `server.limits.max_players` | no | Positive slots per instance; default `20`. |
| `server.limits.max_instances` | no | Positive concurrent instances; default `1`. |
| `save` | no | Boolean persistence policy; default `false`. |

SLS-LITE prefers a ready instance with capacity. It creates another instance
only when needed and allowed by `max_instances`, resource admission, process
slots, and available ports. Queued joins reserve player capacity.

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
managed copy.

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
SLS v0.2.0 behavior.

Targets must remain inside the instance and may not traverse symbolic links.
Files must be UTF-8 regular files no larger than 8 MiB. Writes use a sibling
temporary file and atomic replacement when supported; failures preserve the
original target. Output line endings are normalized to LF with a final newline
when the input contained at least one line.

## Volumes

SLS-LITE accepts modern SLS `state.volumes` entries using `cow`, `ro`, or `rw`.
The portable `cow` implementation copies and merges sources into each isolated
instance. `ro` becomes a source-protecting private snapshot; `rw` parses but is
rejected when an instance is prepared because shared writable mounts cannot be
provided safely in portable local mode.

Mapping and shorthand forms are accepted:

```yaml
state:
  volumes:
    - name: world
      source: worlds/archive
      target: /world
      mode: cow
    - "nether:worlds/archive/DIM-1:/world_nether/DIM-1:cow"
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

## Lifecycle Annotations

SLS-LITE reads these values under `annotations.sls-lite`:

| Key | Type | Behavior |
| --- | --- | --- |
| `keep-alive` | boolean | `true` excludes the blueprint from idle cleanup. |
| `stop-when-empty` | boolean | `false` is also treated as keep-alive. |
| `idle-shutdown-seconds` | non-negative integer | Overrides the host idle delay; `0` disables it for this blueprint. |

Persistent instances and the active managed lobby are excluded from ordinary
idle cleanup regardless of annotation.

SLS-LITE also accepts these established vSLS annotations:

```yaml
annotations:
  vsls:
    dont-stop-when-empty: true
    max-instances: 2
    matchmaking:
      maxPlayers: 12
```

`dont-stop-when-empty` excludes the blueprint from idle cleanup.
`max-instances` and `matchmaking.maxPlayers` supply capacity defaults when the
SLS-LITE `server.limits.max_instances` and `max_players` extensions are
omitted. Explicit local limits take precedence. Missing or invalid vSLS
capacity values retain the constrained defaults of one instance and 20
players.

`matchmaking.gameType` and `on-join` are preserved as metadata but do not yet
affect runtime behavior. Their compatibility semantics are tracked separately
for Stage 2.

`start-on-proxy-start` is roadmap work. Because annotations are open-ended, the
key can be preserved in an imported blueprint, but it currently has no effect.

## Resource Packs

A copied `resources.zip` is preserved but is not automatically reachable by
clients. Set supported `server.properties` resource-pack fields to a public
HTTP(S) URL. See [Resource Packs](Resource_Packs.md).

## Modern SLS Boundary

The field shape above is the currently implemented subset, not the final Stage
2 compatibility contract. Unsupported modern SLS structural fields are
rejected with their path; unknown annotations are preserved in memory. See the
[SLS v0.2.0 compatibility matrix](SLS_v0.2.0_Compatibility.md) for the pinned,
field-by-field boundary.

Blueprint parsing does not require volume source directories to exist. This
allows definitions to be reviewed and reloaded before optional world content
is installed. Starting an affected blueprint still fails with an actionable
content error if its required source is absent.
