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
