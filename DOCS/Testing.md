# Testing

Testing is split between deterministic Maven tests, protocol clients, a
synthetic Velocity fixture, and the local Pterodactyl historical-world network.

## Automated Suite

```powershell
mvn verify
```

This compiles Java 21 bytecode, runs the JUnit suite, builds the shaded JAR, and
packages license material. It also checks the pinned Google Java Format output
and fails on high-priority SpotBugs findings. Tests cover parsing, validation,
lifecycle, concurrency, resource accounting, installation, reconciliation,
commands, lobbies, SLS-Limbo, protocol integration boundaries, and Velocity
registration.

During development:

```powershell
mvn spotless:apply
mvn spotless:check
mvn compile spotbugs:check
```

The formatter covers production and test Java sources. SpotBugs analyzes
production bytecode at maximum effort; its build gate rejects high-priority
findings. Review lower-priority findings when changing the affected code rather
than establishing an unreviewed suppression baseline.

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

## Modern Blueprint Corpus

Run the opt-in modern SLS corpus harness against a copied blueprint directory:

```powershell
mvn "-Dtest=BlueprintCorpusCompatibilityIT" `
  "-Dsls.compatibility.blueprints=path/to/blueprints" test
```

This validates schema compatibility without requiring referenced world or
plugin volume sources. It does not claim that every blueprint is launch-ready;
runtime content, software, Java, memory, and unsupported volume modes remain
separate gates.

Run each example from the pinned SLS checkout independently:

```powershell
mvn "-Dtest=BlueprintExamplesCompatibilityIT" `
  "-Dsls.compatibility.examples=.local-fixtures/upstream-sls-v0.2.0/examples" test
```

Keep source checkouts under `.local-fixtures/`, not Maven's `target/` build
directory. Git pack files may be read-only on Windows and prevent `mvn clean`
from removing `target/`.

If a deliberately unsupported example is added later, use the exact
`sls.compatibility.expectedRejected` filename list. The test fails if a new
example is rejected or if a listed gap starts loading without updating that
list.

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
