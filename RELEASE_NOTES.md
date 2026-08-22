# SLS-LITE 0.1.0-rc.2.1

This temporary hotfix candidate addresses release-blocking lifecycle and
diagnostic problems reported against RC.2. It is intentionally narrow; the
planned RC.3 feature work remains deferred.

## Fixes

- Saved instances are no longer silently replaced with newly generated IDs.
  Start and creation paths identify an existing retained instance and require
  an explicit restart, reset, or deletion instead of leaving duplicate storage.
- Matchmaking and the managed lobby resume their single retained persistent
  instance after a full proxy restart. If legacy storage contains multiple
  retained copies for one blueprint, startup refuses to choose arbitrarily.
- Startup reconciliation quarantines ambiguous persistent copies without
  publishing any copy's managed files. It names the conflicting instance IDs so
  an operator can preserve the wanted copy and remove the others safely.
- Reset and replacement cleanup retain the exact persistent instance identity,
  preserve unresolved file-conflict candidates, and avoid accumulating stale
  directories after interrupted or failed lifecycle operations.
- Malformed blueprints now appear in the normal `action needed` workflow at
  startup and after reload. `/sls blueprint <rejected-path>` exposes the exact
  parser or validation failure, including suggestions for recognizable typos
  such as `state.volunes` instead of `state.volumes`.
- `/sls console` is exclusively managed-server console input. Live output is
  available only through `/sls logs <server|this> --follow` and targetless
  `/sls logs --unfollow`.

## Compatibility and Documentation

- The full-SLS compatibility baseline now follows the upstream `main` branch
  instead of a pinned release or commit. Documentation avoids repeating
  upstream version identifiers that become stale between audits.
- The SLS-LITE Java API remains compatible with the RC.2 API contracts. This
  hotfix does not introduce a new extension API surface or configuration
  generation.
- Java 21 plugin bytecode remains supported on the tested Java 25 Velocity
  runtime. Existing RC.2 host, storage, forwarding, and protocol boundaries are
  unchanged.

## Upgrade From RC.2

1. Stop Velocity normally and confirm its managed child processes have exited.
2. Back up the complete `plugins/sls-lite/` directory and installed RC.2 plugin
   JAR as one matching restore set.
3. Replace only the plugin JAR with `0.1.0-rc.2.1`; do not use a plugin
   hot-reloader. No configuration rewrite is required.
4. Start Velocity and review the startup checklist, `/sls system`, blueprint
   `action needed` entries, and the detailed log.
5. If more than one saved instance is reported for a persistent blueprint,
   preserve a backup and delete only the unwanted copies before starting that
   blueprint. SLS-LITE deliberately will not select one on your behalf.
6. Exercise a representative saved server through start or matchmaking,
   restart, reset where appropriate, and a full proxy restart.

If RC.2 persistent-file state has been used, do not attempt an in-place
downgrade. Stop the proxy and restore the matching data-directory and JAR backup
instead.

## Known Boundaries

- SLS-LITE manages one host allocation. It does not reproduce full SLS nodes,
  containers, HTTP control plane, or distributed resource enforcement.
- Host permissions determine which storage strategies and child-process
  features are available. Portable copy remains the universal fallback unless
  the operator excludes it.
- Existing configuration, software profiles, blueprints, volume sources, and
  saved instances remain operator-owned. The hotfix reports ambiguous state
  instead of deleting or merging it automatically.

Use the current [installation guide](DOCS/Getting_Started.md),
[migration guide](DOCS/Migration.md),
[compatibility matrix](DOCS/Compatibility.md), and
[troubleshooting guide](DOCS/Troubleshooting.md). Report candidate issues with
the SLS-LITE version, host capability summary, relevant detailed-log excerpt,
and a minimal redacted configuration or blueprint.
