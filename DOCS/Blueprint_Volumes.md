# Blueprint Volumes

Status: SLS and SLS-LITE field shape; adapted for SLS-LITE local mode.

SLS-LITE supports the modern SLS `state.volumes` structure for placing locally
supplied worlds and other directory content into a managed server instance.
It recognizes the modern `cow`, `ro`, and `rw` modes, with explicit local
adaptations and boundaries.

```yaml
state:
  volumes:
    - name: world
      source: worlds/minigames/spleef
      target: /world
      mode: cow
```

## Local `cow` Behavior

Full SLS can provide copy-on-write storage through its node infrastructure.
SLS-LITE must work without that infrastructure, so it implements the same
isolation outcome as a portable directory copy:

1. SLS-LITE copies the selected software base into a new instance directory.
2. It copies each volume source into its configured instance target.
3. The managed server changes only its private copy.
4. Ephemeral cleanup removes that copy. Persistent instances retain it until
   reset or deletion.
5. Resetting a persistent instance recopies both its software base and its
   clean volume sources.

Multiple `cow` entries may target the same exact directory. SLS-LITE merges
them in blueprint declaration order, matching the lower-layer order used by
SLS v0.2.0:

- directories merge;
- the first source wins when the same path exists in multiple sources;
- later sources fill paths that earlier sources do not contain;
- repeated volume names are accepted because upstream examples use them for
  same-target plugin merges.

This baseline uses more disk space and startup time than filesystem-native
copy-on-write. It works on ordinary shared-host filesystems without requiring
Docker mounts, overlay filesystems, or elevated privileges.

## Storage Strategy Contract

Transactional portable copying, reflink cloning, Btrfs subvolume snapshots,
kernel OverlayFS, and fuse-overlayfs are active strategies.
`storage.strategy` accepts `auto`, `copy`, `reflink`, `btrfs`, `overlay`,
`fuse-overlay`, and `snapshot-hook`. Startup capability checks probe the
configured instance filesystem, atomic directory moves, reflink cloning, Btrfs
identity, and kernel/FUSE overlay prerequisites, then report the requested and
selected strategy in `/sls system`. `auto` selects reflink, Btrfs, kernel
OverlayFS, or fuse-overlayfs only after the configured instance path passes the
corresponding isolation probe. A
reflink source on a different or incompatible filesystem falls back
transactionally for that source; explicit `reflink` fails instead. When Btrfs
is selected, a `cow` source must be a subvolume without nested subvolumes.
Ineligible sources fall back under `auto`; explicit `btrfs` fails. Other
unsupported native capabilities remain informational under `auto`.
Explicitly requesting an unavailable strategy fails startup rather than
silently falling back. An explicitly configured, versioned, bounded snapshot
helper supports storage such as ZFS, LVM thin volumes, or provider APIs. No
strategy may change blueprint behavior:

- source content remains unchanged;
- every instance receives an isolated writable view;
- same-target sources keep declaration-order, first-source precedence;
- reset reconstructs the view from clean sources;
- failed preparation and cleanup do not leave an instance presented as ready;
- unsupported native capabilities fall back to portable copying unless an
  operator explicitly requires a native strategy.

Every native COW implementation must be verified for storage-location support,
write isolation, unclean shutdown recovery, cleanup, and real disk savings
before becoming automatic. Reflink passed its implementation gates through the
contained probe, transactional preparation tests, and a real XFS `reflink=1`
shared-extent test on WSL2. Btrfs passed contained snapshot/isolation/cleanup
probing plus prepare, replacement, reconciliation, deletion, and shared-extent
tests on a disposable real Btrfs filesystem. Snapshot helpers are never
auto-discovered. fuse-overlayfs passed the same overlay lifecycle tests plus a
real contained probe and prepare/restart/reset/delete gate. `/dev/fuse` alone
does not make a host eligible: the contained mount must succeed. Snapshot
helpers passed fake-provider process, timeout, malformed-response, rollback,
lifecycle, and reconciliation tests.

The current `auto` priority is `reflink`, Btrfs snapshot, kernel OverlayFS,
rootless fuse-overlayfs, then portable copy. An operator `snapshot-hook` is
never part of automatic selection. This is the conservative general-purpose
order: reflinks provide block-level COW in ordinary directories without a
mount lifecycle, while overlays require managed mounts and FUSE adds a
userspace process.

Reflink remains ahead of Btrfs in the general priority. If Btrfs is selected,
an eligible `cow` source uses an instant writable snapshot. Ordinary
directories, sources with nested subvolumes, `ro` volumes, and later sources in
a same-target ordered merge use portable semantics. Benchmarks may adjust the
global order only after all safety gates still pass.

`auto` and explicitly requested kernel OverlayFS run a contained
mount/write-isolation/unmount/cleanup probe once their kernel and privilege
prerequisites are present. The probe proves that writes reach only the private
upper layer and that the lower source remains unchanged. Provisioning uses a
durable per-instance manifest, verifies ownership before unmounting, remounts
persistent instances, suspends layers before reset/delete, and reconciles stale
managed mounts after an unclean proxy exit.

## Local `ro` and `rw` Policy

SLS-LITE accepts all three modern mode names so compatible blueprints can be
loaded and inspected without schema translation.

`ro` is adapted to a private snapshot copy. The managed process may write to
its instance copy, but the configured source directory is never mounted or
modified. This preserves source protection and provider portability, but it is
not a byte-for-byte equivalent of a read-only container bind mount.

`rw` is an explicit opt-in to shared writable host state. Preparation creates
and verifies a directory symbolic link at the declared instance target. The
source outlives ephemeral instances, and every concurrent instance using the
volume writes to the same directory. Use `max_instances: 1` unless the server
software and plugins are specifically designed for concurrent shared-file
access. Hosts without directory-link support reject preparation
transactionally.

The source is intentionally mutable: changes from the child are immediately
visible in the configured source and survive instance reset/deletion. Use it
only for trusted server software/plugins and data specifically designed for
single-writer sharing. SLS-LITE does not snapshot, roll back, or back up `rw`
content. Configuration patches may not target paths through the link.

## Paths

`source` is relative to the SLS-LITE plugin data directory. For example,
`worlds/minigames/spleef` resolves to:

```text
<SLS-LITE data>/worlds/minigames/spleef
```

Blueprint YAML files can be organized into nested folders below `blueprints/`.
The folder name is for operators only; the blueprint's `blueprint.type` remains
the registry used by commands and matchmaking.

`target` uses the modern SLS instance-path form. `/world` maps to the `world`
directory at the root of the newly prepared managed instance. A target without
the leading slash, such as `world`, has the same local result.

Volume paths must use `/` separators. SLS-LITE rejects:

- Absolute or escaping source paths.
- Targets that escape or select the instance root.
- Symbolic links in a source path or anywhere inside copied content.
- Sources inside the managed instances directory.
- Ancestor/descendant target overlaps such as `/world` and `/world/data`.
- Same-target combinations unless every entry uses `cow`.
- Targets that collide with files or directories from the software base.

Preparation is transactional. If the software or any volume cannot be copied,
SLS-LITE removes the incomplete instance. A failed persistent reset preserves
the previous instance directory.

## Current Limits

- `cow` and local-snapshot `ro` use complete directory copies.
- `rw` requires a persistent, single-instance blueprint and host directory
  symbolic-link support.
- Volume sources must already exist before the blueprint is started.
- Operators must budget disk space for a complete copy per instance.
- Do not modify a source directory while an instance is being prepared.

These limits define the portable baseline. Native copy-on-write optimizations
may be added later only when they preserve the same blueprint behavior and have
a reliable portable fallback. Stage 3 also includes improving that fallback
through measured bounded parallelism, sparse-file preservation, safe reuse of
verified immutable artifacts, and avoidance of unnecessary persistent-instance
reconstruction. Mutable world data must never be hard-linked or shared.
