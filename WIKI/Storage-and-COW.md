# Storage and COW

Blueprint volumes use `cow`, `ro`, or `rw` intent:

- `cow` creates an isolated writable instance view from clean source content.
- `ro` protects the source through a private writable instance view; it is not
  a strict read-only bind mount.
- `rw` intentionally links persistent single-writer instance state to a shared
  source and receives no rollback or backup from SLS-LITE.

With `storage.strategy: auto`, SLS-LITE prefers:

1. Reflink
2. Eligible Btrfs snapshot
3. Kernel OverlayFS
4. Rootless fuse-overlayfs
5. Portable copy

`storage.auto_priority` can replace that order with any non-empty unique subset.
It is authoritative: omitted strategies are not probed or selected, and
omitting `copy` disables portable fallback. `storage.copy_parallelism` retains
the conservative CPU-based automatic limit by default or accepts a bounded
explicit worker count.

Each native choice requires a successful probe on the actual configured path.
An operator snapshot helper is explicit-only and never auto-discovered. Missing
privileges, `/dev/fuse`, filesystem support, or helper access do not justify
weakening a hosting provider's normal security profile; `auto` uses portable
copy instead.

Always budget enough disk for portable fallback and keep source worlds
unchanged while preparation is running.

Canonical reference: [Blueprint Volumes](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Blueprint_Volumes.md). Operational probes and recovery are documented in [Operations](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Operations.md).
