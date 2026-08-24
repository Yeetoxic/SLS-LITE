# Troubleshooting

[Documentation home](../README.md)

Diagnose failures from the narrowest safe surface before changing data or host
security. Never delete instance directories, transaction backups, ownership
metadata, storage manifests, or mounts while Velocity is running.

You usually need only the first checks and the matching symptom below. Follow
the linked reference only if the short remedy does not resolve it.

## First Checks

1. Run `/sls system` from the console.
2. Run `/sls info [server]` and `/sls status <server>` when an instance exists.
3. Inspect `/sls logs <server>` or `/sls install logs <software> <version>`.
4. Use the correlation ID from a concise console failure to find the matching
   entry in `plugins/sls-lite/logs/sls-lite-detail.log`.
5. Confirm the loaded configuration, blueprint, software profile, Java runtime,
   filesystem path, and artifact version before retrying.

<details>
<summary>How retained process output is shortened</summary>

Retained process-failure reports contain bounded output context. When more than
200 retained lines exist, SLS-LITE records the first and last 100 with an
omission marker between them. This preserves both an early watchdog or exception
header and the final shutdown result without making report storage unbounded.

</details>

## Common Failures

### Managed initialization failed

Inspect startup capability failures. Check configured Java paths, writable
storage, loopback ports, real and declared memory/process budgets, forwarding,
and the selected lobby mode. An explicitly requested unavailable storage
strategy fails startup; `auto` may safely select portable copy.

### Insufficient managed memory

Stop unused instances, reduce safe blueprint reservations, or increase both the
real panel allocation and SLS-LITE admission budget. Do not raise only the
declared budget beyond the real host limit.

Blueprint memory and `resources.total_memory_mib` account for child maximum
heaps; they do not measure total resident memory. Velocity, SLS-Limbo, JVM
native memory, thread stacks, mapped files, and the operating environment need
additional headroom. Use `/sls stats` and host/container metrics on
representative loaded instances.

### Server remains in `STARTING`

Inspect the child log for first-run Paper work, EULA rejection, a missing or
wrong Java runtime, plugin failures, port conflicts, or a readiness pattern
that does not match the selected software output.

### Player remains queued

Inspect the destination's startup, ready-instance capacity, queued-slot count,
blueprint maximum instances, host admission, and queue timeout. A queued player
normally remains on their current healthy backend.

### Player remains in SLS-Limbo

Verify that the primary lobby is ready or responds to its external health
probe, forwarding agrees with Velocity, the client/backend protocols are in the
supported matrix, and ViaVersion mappings are present when required. Check both
primary-lobby and SLS-Limbo recovery budgets.

### Persistent restart is rejected

Definition fingerprint drift protects the existing instance from silently
changing its software or blueprint contract. Review the reported change. Use
normal restart only for the original definition; back up the persistent data
and use reset only when reconstruction from clean sources is intended.

### Explicit COW strategy is unavailable

Review `/sls system` for the exact instance-path probe. Reflink, Btrfs,
OverlayFS, fuse-overlayfs, and snapshot helpers each have distinct filesystem,
privilege, source-shape, and lifecycle requirements. Do not weaken a hosting
provider's normal security profile to make a strategy pass. Use `auto` or
`copy` when the required capability is unavailable.

### World or plugin access is slow

Docker Desktop and Windows-backed bind mounts heavily penalize copies and
region-file access. Prefer native Linux storage for instances, blueprint
sources, and software caches when the provider exposes it. Benchmark the exact
production path before changing capacity or timeout values.

<details>
<summary>Contributor storage benchmark details</summary>

For repeatable samples, compile test classes and run
`StoragePerformanceBenchmarkHarness` with an immutable source, an existing
empty disposable target root, a profile label, and optional repeat/p95 limits.
The harness verifies content and cleanup but does not drop host caches or claim
durable-write latency.

</details>

### Resource pack does not load

Confirm `resource-pack` is a client-reachable HTTP(S) URL and that its SHA-1
matches the exact served ZIP. A path inside the Velocity/Pterodactyl container
cannot be downloaded by the player's client. See [Resource Packs](../blueprints/Resource_Packs.md).

## Reporting A Problem

Include:

- SLS-LITE artifact version and checksum;
- Velocity, Java, Minecraft software, and optional ViaVersion versions;
- operating system/container and relevant storage profile;
- the sanitized configuration/blueprint portion;
- exact commands and expected/observed result;
- correlation IDs and the smallest relevant redacted log excerpt.

Do not include forwarding secrets, administrator claim codes, authentication
tokens, private player data, or full unrelated logs.
