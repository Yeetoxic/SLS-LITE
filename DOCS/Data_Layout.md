# SLS-LITE Data Layout

[Documentation home](README.md)

SLS-LITE keeps modern SLS-compatible concepts under its Velocity plugin data
directory while managing every process locally:

The operator-owned `config.yml` is never rewritten during an upgrade. Its
`config_version` is compared with the plugin's supported generation; migration
details and the complete current example live in the documentation instead of
duplicate generated files.

```text
plugins/sls-lite/
|-- blueprints/
|   |-- lobbies/
|   |-- minigames/
|   `-- adventures/
|-- volumes/
|   |-- worlds/
|   |-- plugins/
|   `-- whitelists/
|-- software/
|-- software-profiles/
|-- runtimes/
|-- instances/
|-- logs/
|   `-- sls-lite-detail.log[.1...]
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
      source: volumes/worlds/minigames/blastoff
      target: /world
      mode: cow
```

`state.copy` sources use the same contained data-directory root.
`volumes/plugins/` is the recommended operator location for plugins copied
into individual instances. It may contain individual JARs or grouped
directories such as `volumes/plugins/minigames/`; a `state.copy` directory
entry targeting `plugins/` installs the complete group into that instance.
Other shared assets may also be organized below `volumes/`; any contained
non-instance source path is valid. SLS-LITE creates `volumes/worlds/` and
`volumes/plugins/` empty on startup. It also creates `volumes/whitelists/` as
an obvious home for canonical `state.persistent_files` such as
`whitelist.json`. SLS-LITE never treats their contents as
generated data. The operator-facing `software/` and `runtimes/` roots are also
created empty so manual installations and Java runtimes have an obvious home.

`software/` is the reusable exact-version cache. `runtimes/` holds optional
operator-supplied Java installations. `instances/` contains prepared runtime
copies and is not a source-content directory. Managed child-output logs live
inside each instance at `logs/sls-lite-console.log`. SLS-LITE's own bounded,
rotating diagnostic stream lives at `logs/sls-lite-detail.log`; its level,
size, retention, redaction, and default-off Velocity-console mirroring are
configured under `detailed_logging`. `sls-limbo/` is an extracted,
reproducible runtime directory.

Operators should back up blueprints, volumes, software profiles, configuration,
administrators, and any persistent instances.

Persistent instance metadata schema 3 records the exact software ID and version
plus a fingerprint of the software profile, config patches, annotations,
volumes, copy declarations, persistent-file mappings, environment names and values, and persistence
policy used to prepare the directory. SLS-LITE refuses a normal restart when
that definition has changed, because reusing old files would silently mix
definitions. Use `/sls reset <instance>` after reviewing the change to rebuild
from the current definition.

Persistent directories made by schema-1 or schema-2 builds are migrated
non-destructively on their first restart. SLS-LITE logs that adoption because
those schemas cannot prove the complete current definition. Migration is
refused when the current blueprint has `save: false`; restore `save: true`
first so existing contents cannot be mistaken for an ephemeral instance.

Persistent reset uses sibling staging and backup directories. Startup
reconciliation restores the backup if a reset was interrupted before valid
metadata was written, or removes the backup when the replacement had already
committed.

Import archives and retired definitions belong outside the live blueprint
registry so they cannot appear in commands or tab completion.
