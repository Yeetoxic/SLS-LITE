# SLS-LITE 0.1.0-rc.1

This is the first SLS-LITE release candidate. It is intended for controlled
single-host Velocity networks and external testing before the first stable
release.

## Included

- Dynamic blueprint registries, local Paper/vanilla installation, managed
  instances, persistent-instance recovery, capacity-aware matchmaking, queues,
  and lifecycle commands.
- Reflink, eligible Btrfs snapshot, kernel OverlayFS, rootless fuse-overlayfs,
  operator snapshot-helper, and bounded portable-copy storage strategies.
- Velocity-owned, external, or managed lobby routing with the optional bundled
  SLS-Limbo safety backend and automatic return after an owned lobby outage.
- Granular Velocity permissions, secure first-administrator claiming, bounded
  diagnostics, detailed rotating logs, and host capability reporting.
- Java extension API 1.0 plus the default-off, source-authorized Paper backend
  messaging protocol.

## Compatibility Baseline

- Java 21 plugin bytecode on the tested Java 25 Velocity runtime.
- Exact Velocity 4.1.0 API snapshot pinned in `pom.xml`; the exercised local
  proxy runtime is recorded in `DOCS/Compatibility.md`.
- Stable Minecraft/Paper through 26.2 where the documented provider, Java,
  plugin, forwarding, and real-client requirements are satisfied.
- With compatible ViaVersion installed, Minecraft 26.2 is the forward-client
  minimum rather than a maximum: newer clients are allowed when that installed
  ViaVersion build reports support for them. The matrix separately identifies
  the exact paths regression-tested for this candidate.
- NanoLimbo 1.13.0 at revision
  `d192d57d1d4a5fdc7b87643f453d82cb7b9b4242` for SLS-Limbo.

Minecraft 26.3 snapshots and experimental upstream branches are not individually
release-qualified. A future stable client may use the forward-compatible
ViaVersion policy without waiting for an SLS-LITE update, but ViaVersion must
first support it and the backend/plugin stack can still impose its own limits.

## Upgrade From A Development Build

Stop Velocity normally, back up the complete `plugins/sls-lite/` directory,
replace the plugin JAR, and restart Velocity. Review reconciliation and
configuration diagnostics before starting or resetting persistent instances.
Replacing the JAR through a plugin hot-reloader is unsupported.

The generated host configuration remains operator-owned. SLS-LITE does not
replace an existing file with candidate defaults; compare it with the canonical
commented configuration shipped beside the release artifact.

## Known Boundaries

- SLS-LITE manages one host allocation. It does not reproduce full SLS nodes,
  containers, HTTP control plane, or distributed resource enforcement.
- Host permissions determine which storage strategies and child-process
  features are available. Portable copy remains the universal default fallback
  unless the operator excludes it.
- Vanilla backends cannot use modern Velocity forwarding.
- Server software or ViaVersion protocol availability does not guarantee
  compatibility of arbitrary worlds, plugins, Java versions, or game behavior.

Use the current
[installation and upgrade guide](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Getting_Started.md),
[compatibility matrix](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Compatibility.md),
and
[troubleshooting guide](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Troubleshooting.md).
Report candidate issues with the SLS-LITE version, host capability summary,
relevant detailed-log excerpt, and a minimal redacted configuration or
blueprint.
