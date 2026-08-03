# Stage 3 Retained-Branch Audit

This release record maps the first-release command, configuration, and runtime
branches to automated, real-kernel, and local Pterodactyl evidence. It does not
replace operator documentation. The audit closed on 2026-08-03 with no open
release-blocking result.

## Command Surface

The machine-readable `VSLSCommandContract` remains pinned to SLS `v0.2.0` at
commit `8e8b1e3cf7d2157887764c16f11b8901f8241121`. Its 55 distinct branches have
stable IDs, syntax, access, sender, selector, modifier, permission, and
completion declarations. Contract tests fail if a runtime or documented root
is missing, duplicated, or silently changes availability.

| Branch group | Retained branches | Verification | Result |
| --- | --- | --- | --- |
| Public inspection | `info.summary`, `list`, `find.player`, `version`, `registries` | Command/handler tests plus authenticated live player lookup and console sweep. | Pass |
| Blueprint inspection | `blueprint.id`, `blueprints` | Permission/completion tests, empty-catalog cases, exact corpus, and live lobby catalog output. | Pass |
| Creation and start | `create`, `start.type`, `start.blueprint` | Parser/permission/completion tests, safe override persistence, admission/limit tests, and Stage 3.8 multi-instance fixture. | Pass |
| Matchmaking and transfer | `join.type`, `join.player`, `join.player.force`, `dequeue.self`, `dequeue.selector` | Queue race, timeout, cancellation, capacity, pool selection, force join, disconnect, action-bar, and multi-client fixture coverage. | Pass |
| Backend diagnostics | `join-test.server`, `status.current`, `status.server`, `stats.current`, `stats.server`, `logs.server`, `console.server`, `console.follow` | Bounded concurrency/output tests and live status, stats, paging, 24 ms join probe, and one-line Paper console response. | Pass |
| Lifecycle mutation | `stop.current`, `stop.server`, `stop.all`, `kill.current`, `kill.server`, `kill.all`, `delete.server`, `delete.all` | Permission, protected-lobby, evacuation rollback, bulk ordering, active/persistent deletion, force-kill, concurrent terminal cleanup, and prior disposable live-fixture runs. | Pass |
| Persistent lifecycle | `restart.current`, `restart.server`, `reset.current`, `reset.server` | Same-ID/data restart, manager recreation, override persistence, definition drift, transactional reset/rollback, protected-lobby force cycles, and Panel restart recovery. | Pass |
| Installation | `install.info`, `install.logs`, `install.warmup`, `install.cleanup` | Handler bounds, concurrent install/cache integrity, EULA, retry/rollback, protected cleanup, and live info/logs/dry-run/warm-cache reuse. | Pass |
| Catalog reload | `reload.default`, `reload.mode` | Atomic commit/rejection tests and live blueprint, software, and combined no-drift reloads. Config reload retains its restart-only response. | Pass |
| Operations and compatibility | `system`, `maintenance`, `node`, `pause`, `resume`, `status.remote`, `debug` | Permission/sender tests and live capability, maintenance-status, player-only rejection, local-node/remote-status, and safe pause/resume boundary responses. | Pass |
| Built-in administration | `admin.claim`, `admin.code`, `admin.add`, `admin.remove`, `admin.list` | Secure/offline claim-service tests and handler-level one-time claim, persistence mutation, sender, permission, completion, and live list coverage. | Pass |

The live sweep used the production-style online-mode allocation and deployed
artifact SHA-256
`82D9EA53C8C7ED44BEDA640E2F98ED709785571E1587899539602E060041507C`.
It did not issue stop, kill, delete, reset, or restart commands against the
retained production lobby. Those destructive branches used isolated unit
fixtures and the earlier disposable/full-stack Stage 3 runs.

## Configuration Surface

`OperatorDocumentationContractTest` proves that every canonical leaf in the
generated default is represented in the configuration reference. Repository
tests load every non-default enum/mode and now reject an explicit invalid-value
matrix at the YAML boundary rather than relying only on record constructors.

| Domain | Accepted branches | Rejected/failure branches | Result |
| --- | --- | --- | --- |
| Resources and ports | Positive memory/process limits, bounded port range, derived process count. | Zero/negative values, ports outside `1024..65535`, descending range, processes exceeding port count. | Pass |
| Matchmaking/lifecycle | `first-available`, `random`, positive queue timeout, disabled or positive idle cleanup. | Unknown selection, zero timeout, negative idle delay. | Pass |
| Storage | `auto`, `copy`, `reflink`, `btrfs`, `overlay`, `fuse-overlay`, explicit `snapshot-hook`. | Unknown strategy, missing/escaping helper, timeout outside `1..300`, unavailable explicit strategy. | Pass |
| Managed output/detail log | Both console/file toggles, all detail levels, bounded sizes, retention, queue, and path redaction. | Invalid booleans/level and every documented lower/upper size, retention, and queue bound. | Pass |
| Forwarding/security | `none`, matching modern online mode, confined secret, secure claim policy and expiry. | Unknown mode, modern online-mode mismatch, escaping secret, non-positive claim expiry. | Pass |
| Lobby/SLS-Limbo | External/managed, managed auto-start on/off, native/fixed protocol, enabled/disabled recovery and bounded backoff. | Blank identity, unsafe disabled-primary combination, low memory/protocol, negative attempts, zero timing, inverted backoff. | Pass |
| Paths/schema | Confined relative instance path, complete known nested structure, deprecated emergency alias migration. | Oversize file, symlink/non-file, traversal, malformed object, unknown key with suggestion, conflicting old/new keys. | Pass |

Blueprint and software repositories separately cover byte limits, symlink and
traversal rejection, unknown fields, inheritance/cycles, exact Java/version
mapping, volume/copy overlap, environment safety, provider metadata, and the
54-definition modern corpus plus six pinned upstream examples.

## Runtime And Failure Matrix

| Area | Success branches | Expected failure/recovery branches | Result |
| --- | --- | --- | --- |
| Installation | Exact Paper/vanilla downloads, checksums, concurrent reuse, manual cache, atomic publication. | Host allowlist rejection, EULA block, failed retry, tamper rehash, incomplete replacement rollback, shutdown cancellation. | Pass |
| Preparation/storage | Portable sparse copy, reflink, Btrfs, OverlayFS, fuse-overlayfs, snapshot helper, `ro`/`rw`/`cow`, copy merge. | Missing source, traversal/symlink, target collision/overlap, unavailable explicit COW, auto fallback, cancellation and rollback. | Pass |
| Process/lifecycle | Ready detection, environment, console input, graceful stop, force kill, persistent restart/reset/delete. | Readiness timeout, crash-before-ready, duplicate ID, startup cancellation, stop timeout, concurrent terminal operation, deadline overrun metadata. | Pass |
| Lobby/routing | External, managed, disabled managed auto-start, SLS-Limbo, healthy-primary preference, handoff, recovery. | Unhealthy primary, unavailable Limbo, bounded restart exhaustion, intentional-stop rollback, failed handoff rate limit. | Pass |
| Queue/transfer | Ready reuse, cold provisioning, first/random pools, capacity scale-out, direct/forced join, multi-registry routing. | Duplicate queue, timeout, disconnect, dequeue/transfer race, readiness failure, full pool, last-waiter cancellation. | Pass |
| Reconciliation/shutdown | Persistent preservation, stale ephemeral removal, reset/delete transaction recovery, clean Panel restart. | Malformed/unknown metadata preservation, unverifiable live PID preservation, forced shutdown deadline with later reconciliation. | Pass |
| Observability | Concise console, bounded detail/child logs, status/stats/system, correlation/timing, unavailable-metric labels. | Queue/log overflow remains bounded; sensitive paths/credentials are redacted; diagnostic failure cannot block lifecycle work. | Pass |

The final clean verification after closing the uncovered administrator and
configuration-boundary tests ran 617 tests with zero failures or errors and
eight environment-dependent skips. The Windows symlink test skipped because
that filesystem denied link creation; its equivalent unprivileged Linux
real-kernel harness passed. Dependency analysis, Spotless, and SpotBugs passed.
The normal Pterodactyl security profile remained unchanged, the connected
authenticated player stayed on `lobby.b5kk8m`, and Velocity, SLS-Limbo, Paper,
Wings, Panel, database, and cache remained healthy.
