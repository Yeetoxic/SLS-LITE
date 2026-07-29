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
SLS-LITE currently reads only the lifecycle keys documented below.

## Metadata

| Field | Required | Rules |
| --- | --- | --- |
| `blueprint.id` | yes | Lowercase `[a-z0-9][a-z0-9_-]{0,63}`; globally unique. |
| `blueprint.name` | yes | Non-blank display name. |
| `blueprint.type` | yes | Non-blank dynamic registry name. |
| `server.software` | yes | ID of a loaded software profile. |
| `server.version` | yes | Exact Minecraft/software version string. |
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

Other YAML, JSON, and TOML patchers are not implemented. Unsupported structural
config targets are rejected instead of ignored.

## Volumes

SLS-LITE accepts modern SLS `state.volumes` entries with `mode: cow`. The local
portable implementation copies the source into each isolated instance.

- `source` is relative to `plugins/sls-lite/`.
- `target` is an instance path such as `/world`.
- source paths, symlinks, traversal, overlap, and template collisions are
  rejected.

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

`start-on-proxy-start` is roadmap work. Because annotations are open-ended, the
key can be preserved in an imported blueprint, but it currently has no effect.

## Resource Packs

A copied `resources.zip` is preserved but is not automatically reachable by
clients. Set supported `server.properties` resource-pack fields to a public
HTTP(S) URL. See [Resource Packs](Resource_Packs.md).

## Modern SLS Boundary

The field shape above is the currently implemented subset, not the final Stage
2 compatibility contract. Unsupported modern SLS structural fields are
rejected with their path; unknown annotations are preserved in memory. Stage 2
will test representative unmodified upstream definitions and publish a
field-by-field matrix.
