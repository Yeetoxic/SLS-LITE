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
`-- logs/
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

`software/` is the reusable exact-version cache. `instances/` contains prepared
runtime copies and is not a source-content directory. Operators should back up
blueprints, worlds, software profiles, configuration, and any persistent
instances.

The Stage 1 test allocation uses the historical network directly from the
organized `worlds/lobbies`, `worlds/minigames`, and `worlds/adventures`
directories. Import archives and retired fixtures belong outside the live
blueprint registry so they cannot appear in commands or tab completion.
