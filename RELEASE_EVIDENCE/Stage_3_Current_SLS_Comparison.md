# Stage 3 Current SLS Comparison

This is the dated acceptance record for the complete-project compatibility
review performed on 2026-08-03. Current product behavior is defined by the
operator documentation under `DOCS/`, not by this record.

## Source Pin

- Repository: `https://github.com/jessefaler/SLS`
- Branch: `main`
- Commit: `8e8b1e3cf7d2157887764c16f11b8901f8241121`
- Commit date: 2026-07-07T20:10:51-05:00
- Commit subject: `Bump version from 1.0.2 to 1.1.0 in pom`
- Containing release tag: `v0.2.0`

At audit time, `origin/main`, local `main`, and `v0.2.0` all resolved to the
same commit. The previously pinned definition/command corpus was therefore not
an older approximation of current upstream; it was the exact current tree.

## Reviewed Surface

The review read the source rather than relying only on the top-level README or
vSLS documentation. The checkout contained 93 Protocube files, 119 daemon
files, 85 vSLS files, 22 SlimePacks files, three bundled software-definition
files, and six blueprint examples. The audit covered:

- Protocube blueprint/software models, parsers, registries, server manager,
  node manager, allocator/load balancer, SQLite repository, API routes,
  authorization middleware, event streams, and native plugin interface;
- daemon server lifecycle, Docker environment, installation/warmup, OverlayFS,
  mounts/copy, config patching, console/log/status/stats endpoints, crash
  reporting, reconciliation/sync, filesystem/archive operations, and token
  authentication;
- the complete vSLS command tree, permission checks, server registration,
  lifecycle manager, matchmaking, transfer behavior, annotations, messages,
  event routing, and ViaVersion integration;
- bundled blueprint examples, Paper/Spigot software definitions, installation
  scripts, resource limits, image mappings, update metadata, and SlimePacks.

## Classification Results

| Area | Compatible/adapted | Outside/deferred |
| --- | --- | --- |
| Blueprint schema | Metadata, types, software/version, limits, save, volumes, copy, environment, configs, annotations | Arbitrary host mounts are rejected. Container limits remain metadata where a child JVM cannot enforce them. |
| Software schema | Identity, mappings, Java invocation, readiness, stop, limits/defaults, warmup intent, Paper/vanilla/manual profiles | Arbitrary installer containers/scripts and remote definition replacement are not executed. |
| Commands | Every vSLS root is retained with useful local behavior or a stable local-mode response; local extensions are additive | Node and portable process-suspension operations do not simulate distributed/container behavior. |
| Configuration | Shared definition shapes plus explicit single-host configuration | Protocube/daemon topology, TLS/CORS, Docker, database, node, and host-mount settings are not imported. |
| API/events | Internal lifecycle services cover product operation | Protocube HTTP/SSE/WebSocket APIs, API scopes, and S4J compatibility are absent; the later Java API closure described below supplies a local extension contract without a network listener. |
| Lifecycle/matchmaking | Start/readiness/stop/kill/delete/reset/restart, persistence, crash recovery, queues, capacities, selection, transfer, and empty cleanup | Horizontal placement and cross-node recovery are outside one-host scope. |
| Storage | COW/RO/RW/copy intent, reflink, Btrfs, OverlayFS, fuse-overlayfs, helper snapshots, portable fallback, reconciliation | Arbitrary daemon host mounts and container overlay propagation are outside scope. |
| Installation | Exact provider artifacts, EULA, atomic caches, manual bases, warmup, retries, cancellation, cleanup | General shell installation and container image assembly are intentionally not supported. |
| Networking/nodes | Loopback allocation, Velocity registration, forwarding validation, managed/external lobby, SLS-Limbo | Docker bridges, public daemon allocation ranges, heartbeats, draining, load balancing, and horizontal nodes are outside scope. |
| Persistence | Durable instance metadata, administrator store, storage manifests, persistent remount/recovery | Protocube/daemon controller databases and distributed records are replaced, not emulated. |
| Observability | Status/stats/logs/console, bounded output, rotating detail logs, timings, correlation IDs, host capability reporting | No remote metrics/event service is exposed. |
| Security | Velocity permissions, administrator claims, loopback binding, path/symlink confinement, bounded inputs, redaction | External TLS/CORS/bearer-token surfaces do not exist because there is no public listener. |
| Operations/extensions | Maintenance, clean shutdown, recovery, software cleanup, resource admission, annotations retained for integrations | Native Protocube Go plugins, SlimePacks conversion/hosting, telemetry, and non-Minecraft game orchestration are outside the first release. |

## Findings And Corrections

No release-blocking runtime gap was found inside SLS-LITE's documented
single-host boundary. All fields used by the six exact upstream examples were
already covered by the current compatibility gate, and the command and storage
surfaces had been completed during Stage 3.

One release-facing documentation defect was found and corrected: the pinned
field matrix still described `rw` volumes, `create`, `delete`, `kill`,
`blueprint`, `debug`, and true filesystem COW as unavailable or future work.
Those statements contradicted the implemented and tested product. The matrix
now describes verified shared-directory links, the completed command surface,
all native COW strategies, and explicit rejection of unsafe `create`
modifiers. `DOCS/compatibility/README.md` now contains the whole-project map instead
of limiting its comparison to vSLS and shared YAML.

No feature was added merely to imitate distributed SLS. In particular, a
network API, Docker/container enforcement, nodes, native Go plugins,
SlimePacks, and arbitrary host mounts remain explicit scope decisions. Adding
them would materially change the product and its threat model rather than fix
a SLS-LITE defect.

## Acceptance Conclusion

The current full-SLS comparison produced no unresolved Stage 3 release
blocker. SLS-LITE is compatible with the documented shared definition and
operator-intent subset, locally adapts lifecycle and storage to one Velocity
allocation, and clearly excludes distributed/container-only behavior. It must
not be marketed as a drop-in Protocube/daemon, S4J endpoint, or general game
orchestrator.

## Post-Audit Java API Closure

Immediately after this comparison, the project owner promoted the local Java
extension API from deferred candidate to release requirement. API version 1.0
now provides capability discovery, immutable blueprint/instance views,
asynchronous start/stop/delete and matchmaking requests, queue inspection and
cancellation, and bounded ordered lifecycle subscriptions. A classifier JAR
contains only `net.slimelabs.slslite.api`, its event package, Maven metadata,
and the project license; it excludes `api.internal` and every other
implementation package. The authenticated HTTP/event API remains deferred.

## Verification

- The exact current-upstream example gate loaded all six YAML files with no
  unexpected rejection.
- `mvn -B --no-transfer-progress clean verify` passed 617 tests with zero
  failures, zero errors, and eight host-dependent skips. Dependency analysis,
  Spotless, and SpotBugs passed.
- `git diff --check` passed.
- The local Pterodactyl stack remained healthy. The allocation contained only
  the expected bounded-heap Velocity, SLS-Limbo, and persistent Paper 26.2
  lobby JVMs, and its recent log window contained no SLS-LITE warning, error,
  or exception.
