# Contributing

[Documentation home](../README.md)

In this branch: [architecture](Architecture.md),
[contributor architecture](Contributor_Architecture.md),
[internal invariants](Internal_Invariants.md), [testing](Testing.md),
[Velocity testing](Velocity_Testing.md),
[local Pterodactyl testing](Pterodactyl_Local_Testing.md), and the
[release process](Release_Process.md).

Changes should remain small, reviewable, compatible with the single-host goal,
and covered in proportion to their lifecycle or data risk.

## Build Environment

- JDK 25. The pinned Velocity API contains Java 25 class files, while Maven
  still emits SLS-LITE as Java 21 bytecode through `--release 21`.
- Maven 3.9 or newer.
- Current pinned Velocity API dependencies.
- Docker Desktop, WSL, Node, and Minecraft clients only for their respective
  integration fixtures.

Maven packaging uses the fixed `project.build.outputTimestamp` in `pom.xml` so
the same source and dependency inputs produce byte-identical JARs. Update that
timestamp only as an explicit release/build-metadata decision; changing it
changes every published checksum even when compiled code is unchanged.

Run:

```powershell
mvn verify
```

`verify` enforces Google Java Format through Spotless and runs the high-priority
SpotBugs gate at maximum analysis effort. Before verification, apply the pinned
formatter with:

```powershell
mvn spotless:apply
```

Use `mvn spotless:check` for a formatting-only check. Static-analysis findings
must be reviewed; do not add broad exclusions or lower the configured gate to
make a build pass.

Do not commit generated `target/`, local Pterodactyl state, credentials,
imported worlds, server caches, logs, or test allocation data.

## Design Rules

- Keep SLS-LITE operationally independent from full SLS.
- Prefer modern SLS names and semantics for genuinely shared concepts.
- Adapt distributed behavior truthfully to one host.
- Reject unsupported structural configuration instead of silently ignoring it.
- Keep local-only metadata namespaced.
- Never use a command shell to launch managed software.
- Preserve loopback binding, path containment, bounded logs, and conservative
  deletion.
- Do not claim container enforcement from JVM arguments or admission counters.
- Avoid broad refactors mixed with behavioral changes.

## Change Map

Read [Architecture](Architecture.md) for ownership and use the
[Contributor Architecture Guide](Contributor_Architecture.md) to identify the
implementation, tests, defaults, and documentation that must change together.
Update all affected:

- implementation and focused tests;
- bundled commented examples;
- public reference documentation;
- compatibility matrix or intentional-difference explanation;
- roadmap status when a tracked item is completed.

## Documentation Standard

Public docs must describe supported behavior and explicit product boundaries,
not development plans. Proposed features belong only in the
[project plan](../../todo.md) until implemented.

Use these labels where relevant:

- `SLS and SLS-LITE`
- `Adapted for local mode`
- `SLS-LITE only`
- `Full SLS only`
- `Unsupported by SLS-LITE`

Commands, permissions, fields, defaults, valid ranges, restart requirements,
security implications, and destructive behavior must be explicit.

## Compatibility Work

Compare behavior against the current upstream SLS `main` branch. Record the
audit in development or release evidence, but keep operator documentation free
of transient commit hashes and release pins. Classify each feature as supported,
adapted, SLS-LITE-only, or intentionally unsupported. Proposed work belongs
only in `todo.md`. Do not copy upstream code or fixtures without checking their
license and attribution requirements.

## Release Artifacts

A release must be built from a reviewed source revision, pass the complete
automated and documented manual suites, contain required license and
third-party material, and publish a checksum.

Run the manually triggered **API distribution smoke** workflow after changing
the Java API or developer-artifact packaging. It creates a private draft GitHub
Release, downloads the plugin, API, sources, Javadocs, and checksum file on a
separate clean runner, and compiles the example extension with both Maven and
Gradle against only the downloaded API classifier. Leave `cleanup_draft`
disabled when the draft needs human inspection; enable it only when the same
run may delete its temporary draft release and tag after testing. This internal
draft is a delivery-path test, not a release candidate.
