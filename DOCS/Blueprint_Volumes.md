# Blueprint Volumes

Status: SLS and SLS-LITE field shape; adapted for SLS-LITE local mode.

SLS-LITE supports the modern SLS `state.volumes` structure for placing locally
supplied worlds and other directory content into a managed server instance.
The initial supported mode is `cow`.

```yaml
state:
  volumes:
    - name: world
      source: worlds/minigames/spleef
      target: /world
      mode: cow
```

## Local `cow` Behavior

Full SLS can provide copy-on-write storage through its node infrastructure.
SLS-LITE must work without that infrastructure, so it implements the same
isolation outcome as a portable directory copy:

1. SLS-LITE copies the selected software base into a new instance directory.
2. It copies each volume source into its configured instance target.
3. The managed server changes only its private copy.
4. Ephemeral cleanup removes that copy. Persistent instances retain it until
   reset or deletion.
5. Resetting a persistent instance recopies both its software base and its
   clean volume sources.

This baseline uses more disk space and startup time than filesystem-native
copy-on-write. It works on ordinary shared-host filesystems without requiring
Docker mounts, overlay filesystems, or elevated privileges.

## Paths

`source` is relative to the SLS-LITE plugin data directory. For example,
`worlds/minigames/spleef` resolves to:

```text
<SLS-LITE data>/worlds/minigames/spleef
```

Blueprint YAML files can be organized into nested folders below `blueprints/`.
The folder name is for operators only; the blueprint's `blueprint.type` remains
the registry used by commands and matchmaking.

`target` uses the modern SLS instance-path form. `/world` maps to the `world`
directory at the root of the newly prepared managed instance. A target without
the leading slash, such as `world`, has the same local result.

Volume paths must use `/` separators. SLS-LITE rejects:

- Absolute or escaping source paths.
- Targets that escape or select the instance root.
- Symbolic links in a source path or anywhere inside copied content.
- Sources inside the managed instances directory.
- Overlapping volume targets.
- Targets that collide with files or directories from the software base.

Preparation is transactional. If the software or any volume cannot be copied,
SLS-LITE removes the incomplete instance. A failed persistent reset preserves
the previous instance directory.

## Current Limits

- Only `mode: cow` is supported.
- `rw` and `ro` host-mounted volumes are rejected because they would expose
  shared mutable host state and behave differently across providers.
- Volume sources must already exist before the blueprint is started.
- Operators must budget disk space for a complete copy per instance.
- Do not modify a source directory while an instance is being prepared.

These limits define the portable baseline. Native copy-on-write optimizations
may be added later only when they preserve the same blueprint behavior and have
a reliable portable fallback.
