# SLS-LITE 0.1.0-rc.2.2

This compatibility follow-up aligns omitted blueprint capacity with current
full-SLS/vSLS behavior and begins the planned shared blueprint-shape migration.
The implementation is otherwise frozen; RC.3 feature and architecture work
remains out of scope for this candidate.

## Blueprint Capacity

- A blueprint that omits player capacity now defaults to `10000` public player
  slots per instance.
- A blueprint that omits its instance cap now uses `2147483647`, displayed to
  operators as `unlimited`. Configured host memory, managed-process, and port
  budgets still limit actual local concurrency.
- Explicit finite capacity values remain unchanged.
- Established `annotations.vsls.matchmaking.maxPlayers` and
  `annotations.vsls.max-instances` remain supported as upstream-compatible
  fallbacks when the corresponding SLS-LITE settings are omitted.

## Breaking Blueprint Migration

The SLS-LITE public player cap moved from `server.limits.max_players` to
`annotations.sls-lite.max-players`:

```yaml
server:
  limits:
    memory_limit: 1536
    max_instances: 1

annotations:
  sls-lite:
    max-players: 100
```

The removed field is not accepted or silently translated. Blueprint reload
reports an actionable error naming the replacement. Zero, negative,
fractional, textual, overflowing, and malformed namespace input is rejected
with the exact annotation path.

`server.limits.max_instances` intentionally remains in the general limits
section. The internal SLS-LITE annotation reader is now shared by capacity,
lifecycle, queue, crash-recovery, and process-timeout policies so namespace and
primitive validation remain consistent.

## Upgrade From RC.2.1

1. Stop Velocity normally and confirm its managed child processes have exited.
2. Back up the complete `plugins/sls-lite/` directory and installed plugin JAR
   as one matching restore set.
3. Before startup, replace every `server.limits.max_players` declaration with
   `annotations.sls-lite.max-players`. Preserve the same positive integer value.
4. Replace only the plugin JAR with `0.1.0-rc.2.2`; do not use a plugin
   hot-reloader. No `config.yml` rewrite is required.
5. Start Velocity and review the startup checklist, `/sls system`, blueprint
   `action needed` entries, and the detailed log.
6. For `save: true` blueprints whose annotation tree changed during this
   migration, review the reported definition drift and use the normal reset
   path before reuse. Reset reconstructs the instance from its current
   blueprint sources; back up operator-owned state first.
7. Verify a finite-cap blueprint and an omitted-cap blueprint through reload,
   creation or matchmaking, direct join, restart, and a full proxy restart.

Operators upgrading directly from RC.2 should also read the RC.2.1 lifecycle
and malformed-blueprint fixes in the repository history. If RC.2 persistent
file state has been used, do not attempt an in-place downgrade: stop the proxy
and restore the matching data-directory and JAR backup instead.

## Compatibility

- The SLS-LITE Java API remains compatible with the published RC.2 API
  contracts; this candidate adds no extension API surface.
- The full-SLS compatibility baseline continues to follow upstream `main`
  without embedding a release or commit pin in operator documentation.
- Java 21 plugin bytecode remains supported on the tested Java 25 Velocity
  runtime. Existing host, storage, forwarding, and protocol boundaries are
  unchanged.

## Known Boundaries

- SLS-LITE manages one host allocation. It does not reproduce full-SLS nodes,
  containers, HTTP control plane, or distributed resource enforcement.
- Host permissions determine which storage strategies and child-process
  features are available. Portable copy remains the universal fallback unless
  the operator excludes it.
- Existing configuration, software profiles, blueprints, volume sources, and
  saved instances remain operator-owned. SLS-LITE reports ambiguous or
  incompatible state instead of deleting or merging it automatically.
- Cleanup of every unreachable directory after all possible failed or
  cancelled start phases remains assigned to RC.3 because it requires broader
  lifecycle and reconciliation work.

Use the current [installation guide](DOCS/Getting_Started.md),
[blueprint reference](DOCS/Blueprints.md),
[compatibility matrix](DOCS/Compatibility.md), and
[troubleshooting guide](DOCS/Troubleshooting.md). Report candidate issues with
the SLS-LITE version, host capability summary, relevant detailed-log excerpt,
and a minimal redacted configuration or blueprint.
