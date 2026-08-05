# Configuration

[Documentation home](README.md)

SLS-LITE generates `plugins/sls-lite/config.yml`. The bundled, commented
[`config.yml`](../src/main/resources/defaults/host/config.yml) is the canonical
default.
Unknown structural keys are rejected. Host configuration changes require a
Velocity restart; `/sls reload` reloads blueprints and software profiles only.
This restart boundary applies to every `config.yml` key because each one owns
or sizes a long-lived service, executor, listener, security decision, storage
adapter, or recovery controller. `/sls reload config` performs no partial
mutation and names the restart requirement.

The generated file is arranged in stable operator-facing sections and retains
comments for units, defaults, security implications, and restart behavior.
Section order is for readability; YAML key order does not change behavior.
Validation errors identify the source file and the complete known YAML path,
and unknown keys include a nearest-key suggestion when one is unambiguous.

The generated values are portable safety baselines, not automatic host sizing.
SLS-LITE cannot reliably infer how much of a panel/container limit must remain
for Velocity, JVM native memory, the operating environment, and unrelated
services. Operators must review memory, process, and port budgets for the real
allocation. Repository test fixtures keep their larger budgets, shorter
lifecycle timers, verbose diagnostics, and offline-security exceptions in
fixture scripts rather than in these product defaults.

## Reference

| Key | Default | Valid values and behavior |
| --- | --- | --- |
| `resources.total_memory_mib` | `2048` | Portable starting admission budget for managed children, not host detection. Excludes Velocity and does not measure the panel limit; operators must size it from the real allocation. |
| `resources.max_managed_processes` | `20` | Positive process count no greater than the configured port count. When omitted manually, it follows that port count. SLS-Limbo consumes one slot. |
| `network.ports.start` | `25570` | Integer `1024..65535`; first managed loopback port. |
| `network.ports.end` | `25589` | Integer from `start..65535`; last managed loopback port. The default range contains 20 slots. |
| `matchmaking.queue_timeout_seconds` | `180` | Positive host-default queue lifetime in seconds. A blueprint may override it or select explicit no-expiry with `sls-lite.queue-timeout-seconds`. |
| `matchmaking.blueprint_selection` | `first-available` | `first-available` prefers the requested blueprint and then stable ID order; `random` uniformly selects from eligible pool definitions. Existing ready instances with capacity are always preferred before either provisioning policy. |
| `lifecycle.idle_shutdown_seconds` | `180` | Non-negative seconds. `0` disables global idle cleanup. |
| `compatibility.viaversion_backend_sync` | `auto` | `auto` integrates with ViaVersion's dynamic-backend protocol map when ViaVersion is installed and is the recommended standard; `on` makes a compatible ViaVersion installation mandatory at startup; `off` prevents SLS-LITE from inspecting or changing that map. |
| `storage.strategy` | `auto` | `auto`, `copy`, `reflink`, `btrfs`, `overlay`, `fuse-overlay`, or `snapshot-hook`. `auto` uses reflink, eligible Btrfs snapshots, kernel OverlayFS, or fuse-overlayfs after a successful per-path isolation probe and otherwise uses portable copy. Explicitly requesting an unavailable strategy fails startup. |
| `storage.auto_priority` | `5 entries` | Authoritative non-empty ordered allowlist containing unique `reflink`, `btrfs`, `overlay`, `fuse-overlay`, and/or `copy` entries. Applies only to `strategy: auto`; omitted strategies are neither probed nor selected. |
| `storage.copy_parallelism` | `auto` | `auto` uses the smaller of four workers and the JVM's available processor count; an explicit integer from `1..16` selects the bounded worker count. |
| `storage.snapshot_hook.executable` | unset | Required only for `snapshot-hook`; relative executable below the SLS-LITE data directory. Never auto-discovered. |
| `storage.snapshot_hook.timeout_seconds` | `30` | Per-operation helper timeout from 1 through 300 seconds. |
| `managed_output.mirror_to_proxy_console` | `false` | Mirror every child output line into the Velocity console. |
| `managed_output.write_temporary_file` | `true` | Write bounded `logs/sls-lite-console.log` inside each instance. |
| `managed_output.temporary_file_max_kib` | `2048` | Per-instance hard limit, `1..1048576` KiB. No rotation archives. |
| `detailed_logging.level` | `normal` | `off`, `normal`, or `detailed`. Controls SLS-LITE's own bounded detail file independently from child output and concise console messages. |
| `detailed_logging.mirror_to_proxy_console` | `false` | Mirror detail records to Velocity's console. This never disables milestones, warnings, failures, or command responses. |
| `detailed_logging.max_file_kib` | `4096` | Active detail-file limit, `64..1048576` KiB. |
| `detailed_logging.retained_files` | `3` | `1..32`; includes the active file. Older rotations are replaced. |
| `detailed_logging.queue_capacity` | `1024` | Bounded asynchronous queue, `128..65536`. Overflow drops detail rather than blocking lifecycle threads and emits sparse warnings. |
| `detailed_logging.redact_paths` | `true` | Redact known roots and absolute path-shaped values. Credential redaction is mandatory regardless of this option. |
| `diagnostics.console_tail_lines` | `1000` | In-memory retained child-output lines per instance, `0..10000`; `0` disables the tail without disabling persistent instance logs. |
| `diagnostics.installer_history_entries` | `100` | Completed in-memory software-installation records, `0..1000`; active installation ownership is retained independently. |
| `diagnostics.failure_reports` | `64` | Recent in-memory instance-failure events exposed in diagnostics, `0..1000`; event delivery and persistent detail logs remain active at `0`. |
| `forwarding.mode` | `none` | `none` or `modern`. |
| `forwarding.online_mode` | `true` | Must match Velocity online mode when forwarding is `modern`. |
| `forwarding.secret_file` | `forwarding.secret` | Non-blank relative path from the Velocity working directory. |
| `security.allow_insecure_offline_administrators` | `false` | Permit UUID-based built-in admins on an offline proxy. Unsafe for public use. |
| `security.claim_code_expiry_seconds` | `600` | Positive one-time administrator-code lifetime. |
| `presentation.transfer_action_bar.enabled` | `true` | Enables built-in preparation, joining, force-joining, and dequeue feedback. Disable it to leave the action bar entirely to an extension. |
| `presentation.transfer_action_bar.joining` | `<green>Joining <server>` | Bounded MiniMessage template; `<server>` is inserted as unparsed text. |
| `presentation.transfer_action_bar.force_joining` | `<yellow>Force joining <server>` | Bounded MiniMessage template for forced transfers. |
| `presentation.transfer_action_bar.dequeued` | `<red>You have been dequeued.` | Bounded MiniMessage dequeue template. |
| `presentation.transfer_action_bar.frames` | `15 entries` | The canonical SLS animation; accepts `1..32` bounded MiniMessage frames. |
| `presentation.transfer_action_bar.frame_interval_millis` | `72` | Animation interval from `25..2000` milliseconds. |
| `lobby.mode` | `velocity` | `velocity`, `external`, or `managed`. `velocity` preserves native `try` and forced-host routing. |
| `lobby.auto_start` | `true` | Managed mode only. `false` prevents preparation, resume, and recovery of the primary; enabled SLS-Limbo handles lobby routing until the option is restored and Velocity restarts. |
| `lobby.registry` | `lobby` | Managed-lobby blueprint type. Ignored by velocity and external modes. |
| `lobby.server` | `lobby` | External Velocity server name or managed blueprint ID; ignored by velocity mode. |
| `lobby.limbo.enabled` | `true` | Start bundled SLS-Limbo. |
| `lobby.limbo.memory_mib` | `96` | At least `64` MiB; included in managed admission. |
| `lobby.limbo.startup_timeout_seconds` | `30` | Positive readiness timeout. |
| `lobby.limbo.advertised_protocol` | `-1` | `-1` for native behavior, or a tested protocol at least `770`. ViaVersion sees native mode as the safe `770` integration baseline because its detector cannot represent the `-1` sentinel. |
| `lobby.limbo.presentation.dimension` | `THE_END` | `OVERWORLD`, `NETHER`, or `THE_END`; all are supported by the pinned NanoLimbo runtime. |
| `lobby.limbo.presentation.ping.enabled` | `true` | When false, emits an empty ping description without changing the required protocol response. |
| `lobby.limbo.presentation.ping.description` | `<gold>SLS-Limbo` | Bounded MiniMessage server-list description. |
| `lobby.limbo.presentation.ping.version` | `<gold>SLS-LITE` | Bounded MiniMessage server-list version label. |
| `lobby.limbo.presentation.player_list.enabled` | `false` | Enables NanoLimbo's virtual player-list sample. |
| `lobby.limbo.presentation.player_list.username` | `SLS-LITE` | Non-blank virtual sample username, at most 64 characters. |
| `lobby.limbo.presentation.brand.enabled` | `true` | Enables the client brand response. |
| `lobby.limbo.presentation.brand.text` | `<gold>SLS-Limbo` | Bounded MiniMessage brand text. |
| `lobby.limbo.presentation.join_message.enabled` | `true` | Enables the message sent when a player enters SLS-Limbo. |
| `lobby.limbo.presentation.join_message.text` | `<yellow>You are in SLS-Limbo while your destination gets ready.` | Bounded MiniMessage join text. |
| `lobby.limbo.presentation.boss_bar.enabled` | `true` | Enables the holding boss bar. |
| `lobby.limbo.presentation.boss_bar.text` | `<yellow>Waiting for your destination...` | Bounded MiniMessage boss-bar text. |
| `lobby.limbo.presentation.boss_bar.health` | `1.0` | Finite value from `0.0..1.0`. |
| `lobby.limbo.presentation.boss_bar.color` | `YELLOW` | NanoLimbo-supported boss-bar color. |
| `lobby.limbo.presentation.boss_bar.division` | `SOLID` | `SOLID`, `NOTCHED_6`, `NOTCHED_10`, `NOTCHED_12`, or `NOTCHED_20`. |
| `lobby.limbo.presentation.title.enabled` | `true` | Enables the entry title and subtitle. |
| `lobby.limbo.presentation.title.title` | `<gold>SLS-LITE` | Bounded MiniMessage title. |
| `lobby.limbo.presentation.title.subtitle` | `<yellow>SLS-Limbo` | Bounded MiniMessage subtitle. |
| `lobby.limbo.presentation.title.fade_in_ticks` | `10` | Non-negative tick count through `12000`. |
| `lobby.limbo.presentation.title.stay_ticks` | `100` | Non-negative tick count through `12000`. |
| `lobby.limbo.presentation.title.fade_out_ticks` | `10` | Non-negative tick count through `12000`. |
| `lobby.limbo.presentation.header_footer.enabled` | `false` | Enables the virtual tab-list header and footer. |
| `lobby.limbo.presentation.header_footer.header` | `empty` | Bounded MiniMessage header; empty is allowed. |
| `lobby.limbo.presentation.header_footer.footer` | `empty` | Bounded MiniMessage footer; empty is allowed. |
| `lobby.limbo.recovery.max_attempts` | `5` | Non-negative restart attempts; `0` disables recovery. |
| `lobby.limbo.recovery.initial_backoff_seconds` | `2` | Positive first delay. |
| `lobby.limbo.recovery.max_backoff_seconds` | `30` | At least the initial delay. |
| `lobby.limbo.recovery.stable_after_seconds` | `120` | Positive healthy period before retry-budget reset. |
| `lobby.recovery.max_attempts` | `5` | Managed-primary restart attempts; `0` disables recovery. |
| `lobby.recovery.initial_backoff_seconds` | `5` | Positive first delay. |
| `lobby.recovery.max_backoff_seconds` | `60` | At least the initial delay. |
| `lobby.recovery.stable_after_seconds` | `120` | Positive healthy period before retry-budget reset. |
| `paths.instances` | `instances` | Non-blank relative path below the SLS-LITE data directory. |

For `storage.strategy: auto`, the default priority is reflink, Btrfs snapshot,
kernel OverlayFS, rootless fuse-overlayfs, then portable copy. The configured
`storage.auto_priority` replaces that order rather than extending it. Removing
`copy` forbids portable fallback; startup fails if none of the remaining
allowlisted strategies passes its exact-path capability probe.
`snapshot-hook` is explicit-only and requires its configured helper to pass the
bounded `sls-snapshot-helper-v1` handshake. When Btrfs is selected, eligible `cow`
subvolumes without nested subvolumes use snapshots; other sources retain
portable semantics under `auto`. Detected
capabilities are eligible only when the corresponding strategy is implemented
and its exact storage-path safety probe passes. Kernel OverlayFS is active only
after its exact storage path passes the contained mount probe. fuse-overlayfs
is also active only after
its exact-path mount/isolation/unmount probe succeeds; `/dev/fuse` and the
binary alone are insufficient. Snapshot helpers are active only when explicitly
configured and never participate in `auto`.

## Resource Accounting

SLS-Limbo and managed game servers reserve configured heap values before they
start. A request is rejected when memory, process slots, blueprint instance
limits, or ports are exhausted. Java native memory and Velocity's own memory are
real additional usage and must be included in the panel allocation.

## Lobby Modes

`external` selects a backend already registered with Velocity. SLS-LITE does
not start or stop that server.

`managed` starts `lobby.registry/lobby.server` from the blueprint catalog,
protects it from ordinary stop/restart/reset operations, and applies bounded
crash recovery.

With managed mode and `auto_start: false`, SLS-LITE deliberately leaves the
primary offline and routes through SLS-Limbo. It does not adopt an ordinary
instance started from the same blueprint and does not run primary recovery.
Set `auto_start: true` and restart Velocity to restore managed-primary
ownership. Configuration validation rejects this mode when SLS-Limbo is
disabled because that would intentionally start with no safe lobby.

SLS-Limbo is a fallback for either mode, not a third primary mode. Normal queued
players stay on their current healthy backend.

## Validation And Reload

Startup fails managed initialization when required host settings are unsafe or
internally inconsistent. Velocity itself remains available for console
diagnosis where possible.

```text
/sls reload blueprints
/sls reload software
/sls reload all
```

Reload examines every blueprint file. Invalid definitions are omitted while
valid siblings are resolved together and published as one catalog revision;
running instances keep the immutable definitions with which they were created.
The command reports accepted/rejected counts plus added, updated, and removed
IDs. Every rejected relative path and exact error is written to the SLS-LITE
detail log. A software-profile, catalog I/O, or global transaction failure still
rejects the transaction rather than publishing a structurally inconsistent
catalog. Reload also verifies SLS-LITE-owned dynamic Velocity registrations,
restores missing owned entries, and leaves same-name foreign conflicts untouched.
Host configuration, storage strategy, output and detailed-log policy,
forwarding, lobby mode, ports, memory, and security require a Velocity restart.
