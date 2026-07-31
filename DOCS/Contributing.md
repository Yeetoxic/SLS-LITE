# Contributing

SLS-LITE is under active pre-release development. Changes should remain small,
reviewable, compatible with the single-host goal, and covered in proportion to
their lifecycle or data risk.

## Build Environment

- JDK 21 or newer.
- Maven 3.9 or newer.
- Current pinned Velocity API dependencies.
- Docker Desktop, WSL, Node, and Minecraft clients only for their respective
  integration fixtures.

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

Read [Architecture](ARCHITECTURE.md) for ownership and use the
[Contributor Architecture Guide](Contributor_Architecture.md) to identify the
implementation, tests, defaults, and documentation that must change together.
Update all affected:

- implementation and focused tests;
- bundled commented examples;
- public reference documentation;
- compatibility matrix or intentional-difference explanation;
- roadmap status when a tracked item is completed.

## Documentation Standard

Public docs must describe implemented behavior, not planned behavior. Planned
features belong in [the roadmap](../todo.md) or in an explicitly labeled
planned section.

Use these labels where relevant:

- `SLS and SLS-LITE`
- `Adapted for local mode`
- `SLS-LITE only`
- `Full SLS only`
- `Planned`

Commands, permissions, fields, defaults, valid ranges, restart requirements,
security implications, and destructive behavior must be explicit.

## Compatibility Work

Pin the upstream SLS revision before comparing behavior. Record whether each
feature is supported, adapted, intentionally unsupported, or deferred. Do not
copy upstream code or fixtures without checking their license and attribution
requirements.

## Release Artifacts

A release candidate must be built from a reviewed source revision, pass the
complete automated and documented manual suites, contain required license and
third-party material, and publish a checksum. Snapshot artifacts are not
production releases.
