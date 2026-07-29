# SLS-LITE Data Layout

SLS-LITE keeps modern SLS-compatible concepts under its Velocity plugin data
directory while managing every process locally:

```text
plugins/sls-lite/
|-- blueprints/
|   |-- lobbies/
|   |-- minigames/
|   `-- adventures/
|-- worlds/
|   |-- lobbies/
|   |-- minigames/
|   `-- adventures/
|-- software/
|-- software-profiles/
|-- runtimes/
|-- instances/
|-- sls-limbo/
`-- administrators.properties
```

Blueprint files are discovered recursively below `blueprints/`. Folder names
are an operator organization tool; `blueprint.type` remains the dynamic
registry name used by commands and matchmaking. Duplicate blueprint IDs are
rejected even when the files are in different folders.

Volume `source` paths remain relative to the SLS-LITE data directory, matching
modern SLS blueprint vocabulary. For example:

```yaml
state:
  volumes:
    - name: world
      source: worlds/minigames/blastoff
      target: /world
      mode: cow
```

`software/` is the reusable exact-version cache. `runtimes/` holds optional
operator-supplied Java installations. `instances/` contains prepared runtime
copies and is not a source-content directory. Managed temporary logs live
inside each instance at `logs/sls-lite-console.log`; there is no host-wide log
archive. `sls-limbo/` is an extracted, reproducible runtime directory.

Operators should back up blueprints, worlds, software profiles, configuration,
administrators, and any persistent instances.

Persistent instance metadata schema 3 records the exact software ID and version
plus a fingerprint of the software profile, server properties, annotations,
volumes, and persistence policy used to prepare the directory. SLS-LITE refuses
a normal restart when that definition has changed, because reusing old files
would silently mix definitions. Use `/sls reset <instance>` after reviewing the
change to rebuild from the current definition.

Persistent directories made by schema-1 or schema-2 builds are migrated
non-destructively on their first restart. SLS-LITE logs that adoption because
those schemas cannot prove the complete current definition. Migration is
refused when the current blueprint has `save: false`; restore `save: true`
first so existing contents cannot be mistaken for an ephemeral instance.

Persistent reset uses sibling staging and backup directories. Startup
reconciliation restores the backup if a reset was interrupted before valid
metadata was written, or removes the backup when the replacement had already
committed.

The historical regression allocation uses the network directly from organized
`worlds/lobbies`, `worlds/minigames`, and `worlds/adventures` directories.
Import archives and retired fixtures belong outside the live blueprint registry
so they cannot appear in commands or tab completion.
