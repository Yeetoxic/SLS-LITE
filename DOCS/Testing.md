# Testing

Testing is split between deterministic Maven tests, protocol clients, a
synthetic Velocity fixture, and the local Pterodactyl historical-world network.

## Automated Suite

```powershell
mvn verify
```

This compiles Java 21 bytecode, runs the JUnit suite, builds the shaded JAR, and
packages license material. Tests cover parsing, validation, lifecycle,
concurrency, resource accounting, installation, reconciliation, commands,
lobbies, SLS-Limbo, protocol integration boundaries, and Velocity registration.

For a clean dependency-resolution check:

```powershell
mvn -U clean verify
```

That command requires Maven repository network access.

## Protocol Tests

Native bundled SLS-Limbo matrix:

```powershell
.\scripts\test-sls-limbo-native-protocols.ps1
```

Proxy-routed smoke clients:

```powershell
.\scripts\test-sls-limbo-protocols.ps1 `
  -Versions 1.21.5,1.21.11 `
  -ExpectedBrand SLS-Limbo
```

See [Protocol Compatibility](Protocol_Compatibility.md) for the acceptance
definition and recorded matrix. Test clients use offline authentication and
must never target a production online network.

## Synthetic Fixture

```powershell
.\scripts\setup-velocity-test.ps1
```

This creates an isolated development fixture under `test-server/`. It is useful
for narrow lifecycle work but is not the primary historical-world regression
network.

## Historical-World Fixture

The primary integration network runs in local Pterodactyl and includes the
preserved SLS v2.1.2 lobby, minigames, and adventure content with exact Paper
versions. Use:

- [Local Pterodactyl Testing](Pterodactyl_Local_Testing.md) for infrastructure.
- [Velocity Testing](Velocity_Testing.md) for commands and scenarios.

Imported worlds, generated server files, local credentials, and Pterodactyl
state are intentionally ignored by Git.

## Required Change Coverage

| Change area | Minimum verification |
| --- | --- |
| YAML/config | Parser tests, invalid values, unknown keys, generated example review. |
| Commands | Permissions, sender types, usage, execution, and tab completion. |
| Lifecycle | Success, failure, cancellation, concurrent operation, and cleanup. |
| Filesystem | Traversal/symlink rejection, transaction rollback, immutable source. |
| Installation | Exact version, integrity, cache reuse, failure retry, cancellation. |
| Lobby/routing | Primary success/failure, SLS-Limbo fallback, no reconnect loop, handoff. |
| Protocol | Full PLAY-state client test, not only ping or server startup. |
| Packaging | Shaded JAR contents, licenses, bundled runtime checksum. |

Manual evidence should record the artifact hash, runtime versions, configuration
relevant to the result, exact command sequence, and both player-facing and
server-log observations.
