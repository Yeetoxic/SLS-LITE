# Configuration

SLS-LITE generates `plugins/sls-lite/config.yml`. The bundled, commented
[`config.yml`](../src/main/resources/config.yml) is the canonical default.
Unknown structural keys are rejected. Host configuration changes require a
Velocity restart; `/sls reload` reloads blueprints and software profiles only.

## Reference

| Key | Default | Valid values and behavior |
| --- | --- | --- |
| `resources.total_memory_mib` | `4096` | Positive MiB admission budget for managed children. Excludes Velocity and does not measure the panel limit. |
| `resources.max_managed_processes` | port count | Positive process count, no greater than the managed port count. SLS-Limbo consumes one slot. |
| `network.ports.start` | `25570` | Integer `1024..65535`; first managed loopback port. |
| `network.ports.end` | `25670` | Integer from `start..65535`; last managed loopback port. |
| `matchmaking.queue_timeout_seconds` | `180` | Positive queue lifetime in seconds. |
| `lifecycle.idle_shutdown_seconds` | `180` | Non-negative seconds. `0` disables global idle cleanup. |
| `managed_output.mirror_to_proxy_console` | `false` | Mirror every child output line into the Velocity console. |
| `managed_output.write_temporary_file` | `true` | Write bounded `logs/sls-lite-console.log` inside each instance. |
| `managed_output.temporary_file_max_kib` | `4096` | Per-instance hard limit, `1..1048576` KiB. No rotation archives. |
| `forwarding.mode` | `none` | `none` or `modern`. |
| `forwarding.online_mode` | `true` | Must match Velocity online mode when forwarding is `modern`. |
| `forwarding.secret_file` | `forwarding.secret` | Non-blank relative path from the Velocity working directory. |
| `security.allow_insecure_offline_administrators` | `false` | Permit UUID-based built-in admins on an offline proxy. Unsafe for public use. |
| `security.claim_code_expiry_seconds` | `600` | Positive one-time administrator-code lifetime. |
| `lobby.mode` | `external` | `external` or `managed`. |
| `lobby.registry` | `lobby` | Managed-lobby blueprint type. Ignored by external mode. |
| `lobby.server` | `lobby` | External Velocity server name or managed blueprint ID. |
| `lobby.limbo.enabled` | `true` | Start bundled SLS-Limbo. |
| `lobby.limbo.memory_mib` | `96` | At least `64` MiB; included in managed admission. |
| `lobby.limbo.startup_timeout_seconds` | `30` | Positive readiness timeout. |
| `lobby.limbo.advertised_protocol` | `-1` | `-1` for native behavior, or a tested protocol at least `770`. |
| `lobby.limbo.recovery.max_attempts` | `5` | Non-negative restart attempts; `0` disables recovery. |
| `lobby.limbo.recovery.initial_backoff_seconds` | `2` | Positive first delay. |
| `lobby.limbo.recovery.max_backoff_seconds` | `30` | At least the initial delay. |
| `lobby.limbo.recovery.stable_after_seconds` | `120` | Positive healthy period before retry-budget reset. |
| `lobby.recovery.max_attempts` | `5` | Managed-primary restart attempts; `0` disables recovery. |
| `lobby.recovery.initial_backoff_seconds` | `5` | Positive first delay. |
| `lobby.recovery.max_backoff_seconds` | `60` | At least the initial delay. |
| `lobby.recovery.stable_after_seconds` | `120` | Positive healthy period before retry-budget reset. |
| `paths.instances` | `instances` | Non-blank relative path below the SLS-LITE data directory. |

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

Reload candidates are parsed and cross-validated before replacing the active
catalog. Running instances keep the definitions with which they were created.
Host configuration, output policy, forwarding, lobby mode, ports, memory, and
security require a Velocity restart.

