# Local Pterodactyl Test Environment

This environment runs the Pterodactyl control plane and game servers in Docker
Desktop. Persistent Panel, Wings, database, and server data remains in Ubuntu
WSL2.

## Access

- Panel: <http://localhost:8088>
- Velocity console: <http://localhost:8088/server/c165ae9c>
- Optional external lobby console: created by the lobby-mode helper when needed.
- Velocity address: `127.0.0.1:25565`

The Docker environment stores generated local credentials in the ignored
`infra/pterodactyl/.env.local` file. The legacy WSL installer requires
`PTERODACTYL_DB_PASSWORD` and `PTERODACTYL_ADMIN_PASSWORD` to be set explicitly;
it has no fallback passwords. Configure TLS and rotate all credentials before
exposing the Panel to a LAN or the internet.

## Installed Services

- Pterodactyl Panel 1.14.1
- Pterodactyl Wings 1.13.1
- MariaDB 11.4 and Redis 7
- One node named `Local Wings`
- One server named `SLS-LITE Velocity`
- An optional separately allocated Paper server named `SLS-LITE External Lobby`
- Eclipse Temurin Java 25 container images
- Velocity with the current SLS-LITE build

The browser-facing Wings API, signed file routes, and console WebSocket are
proxied through NGINX at `localhost:8088`. Wings listens internally on port
`8080`.

Docker Desktop must be running. Check the stack from PowerShell with:

```powershell
docker ps --filter "name=sls-ptero-"
```

### Storage Modes

The default allocation uses the repository's Windows-backed game-data bind and
is expected to report `9p`. A reversible WSL2-native mode stores the same
allocations in Docker Desktop's ext4-backed external volume:

```powershell
.\scripts\set-pterodactyl-storage-mode.ps1 -Mode native
.\scripts\set-pterodactyl-storage-mode.ps1 -Mode windows
```

Only one mode runs at a time. The first native switch copies the stopped
Windows snapshot; later changes do not synchronize automatically in either
direction. Keep the Windows snapshot as the portable-fallback regression
fixture and use the native snapshot for Linux filesystem measurements.

## Lobby Modes

Switch to the separately hosted Paper lobby:

```powershell
.\scripts\set-pterodactyl-lobby-mode.ps1 -Mode external
```

This command creates or reuses the Paper allocation on port `25566`, registers
it as Velocity server `lobby`, changes SLS-LITE to `lobby.mode: external`, and
restarts the affected servers through Wings.

Restore the self-contained managed Paper lobby:

```powershell
.\scripts\set-pterodactyl-lobby-mode.ps1 -Mode managed
```

Managed mode stops the separate Paper allocation, removes the static lobby
registration, and restores SLS-LITE's managed `lobby/lobby` blueprint.

## Manual External-Lobby Test

Switch to external mode first. Connect a Minecraft client to
`127.0.0.1:25565` and verify:

1. The first connection lands on `SLS-LITE External Lobby`.
2. `/sls info` reports the lobby as external.
3. `/sls join minigame biome_run` starts and transfers you to a managed Paper
   server.
4. `/sls stop <instance-id>` returns you to the external lobby.
5. Stopping or kicking from a managed backend redirects you to the external
   lobby without a reconnect loop.
6. Stopping the external lobby redirects new arrivals to SLS-Limbo rather than
   selecting an arbitrary game backend.
7. `/sls info` and authorized proxy-level commands remain available there.
8. Restarting the external lobby automatically moves tracked SLS-Limbo players
   back after its health probe succeeds.
9. A normal `/sls join` queue from a healthy backend leaves the player on that
   backend until the requested destination is ready.

After the test, start the external lobby again from its Panel console before
continuing, or switch back to managed mode with the command above.

The fallback's architecture, configuration, limitations, and full manual test
are documented in [SLS_Limbo.md](SLS_Limbo.md).

## Manual Managed-Lobby Test

Switch to managed mode, connect to `127.0.0.1:25565`, and verify:

```text
/sls blueprint lobby
/sls blueprints minigame
/sls start minigame biome_run
/sls list
/sls status <instance-id>
/sls stop <instance-id>
```

The historical SLS v2.1.2 network is the primary local fixture. Its lobby,
minigames, and adventure launch inside the same Pterodactyl allocation as
Velocity. The 6 GiB Pterodactyl memory limit applies to Velocity and all child
processes together. See [Velocity_Testing.md](Velocity_Testing.md) for the
version matrix and complete run.

If the Panel loads but its console reports a connection problem, hard-refresh
the page after confirming that `sls-ptero-panel` and `sls-ptero-wings` are
running.

## Console Automation

`infra/pterodactyl/send-command.php` sends one console command to the local
Velocity allocation identified by external ID `sls-lite-local-velocity`. It is
for repeatable local integration tests; it is not a production Panel extension
or a remote administration endpoint.

Copy it into the Panel container, then pass the console command as arguments:

```powershell
docker cp infra/pterodactyl/send-command.php sls-ptero-panel:/tmp/sls-lite-send-command.php
docker exec -e PANEL_ROOT=/app sls-ptero-panel php /tmp/sls-lite-send-command.php sls info
```

The helper uses the Panel's existing internal Wings repository and contains no
credentials. Keep it available only to users who already have local Docker
access.

## Stage 3 Performance Baseline

The initial Stage 3 timing run on 2026-07-29 used snapshot JAR SHA-256
`7F15D7ED51F53912059A698F2694B1E60EE3F86AB1BF56E93BB326C0C961FE58`.
The allocation reported Windows-backed `9p` storage (`C:\`), atomic directory
moves, no usable reflink clone, an available OverlayFS kernel driver without
`CAP_SYS_ADMIN`, and selected `portable-copy`.

The persistent Paper 1.18.2 lobby resumed without copying its retained world:

| Phase | Time |
| --- | ---: |
| Dispatch | 0.759 ms |
| Software resolution | 2,760.204 ms |
| File preparation | 3.175 ms |
| Configuration | 173.377 ms |
| Child launch | 8.991 ms |
| Readiness | 137,782.973 ms |
| Registration | 18.250 ms |
| Total | 140,759.717 ms |

The ephemeral `stage2_undead` Paper 1.18.2 fixture performed a fresh portable
world copy and completed as follows:

| Phase | Time |
| --- | ---: |
| Dispatch | 0.921 ms |
| Software resolution | 2,611.144 ms |
| File preparation | 22,135.621 ms |
| Configuration | 65.862 ms |
| Child launch | 4.848 ms |
| Readiness | 86,291.399 ms |
| Registration | 18.864 ms |
| Total | 111,139.295 ms |

Its stop exceeded the configured 30-second graceful-shutdown window and exited
137 after forced termination. Shutdown measured 30,347.964 ms and cleanup
measured 5,526.713 ms. The ephemeral directory was removed, reservations were
released, the managed lobby and SLS-Limbo remained ready, and no capability
probe directories remained. These are observational baselines, not release
thresholds; native-Linux measurements are still required.

### WSL2 Ext4 Baseline

The same allocation, JAR, persistent lobby, software cache, and copied fixture
were switched to external Docker volume `sls-ptero-native-game-data`. The game
container reported ext4 on `/dev/sdd`; atomic moves remained available,
reflinks remained unavailable, and OverlayFS remained blocked by the
unprivileged game container.

The persistent Paper 1.18.2 lobby resumed in 13,083.627 ms:

| Phase | Time |
| --- | ---: |
| Dispatch | 1.062 ms |
| Software resolution | 51.122 ms |
| File preparation | 0.082 ms |
| Configuration | 47.244 ms |
| Child launch | 5.250 ms |
| Readiness | 12,972.625 ms |
| Registration | 4.699 ms |

The fresh ephemeral `stage2_undead` run completed in 9,989.053 ms:

| Phase | Time |
| --- | ---: |
| Dispatch | 0.630 ms |
| Software resolution | 34.346 ms |
| File preparation | 409.438 ms |
| Configuration | 4.406 ms |
| Child launch | 2.636 ms |
| Readiness | 9,535.039 ms |
| Registration | 1.854 ms |

Its graceful shutdown completed with exit code 0 in 2,570.468 ms and cleanup
took 56.140 ms. The instance directory and capability probes were absent after
cleanup. Compared with the matching `9p` run, total provisioning was about
11.1 times faster and portable world preparation was about 54 times faster.
This WSL2 ext4 result is the native-Linux development baseline, not a
bare-metal release threshold.

The first Stage 3.3 restart-timing smoke test on 2026-07-30 used the exact
packaged build after the timing instrumentation was added. The normal fixture
selected `portable-copy`, reconciliation preserved one persistent instance
with zero failures, and the primary lobby became ready after
`14,199.508 ms`. This value measures from SLS-LITE proxy initialization until
primary-lobby readiness; it is an observational restart sample, not a
threshold. Queue, transfer, and first-player timings require a real player
connection and remain covered deterministically in the Maven suite until the
interactive sampling pass.

The Stage 3.5 force-kill gate passed on 2026-07-31 with JAR SHA-256
`987556DDC0564801751CF1E35C84F2B276B84C82106AC40BAC7861140EBC1AFD`.
On the normal unprivileged Pterodactyl/Velocity fixture,
`kill <persistent-id> force` evacuated first, exited immediately with code 137,
reported the pinned gray `Killed <id>` result, retained the instance directory,
and wrote `state=STOPPED`. The disposable directory was then removed through
the normal transactional delete command. A separate ephemeral server passed
unforced `kill all`: its process and directory were removed, the managed lobby
was reported as protected and skipped, and Velocity, NanoLimbo, and the lobby
JVM remained healthy.

The Stage 3.5 debug-command gate passed on 2026-07-31 with JAR SHA-256
`363098E8498319EF742F7E9E18FFCDACC2F9D84A1563FA80AE074DD33EFA7514`.
The console received the pinned player-only rejection. A disposable
Minecraft-protocol 1.18.2 client then connected through Velocity with a
temporary `sls.command.debug` LuckPerms grant and received both exact gray
`Debug mode enabled.` and `Debug mode disabled.` responses. The permission was
removed immediately after the run. The reusable
`tools/protocol-smoke/command-smoke.js` helper sends only explicitly supplied
test commands, uses offline authentication, and is not packaged with the
plugin.

The Stage 3.5 retained-modifier gate passed on 2026-07-31 with JAR SHA-256
`5A2B94FCD7CD00A7D898E3E4BB904A44345728A242FE8B79380E63EBD1F4880B`.
On the normal unprivileged fixture, `reload config` directed the operator to
restart Velocity, `status lobby.97f1ae remote` identified supervised local
process state as authoritative, and `create lobby lobby --cpu=1` rejected the
known daemon/container-only modifier before allocation. A disposable
`stage1_lifecycle.3bvmsk` server then reached readiness and accepted the pinned
non-dashed `stop ... force` form. It stopped gracefully with exit code zero,
released its ephemeral directory, and left only Velocity, NanoLimbo, and the
managed lobby JVM running. The panel remained healthy.

### Representative Storage and Resource Samples

The repeated 2026-07-30 portable-copy pass used unchanged fixture worlds and
the production four-worker preparer. Small and medium profiles ran five times;
the large profile ran three times. The Windows samples used the preserved
Docker Desktop `9p` snapshot, while the native samples used separate ext4
source and disposable target volumes.

| Profile | Files | Logical size | `9p` prepare median / p95 | ext4 prepare median / p95 | `9p` cleanup median / p95 | ext4 cleanup median / p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Small `blastoff_the_end` | 15 | 2,600,463 B | 148.289 / 198.836 ms | 7.993 / 38.462 ms | 199.082 / 207.404 ms | 3.351 / 6.968 ms |
| Medium `undead` | 247 | 69,865,556 B | 2,826.839 / 3,456.244 ms | 61.866 / 235.200 ms | 2,475.681 / 2,495.240 ms | 17.444 / 24.090 ms |
| Large `lobby` | 1,330 | 1,335,887,002 B | 22,063.596 / 27,681.142 ms | 1,011.649 / 3,045.207 ms | 11,051.024 / 11,105.902 ms | 1,095.762 / 1,199.287 ms |

All copied trees matched source file count and logical bytes and were deleted.
Allocated size was approximately logical size on both profiles. Windows `9p`
reported logical writes through `wchar` but zero kernel `write_bytes`, so its
block-I/O counters are explicitly unreliable. Native ext4 reported about the
target allocation in `write_bytes`; cached later runs frequently reported zero
physical reads. The large ext4 preparation range also reflects page cache and
asynchronous writeback. These are readiness-impact distributions, not
fsync/durable-write benchmarks or release thresholds.

For repeat runs on this exact fixture and unchanged sources, the initial
alert-only p95 envelopes are:

| Profile | `9p` prepare / cleanup | ext4 prepare / cleanup |
| --- | ---: | ---: |
| Small | 300 / 350 ms | 100 / 50 ms |
| Medium | 5,000 / 4,000 ms | 500 / 100 ms |
| Large | 35,000 / 15,000 ms | 5,000 / 2,500 ms |

Pass the matching pair after the repeat count to
`StoragePerformanceBenchmarkHarness`; it exits unsuccessfully after complete
target cleanup when either p95 exceeds its envelope. A failure is a signal to
repeat on the same storage profile and inspect host load, not a cross-provider
release failure. Small and medium use five repeats and large uses three.

The normal ready-but-empty fixture (Velocity, SLS-Limbo, and the persistent
1.5 GiB lobby) reported 2,083,389,440 cgroup bytes in use. Starting the existing
1 GiB `stage2_undead` profile raised the stabilized snapshot to 3,951,681,536
bytes; its child RSS was 1,401,856 KiB. The private instance used 230,164,976
logical bytes and 232,304,640 allocated bytes. Container I/O increased by
roughly 206.6 MB read and 231.1 MB written across provisioning and stabilization.
The instance then stopped normally and its directory was fully reclaimed.
These are ready-but-empty measurements, not simulated player load, and
container-wide I/O is not attributed to one child.

Additional ready-but-empty samples establish conservative planning floors.
They do not establish safe player capacity:

| Component | Configured maximum heap | Observed child RSS | Planning conclusion |
| --- | ---: | ---: | --- |
| Velocity | fixture flags | 343,448 KiB | Reserve at least 512 MiB outside managed-child admission; 768-1,024 MiB gives safer plugin/load headroom. |
| SLS-Limbo | 96 MiB | 141,600 KiB | Keep its 96 MiB admission reservation and allow roughly 160 MiB physical headroom. |
| Paper 1.11.2 `blastoff` | 1,024 MiB | 578,808 KiB | Retain a 1,024 MiB blueprint minimum until a representative player-load test supports less. |
| Paper 1.18.2 `stage2_undead` | 1,024 MiB | 1,401,856 KiB | A 1 GiB heap can consume more than 1 GiB RSS; plan at least 1.5 GiB of physical capacity per similar child. |
| Paper 1.18.2 persistent lobby | 1,536 MiB | 1,579,368 KiB | Retain 1,536 MiB admission and provide additional container headroom. |
| Paper 26.1.2, warm empty world | 1,024 / 768 / 512 MiB | 1,015,568 / 772,636 / 777,344 KiB | All three reached ready, but 512 and 768 MiB left no credible load headroom. Use at least a 1,024 MiB heap and 1.5 GiB physical capacity pending loaded testing. |

The Paper 26 comparison reused an already-generated world and runtime cache and
ran without a container memory limit. The 512 MiB run is a squeezed-boundary
observation, not a supported minimum. RSS can exceed `-Xmx` because Java heap
limits do not include native allocations, thread stacks, mapped files, or the
kernel's accounting around the process.

For this exact fixture, the measured ready baseline is about 1.94 GiB for
Velocity, SLS-Limbo, and one 1.5 GiB lobby. Adding one ready 1 GiB Paper 1.18.2
child raised cgroup use to about 3.68 GiB. A practical panel allocation is at
least 4 GiB for the baseline layout and 6 GiB for that layout plus one similar
game child. For additional 1 GiB game children, budget roughly 1.75 GiB each
and retain at least 20 percent uncommitted capacity until representative player
loads are measured. These physical recommendations complement, rather than
replace, `resources.total_memory_mib`, which must continue to admit the sum of
managed child heaps including SLS-Limbo.

### Reflink Implementation Gate

The Docker Desktop WSL2 ext4 volume does not expose reflinks, so the Stage 3
reflink engine was validated on a disposable XFS filesystem created with
`reflink=1` under the same WSL2 kernel. GNU `cp --reflink=always` produced a
clone whose XFS physical extent map matched its source, and modifying the clone
left the source unchanged. Unit tests additionally cover partial-clone cleanup,
per-source portable fallback under `auto`, explicit-strategy failure, and
existing-target refusal. The production Pterodactyl fixture continues to select
portable copy because its ext4 capability probe correctly rejects reflink.

The complete alternate XFS fixture passed on 2026-07-30 using a disposable
16 GiB loop filesystem exposed to Wings through a separate Docker local volume.
The normal ext4 profile was not modified. The game container reported XFS on
`/dev/loop3`, `auto` selected `reflink`, and `xfs_bmap` showed the source and
instance `level.dat` sharing the same physical block range. Instance-only writes
did not appear in the source. The managed persistent lobby passed forced restart
and rollback-protected reset; an ephemeral Stage 2 instance stopped with no
directory or transactional sibling left behind. After a hard container kill,
startup reconciliation removed the stale ephemeral instance, preserved the
persistent lobby, and reported zero failures.

### Btrfs Implementation Gate

Btrfs probing and the Java snapshot lifecycle are validated on a disposable
loop filesystem inside a privileged throwaway container. The gate creates an
eligible source subvolume, verifies shared physical extents and write
isolation, replaces the instance snapshot, deletes its backup and final
subvolumes, and unmounts the temporary filesystem. It does not modify WSL,
Docker's storage driver, or the normal Pterodactyl security profile.

Compile `BtrfsSelectionRealKernelHarness` and
`BtrfsPreparerRealKernelHarness`, mount an empty temporary Btrfs filesystem,
and run both with `target/classes`, `target/test-classes`, and the test
dependency classpath. The selection harness exercises the exact-path contained
create/snapshot/isolation/delete probe. The preparer harness covers
provisioning, source isolation, replacement, shared extents, and deletion.
The complete Pterodactyl/Velocity fixture on Btrfs remains a separate Stage 3
acceptance test.

That complete fixture passed on 2026-07-30 using a separate 16 GiB loop-backed
Docker local volume and a disposable derivative of the normal Java image that
adds only `btrfs-progs` and `fuse-overlayfs`. The Btrfs volume must be mounted
with `user_subvol_rm_allowed`; without it an unprivileged server can create a
snapshot but cannot safely delete it. The normal fixture and image remain
unchanged.

The full run used explicit `btrfs`, converted only copied fixture worlds into
eligible source subvolumes, and verified snapshot selection, source isolation,
70,107,136 shared bytes with zero exclusive bytes, managed-lobby reset,
ephemeral deletion, hard-crash reconciliation, and absence of a remaining
instance subvolume. The run also exposed and corrected an unprivileged-hosting
bug: `btrfs subvolume show/list` can require a privileged tree search even when
snapshot lifecycle operations are allowed. SLS-LITE now identifies subvolume
roots through the Btrfs inode-256 contract and detects nested subvolumes through
a contained no-symlink filesystem walk.

### fuse-overlayfs Implementation Gate

The fuse-overlayfs adapter shares the durable OverlayFS layer lifecycle and
adds exact userspace-daemon ownership verification across restart. Its
disposable gate covers contained probing, private writes, suspend/remount,
replacement, deletion, and absence of leaked mounts or daemons.

Docker Desktop exposes `/dev/fuse` in a privileged container, but an
unprivileged process without `CAP_SYS_ADMIN` returned `EPERM` from
`fusermount3` inside its container runtime. SLS-LITE therefore treats the
device and binary as prerequisites only: the exact-path contained mount probe
must also pass. Run `FuseOverlayFsSelectionRealKernelHarness` and
`FuseOverlayFsPreparerRealKernelHarness` in a disposable profile with
fuse-overlayfs installed. Never expose `/dev/fuse` or add privileges to the
normal fixture merely to make this test pass.

The complete copied UID-999 Velocity profile was also attempted with only
`/dev/fuse` and the test image exposed, no `CAP_SYS_ADMIN`, and no privileged
container. Docker Desktop permitted the device but `fuse-overlayfs` exited with
code 1 before mounting. This is a valid unsupported-host result, not a passing
rootless profile; `auto` must continue to use another strategy there. The
same host's direct Ubuntu WSL2 environment did permit the mount as UID 1000.

The direct WSL2 gate passed on 2026-07-30 without installing a package or
granting a capability. The Ubuntu package was downloaded and unpacked only
under `/tmp`; the selector and preparer real-kernel harnesses passed contained
selection, private-write isolation, suspend/remount, reset, deletion, and
cleanup. A disposable native-ext4 copy of the small Velocity fixture then
selected explicit `fuse-overlay`, started a real Paper 26.1.2 instance with a
COW world, reached ready, stopped it through idle cleanup, and shut down with
no remaining instance directory or FUSE mount. This demonstrates the intended
hosting distinction: Docker Desktop's profile is unsupported and degrades
safely, while a genuinely rootless-capable Linux profile uses FUSE.

### Snapshot-helper Full-fixture Gate

The complete copied Velocity fixture passed the explicit
`sls-snapshot-helper-v1` profile as UID 999 with no added capability on
2026-07-30. A data-directory-confined fake provider rebuilt the persistent
lobby, passed suspend/resume through restart, passed rollback-protected reset,
deleted an ephemeral Stage 2 instance without provider state, and removed a
second ephemeral instance during hard-restart reconciliation. The existing
process harness separately retains malformed-response and timeout coverage.

## Disposable OverlayFS Lifecycle Gate

The normal Pterodactyl/Velocity fixture intentionally lacks `CAP_SYS_ADMIN`.
Do not weaken that profile to test OverlayFS. Compile the test harnesses and run
them in a separate privileged container backed by disposable tmpfs:

```powershell
mvn test-compile

docker run --rm --privileged --user 0 `
  --tmpfs /mnt/sls-test:rw,size=128m `
  -v "${PWD}/target/classes:/opt/classes:ro" `
  -v "${PWD}/target/test-classes:/opt/test-classes:ro" `
  --entrypoint sh ghcr.io/pterodactyl/yolks:java_25 `
  -c 'mkdir /mnt/sls-test/root && java -cp /opt/classes:/opt/test-classes net.slimelabs.slslite.instance.storage.OverlayFsRealKernelHarness /mnt/sls-test/root'
```

Run `OverlayFsSelectionRealKernelHarness` from the
`net.slimelabs.slslite.host` package in the same way to verify that the
exact-path contained probe makes `auto` select OverlayFS. The full
`OverlayFsPreparerRealKernelHarness` also needs the locally cached SLF4J API on
its classpath; it covers prepare, suspend/remount, reset, and delete. These
harnesses require an empty disposable root and preserve failures for diagnosis.
They never require changes to the production Pterodactyl security profile.

The complete copied Velocity fixture passed this alternate profile on
2026-07-30. Stock Wings has no supported per-server `CAP_SYS_ADMIN` override, so
the Panel allocation remained stopped while its separate XFS snapshot was
mounted into a disposable privileged sibling container. The same JAR, config,
worlds, persistent lobby, commands, and child processes were used. Explicit
`overlay` passed its contained probe, an ephemeral Stage 2 instance mounted and
cleanly unmounted, a forced lobby reset created three durable overlay layers,
and a hard container restart restored all three mounts with zero reconciliation
failures. The ordinary Wings-created profile was never granted the capability.

## Preliminary COW Comparison

The 2026-07-30 full-fixture runs used the same Stage 2 world and recorded these
bounded SLS-LITE phase timings. They are diagnostic single runs, not release
benchmarks: Paper readiness dominates total time, and the fake snapshot helper
uses a full copy rather than representing a production snapshot provider.

| Strategy/profile | File preparation | Total readiness | Initial data movement and physical use |
| --- | ---: | ---: | --- |
| Portable copy / native ext4 | 257.669 ms | 10,763.536 ms | Reads and writes the full logical source; independent physical copy |
| Reflink / XFS `reflink=1` | 334.175 ms | 10,794.967 ms | Metadata clone; matching shared XFS extent verified, with blocks copied on write |
| Btrfs snapshot | 252.178 ms | 13,971.073 ms | Metadata snapshot; 70,107,136 shared bytes and zero exclusive bytes at creation |
| Kernel OverlayFS | 61.911 ms | 13,890.374 ms | No base copy; immutable lowers plus private upper/work directories |
| Rootless fuse-overlayfs / WSL2 ext4 | 236.188 ms | 19,826.619 ms | No base copy; immutable lower plus userspace-managed private upper/work directories |
| Fake snapshot helper | 60.746 ms | 10,133.907 ms | Provider-dependent; the fake helper reads and writes a full logical copy |

Every successful profile passed source-write isolation, cleanup, persistent
restart or remount, and hard-restart reconciliation. Unit and real-kernel
harnesses cover cancellation and rollback. Initial read/write volume follows
the table's data-movement model: `N` logical bytes read and written for full
copy providers, versus metadata plus subsequently dirtied blocks for native
COW providers. Raw host block-I/O counters are not compared across Docker
Desktop loop devices and direct WSL2 because those layers account cached I/O
differently; physical extent, exclusive/shared byte, mount-layer, isolation,
and cleanup evidence is used instead.

## Reprovisioning

The Docker migration and management scripts are documented in
`infra/pterodactyl/README.md`. Recreate or repair the Velocity allocation with:

```powershell
docker cp scripts/create-pterodactyl-velocity-local.php sls-ptero-panel:/tmp/create-pterodactyl-velocity-local.php
docker exec -e PANEL_ROOT=/app sls-ptero-panel php /tmp/create-pterodactyl-velocity-local.php
```

Both server provisioning helpers are idempotent. They accept `--start`,
`--stop`, `--restart`, or `--kill` for infrastructure troubleshooting.
