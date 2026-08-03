# Stage 3 Engineering Evidence

This record preserves dated implementation, fixture, compatibility, and
performance observations gathered while Stage 3 was developed. It is not
operator documentation and does not define current product behavior. Current
claims and instructions live under `DOCS/`; release qualification must rerun
the applicable workflows rather than treating these historical results as a
substitute.

## Stage 2 Compatibility Closure

The pinned modern SLS contract is `v0.2.0` at commit
`8e8b1e3cf7d2157887764c16f11b8901f8241121`. The compatibility review closed on
2026-07-29 against upstream source/examples, the normalized 54-blueprint
SlimeLabs corpus, and SLS-LITE's parser, lifecycle, storage, and command code.

The deployed Docker Desktop/Pterodactyl build had SHA-256
`F335058F554693F047D7D1754B8336C9BB23470248DD38C5F1D12E5AFDD42644`.
The Maven suite passed 302 tests and the exact-ID corpus gate loaded all 54
expected definitions. Paper 1.18.2 lobby and Undead instances plus an unchanged
Paper 1.15.2 Wildfire definition accepted client transfers. The run covered
Java mapping, software-cache reuse, COW/copy/env state, configuration patches,
vSLS lifecycle/matchmaking/on-join behavior, cleanup, and rejection of unsafe
host mounts/shared-volume combinations.

The final correction artifact had SHA-256
`0C6A9C7811D8C98DA5346104E91153D99FA15F17D029308821E3934E35AF1C1F`.
Its suite passed 308 tests, the 54-ID corpus passed, required Java 8/11/16/17
runtimes passed preflight, unused Java 21/25 runtimes remained warnings, 13
blueprints and 2 software profiles loaded, and SLS-Limbo plus persistent lobby
`lobby.97f1ae` reached ready.

The exact corpus contained the `adventure`, `archive`, `experimental`, and
`minigame` registries. `BLUEPRINT_TEMPLATE.yaml.example` was excluded and
`adventures/temple_of_doom.yaml` included. The source corpus was not modified.

## Stage 3 Lifecycle And Command Gates

- 2026-07-30: native ext4 persistent restart sample reached primary readiness
  in 14,199.508 ms using portable copy.
- 2026-07-31: force-kill artifact
  `987556DDC0564801751CF1E35C84F2B276B84C82106AC40BAC7861140EBC1AFD`
  evacuated a persistent target, exited it with code 137, retained its data,
  and transactionally deleted the later disposable target. Unforced `kill all`
  removed an ephemeral target and protected the managed lobby.
- 2026-07-31: debug artifact
  `363098E8498319EF742F7E9E18FFCDACC2F9D84A1563FA80AE074DD33EFA7514`
  enforced the console rejection and player permission/toggle behavior.
- 2026-07-31: retained-modifier artifact
  `5A2B94FCD7CD00A7D898E3E4BB904A44345728A242FE8B79380E63EBD1F4880B`
  preserved local-mode responses, rejected container-only flags before
  allocation, and gracefully stopped a disposable instance.
- 2026-07-31: branch-parity artifact
  `95265280201F0557493046387E4221DDF580E6BF674572830BFAC9AE0E8DF58B`
  covered trailing input, targetless console status, selector errors, protected
  bulk stop, and successful ordinary bulk stop.

## Stage 3 Storage Evidence

The normal restricted Pterodactyl allocation selected `portable-copy`. Its
Windows-backed path reported `9p`; the WSL2-native Docker volume reported ext4.
Neither exposed a usable reflink or privileged kernel mount to the game
container.

### Portable-Copy Samples

| Profile | Files | Logical size | `9p` prepare median/p95 | ext4 prepare median/p95 | `9p` cleanup median/p95 | ext4 cleanup median/p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Small | 15 | 2,600,463 B | 148.289/198.836 ms | 7.993/38.462 ms | 199.082/207.404 ms | 3.351/6.968 ms |
| Medium | 247 | 69,865,556 B | 2,826.839/3,456.244 ms | 61.866/235.200 ms | 2,475.681/2,495.240 ms | 17.444/24.090 ms |
| Large | 1,330 | 1,335,887,002 B | 22,063.596/27,681.142 ms | 1,011.649/3,045.207 ms | 11,051.024/11,105.902 ms | 1,095.762/1,199.287 ms |

Every copied tree matched source file count/logical bytes and was removed.
These were cache-sensitive diagnostic samples, not release thresholds.

### Native COW Profiles

- Reflink: a disposable 16 GiB XFS `reflink=1` volume selected `reflink`;
  source and clone shared physical extents, private writes stayed isolated,
  persistent restart/reset and ephemeral cleanup passed, and hard-restart
  reconciliation preserved persistent data while removing stale ephemeral
  state.
- Btrfs: a separate 16 GiB loop-backed volume mounted with
  `user_subvol_rm_allowed` selected `btrfs`; snapshot creation shared
  70,107,136 bytes with zero exclusive bytes, writes stayed isolated, reset,
  deletion, and hard-crash reconciliation completed without a remaining
  instance subvolume.
- Kernel OverlayFS: a disposable privileged sibling—not the Wings allocation—
  passed contained probing, ephemeral mount/unmount, persistent reset with
  durable layers, and hard-restart remount/reconciliation.
- fuse-overlayfs: direct Ubuntu WSL2 as UID 1000 passed selection, isolation,
  suspend/remount, reset, deletion, and cleanup. Docker Desktop with only
  `/dev/fuse` exposed to UID 999 returned `EPERM` and correctly fell back.
- Snapshot helper: a data-confined fake `sls-snapshot-helper-v1` provider
  covered persistent rebuild, suspend/resume, rollback-protected reset,
  ephemeral deletion, and hard-restart reconciliation without additional
  container privilege.

The representative single-run preparation timings were 257.669 ms portable
ext4, 334.175 ms XFS reflink, 252.178 ms Btrfs, 61.911 ms kernel OverlayFS,
236.188 ms rootless fuse-overlayfs, and 60.746 ms for the fake snapshot helper.
Paper readiness dominated totals; the helper performed a copy and did not
represent production snapshot performance.

Sparse-copy sampling reduced a 64 MiB logical zero-heavy file from 64 MiB
allocated after ordinary Java copy to 2 MiB with the sparse-aware fallback,
with byte-identical content. Bounded parallel preparation improved the sampled
Windows workloads from 2,221 to 1,141 ms (1,000 small files) and 49 to 23 ms
(eight 8 MiB files); Linux samples changed from 176 to 162 ms and 66 to 12 ms.

## Stage 3 Resource Samples

Ready-but-empty samples produced these planning observations:

| Component | Configured heap | Observed child RSS |
| --- | ---: | ---: |
| Velocity | fixture flags | 343,448 KiB |
| SLS-Limbo | 96 MiB | 141,600 KiB |
| Paper 1.11.2 Blastoff | 1,024 MiB | 578,808 KiB |
| Paper 1.18.2 Undead | 1,024 MiB | 1,401,856 KiB |
| Paper 1.18.2 persistent lobby | 1,536 MiB | 1,579,368 KiB |
| Paper 26.1.2 | 1,024/768/512 MiB | 1,015,568/772,636/777,344 KiB |

The baseline Velocity, SLS-Limbo, and lobby used about 1.94 GiB cgroup memory;
adding one 1 GiB Paper 1.18.2 child raised it to about 3.68 GiB. These empty
samples informed the 4 GiB baseline and 6 GiB baseline-plus-one planning floors,
but do not establish loaded player capacity.

## Stage 3.8 Network Evidence

On 2026-08-02:

- External lobby mode started only the separately allocated Paper lobby,
  Velocity, and SLS-Limbo; SLS-LITE did not create or own a managed primary.
  Returning to managed mode resumed the same persistent lobby directory.
- Both `first-available` and `random` blueprint selection initialized cleanly;
  the fixture was restored to `first-available`.
- `lobby.auto_start: false` left the managed primary stopped and routed arrivals
  to SLS-Limbo; restoring it resumed `lobby.97f1ae`.
- The bounded matchmaking workflow cancelled a queue-owned preparation,
  transferred two clients into the two allowed one-player instances, listed
  the lobby/minigame registries, rejected a third player at pool capacity, and
  idle-cleaned both ephemeral instances.
- Automated clients `1.21.5` and `1.21.11` remained connected across
  Paper -> SLS-Limbo -> recovered Paper forced-lobby restarts.
- A real stable `26.2` client (`Yeetoxic`) moved from `lobby.97f1ae` to
  `sls-limbo` at 21:04:32, the lobby exited with code zero, ViaVersion
  resynchronized protocol 758, and the same connection returned to the ready
  lobby at 21:04:47.
- Direct NanoLimbo login completed for 1.13.2, 1.16.5, 1.20.4, 1.21.4,
  1.21.5, and 1.21.11. A prior real 26.1 client reached native SLS-Limbo.

## Verification State At Stage 3.8 Closure

The final full `mvn verify` run reported 602 tests, zero failures, zero errors,
and seven environment-dependent skips. Spotless and SpotBugs passed. Script
syntax, documentation-contract tests, protocol matrices, `git diff --check`,
and the local process audit passed. The fixture ended with only Velocity,
SLS-Limbo, and the persistent managed lobby JVM running.

## Stage 3.9 Clean-Install Exercise

On 2026-08-02, the stopped native-ext4 Pterodactyl allocation was copied to the
recoverable Docker volume `sls-ptero-stage3-preclean-20260802`, then only that
allocation was cleared. A preserved Velocity JAR with SHA-256
`4540289F48C83E305FC2F2C495A84D1F4D0B7F360830251E169DD5A208740E70`
generated a stock Velocity 4 configuration before the release candidate was
installed.

The first pass exposed two onboarding defects: the bundled `template.yml` was
loaded as a real `game/template` blueprint, and Velocity's stock unreachable
`lobby` entry was trusted as the external primary. The latter produced a
connection-refused stack trace before fallback. The fixes changed the generated
template to ignored `template.yml.example`, required an external-primary health
probe before routing, and documented removal of stock servers with Velocity's
required `try = []` setting. The affected clean boot was repeated with artifact
SHA-256 `26F68B99DD8AF67CFE4969509D269A4D944B8418557633543D8F4C63FCFFB2E8`.
It loaded zero blueprints, generated only the ignored example, started
SLS-Limbo, and accepted a 1.21.5 login without a failed-backend stack trace.

Fresh SLS-LITE defaults occupied 5.2 MiB, chiefly the reproducible NanoLimbo
runtime. After accepting the EULA and adding the retained clean-install lobby
fixture, Paper 26.2 installed and the new persistent `lobby.u24v30` reached
ready. The complete allocation occupied 320.6 MiB: SLS-LITE used 296.6 MiB,
including 58.9 MiB of reusable Paper software and 232.5 MiB for the generated
lobby instance and world. Normal shutdown exited cleanly; restart preserved one
persistent directory and returned the same lobby ID to ready in 10.1 seconds
without reinstalling software.

The optional ViaVersion follow-up exposed a separate integration defect:
NanoLimbo's native `-1` sentinel was cached as an actual ViaVersion backend
protocol. SLS-LITE now exposes native mode to integrations as the tested 770
baseline while retaining NanoLimbo's native configuration. Focused regression
tests passed, and updated artifact SHA-256
`00DB8DC378442E78A7363BA72FB52FF4DE6F9F6CE933A9345381F488D592FA09`
mapped `sls-limbo` to 770 in the live fixture. A 1.21.5 client rejected by the
newer Paper 26.2 backend then reached `SLS-Limbo (Velocity)` without the former
Via decoder failure. ViaBackwards remains required if older clients must enter
newer Paper rather than use the holding lobby.

The final verification run passed 605 tests with zero failures or errors and
seven environment-dependent skips. Spotless and SpotBugs passed.

### Production-Style Online Network Repeat

A second clean allocation run retained only Velocity's JAR and used no prior
SLS-LITE data, software cache, world, or optional plugin configuration. The
stock Velocity configuration remained in online mode; its example backends
were removed, `try = []` was retained, and modern forwarding was enabled with
a newly generated private 256-bit secret. The generated SLS-LITE configuration
was changed to modern forwarding with matching online-mode semantics, and a
fresh persistent Paper 26.2 lobby was installed with explicit EULA acceptance.

Velocity, SLS-Limbo, and managed lobby `lobby.58e8xc` reached ready without a
forwarding warning or error. Paper was confined to `127.0.0.1`, used backend
`online-mode=false`, enabled Velocity forwarding with `online-mode=true`, and
contained the exact Velocity secret. An unauthenticated automated client was
rejected by Velocity before backend routing. Normal proxy shutdown preserved
the persistent lobby and secret; the same lobby ID returned ready in 9.7
seconds without reinstalling Paper.

A Mojang-authenticated 26.2 client then connected through Velocity to the
managed Paper lobby. Paper received the same authenticated UUID, the one-time
administrator claim persisted that UUID and player name, and the proxy error
scan contained no authentication, forwarding, secret, or connection failure.
The fixture was left online with the authenticated player, Velocity,
SLS-Limbo, and the managed lobby healthy. The pre-reset fixture remains
recoverable in `sls-ptero-stage3-online-preclean-20260802`.

### Corrected Operator-Layout Repeat

After the production-style run exposed missing operator content roots, the
allocation was backed up and verified file-for-file in the recoverable Docker
volume `sls-ptero-stage3-layout-preclean-20260802`. A direct Docker stop proved
to be an invalid test boundary because Wings retained the allocation's running
intent; the repeat therefore used the Panel power path for every stop, start,
and restart.

Artifact SHA-256
`E9DE06A75382F4E8297DC14AC0A415C93A0684955BAEED44DF62E1A3137670B6`
started with no prior SLS-LITE data. Before any blueprint or provider download,
it created empty `volumes/worlds/`, `volumes/plugins/`, `software/`, and
`runtimes/` roots with allocation ownership and generated no `volumes/PGM/`.
The initial data footprint was 5.3 MiB, principally NanoLimbo. The ignored
blueprint example was the only file below `blueprints/`.

After the documented modern-forwarding, online-mode, EULA, and managed-lobby
settings were applied, Paper 26.2 downloaded from an empty cache and persistent
lobby `lobby.b5kk8m` reached ready. A Mojang-authenticated client reached that
lobby and claimed administration; its Velocity UUID persisted in the fresh
administrator store. A normal Panel restart completed SLS-LITE shutdown,
preserved the same instance directory and lobby ID, and returned it to ready in
10.6 seconds without reinstalling Paper. The detail log contained no failed or
exception events, and the fixture ended with only Velocity, SLS-Limbo, and the
managed lobby JVM running. The final full verification run passed 608 tests
with zero failures or errors and seven environment-dependent skips.

### Environment-Neutral Default Audit

The generated host configuration, blueprint example, software profiles,
SLS-Limbo settings, omitted-key fallbacks, and fixture overrides were reviewed
after the corrected clean-install run. Rig-sized product defaults were reduced
from a 4096 MiB/101-process/101-port baseline to an explicit 2048 MiB managed
budget, 20 process and loopback slots, and a 1024 MiB blueprint example.
Managed child logs now default to 2048 KiB; SLS-LITE diagnostics default to
normal level with a 4096 KiB file, three retained files, and a 1024-record
queue. Test fixtures retain larger or more verbose values explicitly.

Generated SLS-Limbo settings no longer hard-code 100 players and instead copy
Velocity's configured `show-max-players` value. Artifact SHA-256
`40C8954055D5462A672ED17503A4ADA86E3472AF0A9E89F5CCC571460C9FD361`
was booted with an empty plugin data directory on the native-ext4 fixture. The
live generated file contained every revised value, its 5.3 MiB initial
footprint contained no provider cache or instance, normal logging emitted no
detailed records, and SLS-Limbo inherited Velocity's configured 500-player
capacity. The claimed production snapshot was then restored from verified
volume `sls-ptero-stage3-default-audit-preclean-20260802`; the same persistent
`lobby.b5kk8m` and administrator returned ready under the audited artifact.
The full verification run passed 610 tests with zero failures or errors and
seven environment-dependent skips.

### Managed-Lobby Shutdown Finalization

The production-style Pterodactyl fixture reproduced both deferred-cleanup
warnings during one otherwise clean stop of persistent lobby `lobby.b5kk8m`.
The process supervisor's exit future became observable before its dependent
storage finalizer was guaranteed to be submitted, allowing proxy shutdown to
close the finalization executor inside that callback-order window.

Shutdown now waits on each managed instance's terminal cleanup future before
closing the finalization executor. It persists `STOPPING` state and process
identity before stopping active children, and genuine deadline overruns emit
one deduplicated warning while preserving metadata and storage for startup
reconciliation. Regression coverage verifies both a completed persistent-lobby
shutdown and a forced zero-deadline overrun.

The corrected artifact completed two subsequent Panel stops with a code-zero
Paper exit and no deferred-cleanup warning. The shutdown-fix artifact SHA-256
`8B1DB99F74E86D4E1715C6606C44FB3C2F762623B091AD6EE732AFFE547F23B4`
was deployed through the Panel workflow; Velocity, SLS-Limbo, and the same
persistent lobby returned ready. The full verification run passed 612 tests
with zero failures or errors and seven environment-dependent skips; Spotless
and SpotBugs passed.

### Release-Candidate Performance And Efficiency

The native-ext4 production-style allocation was measured with Velocity,
SLS-Limbo, and persistent Paper 26.2 lobby `lobby.b5kk8m` ready. The fixture
helper initially allowed Velocity to size its heap to 95% of the entire 6 GiB
container even though managed children share that cgroup. The helper now uses
an explicit 128/512 MiB Velocity heap and reapplies that bounded startup to
older reusable fixtures. Operator guidance now requires the proxy heap,
managed admission budget, and native/RSS headroom to be sized separately.

After that correction, a 32-second idle sample consumed 1.769 CPU-seconds
across all three JVMs: about 5.53% of one core or 1.38% of the four-vCPU
allocation. Cgroup memory changed from 1,360,863,232 to 1,365,921,792 bytes.
Ending RSS was 257,384 KiB Velocity, 132,728 KiB SLS-Limbo, and 1,020,848 KiB
Paper; summed RSS double-counts shared pages. Thread and descriptor counts
remained 38/71, 24/16, and 45/130 respectively. The sample transferred 70
bytes in each network direction, grew neither Velocity nor SLS-LITE logs, and
performed only 4 KiB of Paper reads and 68 KiB of Paper writes. Cgroup memory
reported no `high`, `max`, OOM, or OOM-kill event and no memory pressure; idle
I/O pressure remained zero.

The live SLS-LITE tree occupied 319,916,709 bytes: approximately 243 MiB for
the retained lobby, 59 MiB for reusable Paper software, 5.2 MiB for SLS-Limbo,
and 44 KiB for SLS-LITE logs. A bounded-heap Panel restart provisioned the
persistent lobby in 10.726 seconds and completed primary recovery in 11.158
seconds. Software resolution, file preparation, configuration, launch, and
registration together used about 215 ms; Paper readiness dominated at 10.505
seconds. The preceding clean stop used 661 ms for shutdown and 2.6 ms for
cleanup. Repeated cached recovery samples ranged from 9.880 to 11.728 seconds;
the clean-install first run took 26.322 seconds, including provider/software
work and Paper's longer initial readiness.

The preserved Stage 3.8 native fixture supplied the multi-instance workload
without changing the online production fixture. Two concurrent ephemeral Paper
instances became ready in 9.686 and 17.600 seconds; their successful
matchmaking totals were 11.067 and 20.369 seconds, including 1.381 and 2.768
seconds for transfer. Cancellation samples completed in 19-238 ms before file
work and roughly 291-353 ms while portable preparation was active. The two
ephemeral instances shut down concurrently in about 4.2-4.3 seconds with
48-51 ms cleanup. Admission remained bounded and both directories were
removed. These figures establish an empty/one-client release baseline, not a
claim about loaded player capacity. Storage-strategy preparation, allocation,
sparse-copy, and cleanup measurements remain recorded in the Stage 3 storage
matrix above.

### Continuous Integration Gate

The repository now runs `mvn -B --no-transfer-progress clean verify` on a
GitHub-hosted Temurin 25 runner for every push and pull request. Checkout, Java
setup, and artifact upload actions are pinned to reviewed commits. The shaded
plugin is retained for 14 days, and pull requests receive a separately pinned
GitHub dependency review that rejects moderate-or-higher known
vulnerabilities.

Maven dependency analysis is bound to `verify` with warnings treated as build
failures. Direct Adventure, SLF4J, Guice, and JUnit API/engine dependencies now
replace reliance on undeclared Velocity transitives or the unused JUnit
aggregator; only the reflectively loaded JUnit engine has a documented unused
analysis exception. A clean local CI-equivalent run passed 612 tests with zero
failures or errors and seven environment-dependent skips, found no dependency
problems, passed Spotless and SpotBugs, and produced Java 21 class-file version
65 from JDK 25.

Attempting the workflow with JDK 21 correctly exposed that the pinned Velocity
4.1 API contains Java 25 class files (version 69). Build and getting-started
documentation now distinguishes the required JDK 25 build/proxy runtime from
SLS-LITE's Java 21 output bytecode.

The final CI-equivalent artifact SHA-256
`610C4438D7EE6EC1C114E7501ADAF8EB505C97C743BE2C3E7BDD198A356617DB`
was deployed through the Panel workflow. Velocity started with its bounded
512 MiB heap, SLS-Limbo became ready, and persistent lobby `lobby.b5kk8m`
returned ready without an SLS-LITE warning or failure.

### Stage 3.9 Complete Release-Gate Rerun

On 2026-08-03, the complete automated, compatibility, packaging, storage, and
documented local Pterodactyl gate passed. `mvn clean verify` ran 612 tests with
zero failures or errors and seven environment-dependent skips. Dependency
analysis, Spotless, and SpotBugs passed. The exact modern corpus loaded all 54
expected blueprints across `adventure`, `archive`, `experimental`, and
`minigame`; the pinned SLS `v0.2.0` checkout at
`8e8b1e3cf7d2157887764c16f11b8901f8241121` loaded all six example files with
no unexpected rejection.

The 5,972,105-byte shaded JAR contained all required defaults, notices,
licenses, bundled NanoLimbo runtime, plugin metadata, and main classes. It
contained no legacy root defaults or unrelocated SnakeYAML classes, and its
main class retained Java 21 class-file version 65. The exact artifact deployed
through the Panel workflow had SHA-256
`82D9EA53C8C7ED44BEDA640E2F98ED709785571E1587899539602E060041507C`.

Disposable Linux real-kernel runs passed portable sparse-copy and snapshot
helper process-protocol coverage; Btrfs exact-path selection and
prepare/reset/delete; kernel OverlayFS probing, automatic selection, lifecycle,
and preparation; and rootless fuse-overlayfs exact-path selection plus
prepare/reset/delete. The release rerun added the previously missing executable
XFS harnesses. A loop-backed XFS `reflink=1` volume passed explicit selection,
physically shared extents, write isolation, replacement, deletion, and source
preservation. Its first lifecycle run exposed only an overly exact harness
check for flag `0x2000`; XFS correctly reported the shared bit as `0x2001`, and
the corrected bitmask assertion passed on a fresh volume.

The production-style allocation retained its normal Pterodactyl security
profile and correctly selected `portable-copy` on ext4: reflink cloning was
rejected, the process lacked `CAP_SYS_ADMIN`, `/dev/fuse` was unavailable, and
no explicit snapshot helper was configured. Startup reported eight passed, six
informational, zero warnings, and zero failures. SLS-Limbo became ready,
persistent Paper 26.2 lobby `lobby.b5kk8m` reconciled and returned ready, and a
bounded join probe passed in 178 ms. Documented blueprint, status, info, stats,
administrator, maintenance, installation, version, and local-node responses
were exercised without a product error.

Finally, an authenticated online-mode client connected through Velocity and
was routed to the loopback-only managed lobby. Velocity and Paper both observed
player `Yeetoxic`; Paper received Mojang UUID
`adc76802-d75c-4143-89a6-ede25720ecce`, establishing modern forwarding rather
than an offline test-client shortcut. Player-side `/sls info`, `/sls system`,
and `/sls list` completed. Velocity's pre-registration no-server warning,
Paper's offline-backend warning, and upstream JDK Unsafe deprecation warnings
were expected for this secured proxy topology and were not SLS-LITE failures.
