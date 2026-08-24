# Applying Changes Safely

[Documentation home](../README.md)

SLS-LITE separates definition loading from instance lifecycle. Reloading a
blueprint or software profile updates the assembly instructions; it never
rewrites, restarts, or disconnects an existing managed server.

The short rule is:

- changed `config.yml` or a plugin JAR: restart Velocity;
- changed a blueprint or software profile: reload definitions;
- changed files used to assemble a persistent server: reset that instance when
  you deliberately want it rebuilt.

Use the table below when the change does not fit one of those cases.

## What Applies When

| Change | Required action | Existing running instance |
| --- | --- | --- |
| `config.yml` | Restart Velocity | Continues until normal proxy shutdown; no partial live configuration is applied. |
| SLS-LITE or extension plugin JAR | Restart Velocity | Plugin replacement is not a supported hot-reload operation. |
| Blueprint or software definition | `/sls reload blueprints`, `/sls reload software`, `/sls reload all`, or restart Velocity | Keeps the immutable definition loaded when that instance was assembled. |
| `state.copy` source, software-base file, or private `cow`/`ro` source | Create a new instance, or explicitly reset a persistent instance | Source changes do not mutate an existing assembly. |
| `state.persistent_files` source | Restart a stopped instance to import an external edit; normal managed stop publishes instance changes | Concurrent external and instance edits are rejected as a conflict rather than merged. |
| Shared `rw` volume contents | No definition reload is required | The instance uses the deliberately shared live directory. Coordinate a safe application-level reload or restart when the server software requires one. |

After a successful definition reload, SLS-LITE reports how many running and
persistent instances use the changed definitions. This is guidance only: it
does not modify those instances automatically.

## Restart Versus Reset

`/sls restart <instance>` preserves the existing persistent instance directory.
It is appropriate when the goal is to cycle the Java process without rebuilding
the server. SLS-LITE compares the recorded definition fingerprint with the
current definition and rejects restart when software, generated configuration,
annotations, volumes, copies, persistent files, or persistence ownership drifted. This prevents
old data from being silently paired with new assembly instructions.

`/sls reset <instance>` is an explicit rebuild. It transactionally replaces the
owned instance directory using the current software base, blueprint properties,
declared volumes, `state.copy` sources, and canonical persistent files, while keeping the persistent instance
ID and approved create-time overrides. Review it before running: private changes
inside the managed instance directory are replaced. Deliberately external `rw`
volume data remains owned by its source directory rather than the assembly.
Before reset or deletion, a running instance publishes its persistent files;
the reset then imports those canonical values into the replacement assembly.

For a changed `state.copy` mapping or source file, use reset rather than restart when the
persistent server should receive the new file.

## Rejected Definitions

Blueprint files are isolated during reload. A malformed or invalid blueprint is
reported in the SLS-LITE detail log while valid siblings remain available. If a
previously valid blueprint becomes invalid, its already-running instances keep
their loaded definition, but SLS-LITE cannot assemble another instance from that
blueprint until it is corrected and reloaded. A stopped persistent instance also
remains on disk; correct and reload its definition before choosing restart or
reset.

Never edit files inside a running instance to imitate a definition reload. Edit
the authoritative blueprint, software profile, or declared source, reload the
definition catalog, inspect the reported impact, and then choose the lifecycle
action deliberately.
