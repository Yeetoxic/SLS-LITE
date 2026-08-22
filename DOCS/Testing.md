# Testing

[Documentation home](README.md)

Testing is split between deterministic Maven tests, protocol clients, a
synthetic Velocity fixture, and the local Pterodactyl historical-world network.

## Automated Suite

```powershell
mvn clean verify
```

Run this release's build with JDK 25 because the pinned Velocity API itself
contains Java 25 class files. Maven still compiles SLS-LITE with `--release 21`.
The command runs the JUnit suite, builds the shaded JAR, and
packages license material. It also checks the pinned Google Java Format output
and fails on dependency declaration drift or high-priority SpotBugs findings.
Tests cover parsing, validation,
lifecycle, concurrency, resource accounting, installation, reconciliation,
commands, lobbies, SLS-Limbo, protocol integration boundaries, and Velocity
registration.

Use `clean` for every artifact that may be distributed or checksummed. The
shading phase transforms packaging output; rerunning `package`, `verify`, or
`install` against an already shaded `target/` directory is suitable only for
local iteration and must not define a release checksum.

Documentation tests verify that the command/permission and configuration
references match runtime contracts, every top-level guide returns to the
canonical index, local Markdown targets resolve, required wiki pages and
sidebar entries exist, and wiki links point to repository content that exists.

Public API coverage includes reflection checks that reject implementation-type
leaks, deep-immutability checks, lifecycle-observer failure isolation, bounded
subscriber behavior, globally ordered lifecycle/matchmaking delivery,
queue/transfer terminal ownership, and API shutdown state. Packaging
must also produce `target/sls-lite-<version>-api.jar`; inspect it when changing
the classifier rules to confirm that `api.internal` and all other product
packages are absent while `META-INF/licenses/LICENSE` is present.

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

GitHub Actions runs the same clean JDK 25 verification for every push and pull
request, retains the shaded plugin and API artifacts for 14 days, and reviews pull
request dependency changes for moderate-or-higher known vulnerabilities. All
third-party actions are pinned to exact commits; update those pins only after
reviewing the corresponding upstream release.

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

For an end-to-end managed-lobby recovery handoff on the disposable local
Pterodactyl fixture, run:

```powershell
.\scripts\test-pterodactyl-lobby-handoff.ps1 `
  -Versions 1.21.5,1.21.11
```

The connected clients must observe the managed Paper lobby, SLS-Limbo during a
forced protected-lobby restart, and the recovered Paper lobby in that order.

The complementary bounded matchmaking scenario is:

```powershell
.\scripts\test-pterodactyl-matchmaking.ps1
```

It requires the local `minigame/stage1_lifecycle` fixture and verifies queue
cancellation, two real backend transfers, multiple-registry visibility, and
full matchmaking-pool rejection.

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

Update a local upstream checkout to its `main` branch, then run the opt-in
modern SLS corpus harness against its blueprint directory:

```powershell
mvn "-Dtest=BlueprintCorpusCompatibilityIT" `
  "-Dsls.compatibility.blueprints=.local-fixtures/upstream-sls-main/blueprints" test
```

This validates schema compatibility without requiring referenced world or
plugin volume sources. It does not claim that every blueprint is launch-ready;
runtime content, software, Java, memory, and unsupported volume modes remain
separate gates. Review differences before refreshing repository-owned fixtures;
ordinary builds must not download or depend directly on the moving branch.

Run each example from a reviewed SLS `main` checkout independently:

```powershell
mvn "-Dtest=BlueprintExamplesCompatibilityIT" `
  "-Dsls.compatibility.examples=.local-fixtures/upstream-sls-main/examples" test
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
| Documentation/wiki | Canonical-page update, local-link/navigation contract, availability-claim review. |

Manual evidence should record the artifact hash, runtime versions, configuration
relevant to the result, exact command sequence, and both player-facing and
server-log observations.
