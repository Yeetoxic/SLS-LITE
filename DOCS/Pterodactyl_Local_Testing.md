# Local Pterodactyl Test Environment

This disposable development environment runs the Pterodactyl control plane and
game servers in Docker Desktop. Persistent Panel, Wings, database, and server
data remain in Ubuntu WSL2. It is a local integration fixture, not a production
deployment template.

## Access

- Panel: <http://localhost:8088>
- Velocity console: <http://localhost:8088/server/c165ae9c>
- Optional external lobby console: created by the lobby-mode helper.
- Velocity address: `127.0.0.1:25565`

Generated local credentials are stored in the ignored
`infra/pterodactyl/.env.local` file. The legacy WSL installer requires
`PTERODACTYL_DB_PASSWORD` and `PTERODACTYL_ADMIN_PASSWORD`; it has no fallback
passwords. Configure TLS and rotate every credential before exposing the Panel
outside the local machine.

Use the Panel or the repository's Panel helpers for game-server power actions.
Stopping a Wings-owned game container directly with Docker does not change the
allocation's desired power state, so Wings may recreate or restart it while a
filesystem test expects the allocation to remain stopped.

## Services

The fixture contains Panel, Wings, MariaDB, Redis, a `Local Wings` node, the
`SLS-LITE Velocity` allocation, and an optional separate
`SLS-LITE External Lobby` allocation. The browser-facing Wings API, signed file
routes, and console WebSocket are proxied through NGINX at `localhost:8088`.
Wings listens internally on port `8080`.

Docker Desktop must be running. Inspect the stack with:

```powershell
docker ps --filter "name=sls-ptero-"
```

## Storage Modes

The default allocation uses the repository's Windows-backed game-data bind and
normally reports `9p`. The native mode uses an ext4-backed Docker volume inside
WSL2:

```powershell
.\scripts\set-pterodactyl-storage-mode.ps1 -Mode native
.\scripts\set-pterodactyl-storage-mode.ps1 -Mode windows
```

Only one mode runs at a time. The first native switch copies the stopped
Windows snapshot; later switches do not synchronize automatically. Treat the
two snapshots as separate fixtures and stop the allocation before changing
them. Use native storage for meaningful filesystem/performance work and retain
the Windows snapshot for translated-filesystem regression coverage.

## Lobby Modes

Switch to the separately hosted Paper lobby with:

```powershell
.\scripts\set-pterodactyl-lobby-mode.ps1 -Mode external
```

The helper creates or reuses the allocation on port `25566`, registers it as
Velocity server `lobby`, selects `lobby.mode: external`, and restarts the
affected allocations through Wings. SLS-LITE must route to this server without
starting, stopping, copying, resetting, or otherwise owning it.

Restore the self-contained managed lobby with:

```powershell
.\scripts\set-pterodactyl-lobby-mode.ps1 -Mode managed
```

Managed mode stops the separate allocation, removes its static Velocity
registration, and restores the managed `lobby/lobby` blueprint. The helper uses
a disposable Alpine utility mounted only to the active game-data volume; it
does not install software into Wings or weaken the normal container profile.

### External-Lobby Workflow

Connect a client to `127.0.0.1:25565` after selecting external mode:

1. Confirm the first connection lands on `SLS-LITE External Lobby`.
2. Confirm `/sls info` identifies the lobby as external.
3. Join `minigame/biome_run` and confirm a managed Paper transfer.
4. Stop the managed instance and confirm the player returns to the external
   lobby.
5. Stop the external allocation and confirm new arrivals use SLS-Limbo instead
   of an arbitrary game backend.
6. Confirm proxy-level commands remain available in SLS-Limbo.
7. Restart the external lobby and confirm tracked SLS-Limbo players return
   after its health probe.
8. Queue a destination while on a healthy backend and confirm the player stays
   there until the destination is ready.

Start the external allocation again before continuing, or return to managed
mode.

### Managed-Lobby Workflow

Select managed mode, connect to `127.0.0.1:25565`, and exercise:

```text
/sls blueprint lobby
/sls blueprints minigame
/sls start minigame biome_run
/sls list
/sls status <instance-id>
/sls stop <instance-id>
```

The historical fixture's lobby, minigames, and adventures run inside the same
allocation as Velocity. The Panel memory limit covers Velocity and every child
process together. See [Velocity Testing](Velocity_Testing.md) for its software
matrix.

The repository helper bounds the fixture's Velocity heap at 512 MiB. Do not use
container-wide `MaxRAMPercentage` sizing for the proxy when managed child JVMs
share its allocation: that allows Velocity to claim memory reserved by
`resources.total_memory_mib`, SLS-Limbo, JVM native overhead, and the operating
system. Size the proxy heap and managed-memory admission budget separately and
leave measured native/RSS headroom below the Panel limit.

To test a disabled managed primary, set `lobby.auto_start: false` while keeping
SLS-Limbo enabled, restart Velocity, and confirm no managed lobby process is
resumed or recovered. Restore `true` and restart Velocity before continuing.

## Console Automation

`infra/pterodactyl/send-command.php` sends one console command to the local
Velocity allocation identified by external ID `sls-lite-local-velocity`. It is
not a production Panel extension or remote administration endpoint.

```powershell
docker cp infra/pterodactyl/send-command.php sls-ptero-panel:/tmp/sls-lite-send-command.php
docker exec -e PANEL_ROOT=/app sls-ptero-panel php /tmp/sls-lite-send-command.php sls info
```

The helper uses the Panel's internal Wings repository and embeds no credential.
Keep it available only to users who already have local Docker access.

## Repeatable Network Scenario

With managed lobby mode and `minigame/stage1_lifecycle` loaded, run:

```powershell
.\scripts\test-pterodactyl-matchmaking.ps1
.\scripts\test-pterodactyl-lobby-handoff.ps1 `
  -Versions 1.21.5,1.21.11
```

The matchmaking workflow uses bounded offline clients to cancel one queued
preparation, occupy both one-player instances, verify both transfers, list the
required registries, and require rejection when the pool is full. The clients
disconnect in a `finally` block. Wait for idle cleanup and confirm both
ephemeral instances stop.

The handoff workflow discovers the persistent managed lobby, forces its
protected restart through the Panel console helper, and requires each connected
client to observe Paper -> SLS-Limbo -> recovered Paper. Use a real client for
stable protocol versions the pinned Node dependency cannot encode. Never run
offline automation against a production online-mode network.

Complete the scenario by restarting the Velocity allocation and confirming:

- reconciliation preserves the persistent lobby;
- SLS-Limbo and the managed lobby become ready;
- no test client remains connected;
- no ephemeral instance, mount, helper, or child process remains.

## Storage And Resource Workflows

Run performance and kernel-lifecycle work only against disposable roots. Do not
add `CAP_SYS_ADMIN`, `/dev/fuse`, packages, or alternate filesystems to the
normal Wings allocation merely to obtain a faster result.

### Portable-Copy Benchmark

Compile tests and run `StoragePerformanceBenchmarkHarness` with an immutable
source, an existing empty disposable target root, a profile label, and a repeat
count. Optional preparation and cleanup p95 limits must be supplied together.
The harness verifies file count/logical bytes, removes every target, and reports
distributions, allocated bytes, peak harness RSS, and available process-I/O
counters. It does not drop host caches or measure durable-write latency.

Benchmark the exact provider path. Do not compare raw block-I/O counters across
Docker Desktop, loop devices, direct WSL2, or native hosts as if their caching
and accounting were equivalent.

### Reflink

Use an empty disposable XFS filesystem created with `reflink=1`. Run the
`ReflinkSelectionRealKernelHarness` and `ReflinkPreparerRealKernelHarness`,
verify shared physical extents, write isolation, replacement, deletion, and
cleanup. The normal ext4 fixture should continue to select another strategy
when its exact-path clone probe fails.

### Btrfs

Use an empty disposable Btrfs filesystem and run
`BtrfsSelectionRealKernelHarness` plus `BtrfsPreparerRealKernelHarness`. A
Pterodactyl volume used by an unprivileged child must allow safe subvolume
deletion (for example, `user_subvol_rm_allowed`). Verify snapshot selection,
shared extents, isolation, reset, deletion, and crash reconciliation.

### fuse-overlayfs

Run `FuseOverlayFsSelectionRealKernelHarness` and
`FuseOverlayFsPreparerRealKernelHarness` only where `fuse-overlayfs`,
`/dev/fuse`, and an actual unprivileged mount are available. Device/binary
presence alone is insufficient; the contained mount probe is authoritative.
An unsupported host must fail an explicit `fuse-overlay` request and fall back
under `auto`.

### Snapshot Helper

Test `sls-snapshot-helper-v1` with a data-directory-confined disposable helper.
Cover create, suspend/resume, replacement, rollback, deletion, timeout,
malformed response, and hard-restart reconciliation. A helper is never selected
automatically and must not receive paths outside the configured data root.

### Kernel OverlayFS

The normal fixture intentionally lacks mount privilege. Run OverlayFS harnesses
in a separate privileged container backed by disposable tmpfs:

```powershell
mvn test-compile

docker run --rm --privileged --user 0 `
  --tmpfs /mnt/sls-test:rw,size=128m `
  -v "${PWD}/target/classes:/opt/classes:ro" `
  -v "${PWD}/target/test-classes:/opt/test-classes:ro" `
  --entrypoint sh ghcr.io/pterodactyl/yolks:java_25 `
  -c 'mkdir /mnt/sls-test/root && java -cp /opt/classes:/opt/test-classes net.slimelabs.slslite.instance.storage.OverlayFsRealKernelHarness /mnt/sls-test/root'
```

Run `OverlayFsSelectionRealKernelHarness` similarly to exercise automatic
selection. `OverlayFsPreparerRealKernelHarness` also needs the cached SLF4J API
on its classpath and covers prepare, suspend/remount, reset, and delete.
Preserve a failed disposable root long enough to diagnose it, then remove it.

## Reprovisioning

The Docker migration and management scripts are described in
`infra/pterodactyl/README.md`. Recreate or repair the Velocity allocation with:

```powershell
docker cp scripts/create-pterodactyl-velocity-local.php sls-ptero-panel:/tmp/create-pterodactyl-velocity-local.php
docker exec -e PANEL_ROOT=/app sls-ptero-panel php /tmp/create-pterodactyl-velocity-local.php
```

Both server provisioning helpers are idempotent. They accept `--start`,
`--stop`, `--restart`, or `--kill` for infrastructure troubleshooting.
