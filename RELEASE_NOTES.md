# SLS-LITE 0.1.0-rc.2

This second release candidate turns the first external-testing feedback into a
safer, clearer operator experience. It remains intended for controlled
single-host Velocity networks while the release-candidate testing period
continues.

## Highlights Since RC.1

- Non-destructive configuration generation 2 diagnostics. Existing
  `config.yml` files remain operator-owned and are never rewritten; omitted new
  options receive safe defaults and the startup checklist points to the exact
  current reference.
- Optional host-wide `software.auto_accept_eula`, disabled by default, alongside
  the existing per-software-profile EULA choice.
- A focused first-run checklist, production and isolated-development forwarding
  walkthroughs, blueprint readiness diagnostics, a copyable canonical config,
  and a visual blueprint recipe book.
- Bounded `state.persistent_files` mappings for root files such as Paper
  whitelist, operator, ban, and icon files. They use single-writer ownership,
  atomic write-back, backups, conflict detection, and crash reconciliation
  without privileged mounts or symbolic links.
- Discoverable live output through `/sls logs <server|this> --follow` and
  `/sls logs --unfollow`, with bounded delivery and compatibility aliases for
  the older console forms.
- vSLS-compatible administrator force joining with backend-safe capacity
  headroom, plus live managed-instance memory, CPU, player, and lifecycle
  metrics in the `/sls debug` action bar.
- Correct modern forwarding across old and current Paper configuration layouts,
  authoritative generated SLS-Limbo settings, and protected fallback/automatic
  lobby return during owned lobby outages.
- Player-visible preparation phases for slow installs and starts, plus clearer
  restart-versus-reset guidance when definitions change.
- Additive Java extension API 1.2 with readiness and diagnostic contributions,
  graceful administrative operations, maintenance controls, and exact-instance
  routing. API 1.0 and 1.1 consumer compatibility remains covered.
- Clean Paper installations now use a 60-second graceful shutdown allowance.
  Existing generated software profiles remain operator-owned and are not
  silently changed.

## Compatibility Baseline

- Java 21 plugin bytecode on the tested Java 25 Velocity runtime.
- Exact Velocity 4.1.0 API snapshot pinned in `pom.xml`; the exercised local
  proxy runtime is recorded in `DOCS/Compatibility.md`.
- Stable Minecraft/Paper through 26.2 where the documented provider, Java,
  plugin, forwarding, and real-client requirements are satisfied.
- With compatible ViaVersion installed, Minecraft 26.2 is the forward-client
  minimum rather than a maximum. Newer clients are allowed when that installed
  ViaVersion build reports support for them; this does not qualify their worlds,
  plugins, or gameplay behavior.
- NanoLimbo 1.13.0 at revision
  `d192d57d1d4a5fdc7b87643f453d82cb7b9b4242` for SLS-Limbo.

Minecraft 26.3 is still an upstream snapshot line and is not release-qualified.
Snapshots may be tested only with a compatible ViaVersion build and disposable
data.

## Upgrade From RC.1

1. Stop Velocity normally and confirm managed children have exited.
2. Back up the complete `plugins/sls-lite/` directory and the installed RC.1
   plugin JAR as one matching restore set.
3. Read [Migration](DOCS/Migration.md), replace only the plugin JAR, and start
   Velocity normally. Do not use a plugin hot-reloader.
4. Review the compact setup/migration diagnostics, `/sls system`, and the detail
   log. An unversioned RC.1 configuration is treated as generation 1; SLS-LITE
   does not rewrite it or create a duplicate reference file.
5. Compare operator-owned configuration and software profiles with the current
   documented defaults, then deliberately add or acknowledge wanted changes.
6. Run a representative start, join, restart, and stop. Adding or changing
   `state.persistent_files` on an existing persistent instance requires an
   explicit reset; ordinary restart correctly rejects definition drift.

If RC.2-only state has been used, do not attempt an in-place downgrade. Stop the
proxy and restore the complete matching RC.1 data-directory and JAR backup.

## Known Boundaries

- SLS-LITE manages one host allocation. It does not reproduce full SLS nodes,
  containers, HTTP control plane, or distributed resource enforcement.
- Host permissions determine which storage strategies and child-process
  features are available. Portable copy remains the universal fallback unless
  the operator excludes it.
- Vanilla backends cannot use modern Velocity forwarding.
- Server software or ViaVersion protocol availability does not guarantee
  compatibility of arbitrary worlds, plugins, Java versions, or game behavior.
- Existing configuration, software profiles, blueprints, and volume sources are
  operator-owned. Release upgrades report required changes instead of silently
  normalizing custom or extension-owned fields.

Use the current [installation guide](DOCS/Getting_Started.md),
[migration guide](DOCS/Migration.md), [compatibility matrix](DOCS/Compatibility.md),
and [troubleshooting guide](DOCS/Troubleshooting.md). Report candidate issues
with the SLS-LITE version, host capability summary, relevant detailed-log
excerpt, and a minimal redacted configuration or blueprint.
