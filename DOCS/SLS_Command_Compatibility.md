# vSLS Command Compatibility

SLS-LITE mirrors the vSLS in-game command interface so operators and players can
move between the two products without relearning command names or argument
order. The compatibility target is:

- SLS `v0.2.0`
- Commit `8e8b1e3cf7d2157887764c16f11b8901f8241121`
- [vSLS command reference](https://protoxon.github.io/sls-docs/guide/vsls/commands.html)

The upstream command and permission behavior is the default. SLS-LITE may add
local-only aliases or options, but they must not replace or change an upstream
form. Distributed operations that cannot work locally must retain their command
shape and return a concise `not available in local mode` explanation.

SLS-LITE also adds `/sls admin` as a local-only bootstrap command. A first-time
operator claims administration with the short-lived code printed to the proxy
console, then manages the built-in administrator role by online player name.
UUIDs are retained internally so name changes do not remove access. Existing
Velocity permission providers remain additive and `sls.command.admin` keeps its
upstream umbrella behavior.

## Compatibility Status

| vSLS command | Permission | SLS-LITE status | Remaining compatibility work |
| --- | --- | --- | --- |
| `info [server]` | Details: `sls.command.admin` | Adapted | Summary and local instance details include players, lifecycle, process, resource, queue, log, and directory information. |
| `list` | Public | Adapted | vSLS layout, status colors, counts, and hover information matched. |
| `create <type> <id> [flags...]` | Admin | Planned | Add safe local equivalents for supported override flags. |
| `start <type> <id>` | Admin | Adapted | Preserve saved-instance semantics when persistence lands. |
| `join <type> <id> [target]` | Self public; others admin | Adapted | Capacity-aware allocation is supported; admin `/sls join player <player> --force` can bypass a full target instance. |
| `find <player>` | Public | Supported | vSLS messages, hover details, and action-bar feedback matched. |
| `system` | Admin | Adapted | Reports local runtime, JVM memory, managed memory allocation, supervised process usage and limit, CPU threads, Java, and OS details; capability probes remain planned. |
| `node <id> [drained [value]]` | Admin | Local-mode response planned | Node administration is distributed-only. |
| `console <server> <command>` | Admin | Adapted | Safe local process input is supported; add bounded in-game output capture. |
| `blueprint <id>` | Admin | Planned | Pretty-print one blueprint; keep `blueprints` as an additive alias. |
| `debug` | Public, player-only | Planned | Add per-player local lifecycle diagnostics. |
| `delete <server\|all>` | Admin | Planned | Define safe persistent and ephemeral deletion behavior. |
| `logs <server> [page] [lines]` | Admin | Adapted | vSLS pagination is backed by a bounded 1,000-line local process-output buffer. |
| `reload [all\|config\|blueprints\|software]` | Admin | Adapted | Blueprint/software candidates are cross-validated and installed as one immutable catalog revision. Add upstream `config` mode only when host-wide services can be rebuilt safely; until then `config.yml` requires a Velocity restart. |
| `stop <server\|all> [force]` | Admin | Adapted | Local servers stop gracefully after evacuation. `/sls stop <server> --force` bypasses managed-lobby protection, requires `sls.command.stop.force` or the umbrella admin permission, diverts new arrivals and evacuates connected players to SLS-Limbo, restores primary routing if evacuation fails, suppresses recovery after a successful drain, and writes an audit log. Add `all` and the upstream non-dashed alias only after their exact behavior is confirmed. |
| `kill <server\|all> [force]` | Admin | Planned | Add explicit force-termination behavior. |
| `dequeue [all\|local\|player]` | Self public; others admin | Adapted | Match final vSLS feedback and queue context. |
| `status <server> [remote]` | Admin | Adapted | Local status output matched; add the `remote` local-mode response. |
| `stats [server]` | Admin | Adapted | Reports lifecycle state, process CPU time, configured memory, uptime, and retained logs; Java cannot portably measure child memory, network, or disk usage. |
| `version` | Public | Adapted | vSLS label, emphasis, author metadata, and colors matched. |
| `pause <server>` | Admin | Compatibility response | Local process suspension is not implemented yet. |
| `resume <server>` | Admin | Compatibility response | Local process resumption is not implemented yet. |
| `restart <server> [--force]` | Admin | Adapted | Evacuates an active persistent server and restarts the same ID and directory; stopped instances can be recovered after a proxy restart. `--force` requires `sls.command.restart.force` and lets an administrator drain the protected managed lobby to SLS-Limbo, restart it through the lobby provider, restore primary routing only after readiness, and automatically return every player holding in SLS-Limbo. |
| `reset <server> [--force]` | Admin | Adapted | Uses rollback-protected template restoration, retains the same ID, and starts the reset server. `--force` requires `sls.command.reset.force` and applies the same protected-lobby drain, ownership, and automatic handoff rules before restoring its template. |
| `install <info\|logs>` | Admin | Adapted | `/sls install info` lists local installation state and `/sls install logs <software> <version>` shows the bounded provider log. Start and join requests trigger missing provider-backed software automatically. |

## Presentation Contract

The implemented command output follows the pinned vSLS component structure:

- Blue gradient `[SLS]` prefix with project hover information.
- Dark-aqua labels, gray values, gold/yellow composite instance IDs, and
  lifecycle-aware status colors.
- vSLS usage grammar: `Usage: /sls <option | option>`.
- Server, player, and blueprint hover details, list framing, and player-facing
  action-bar feedback. Blueprint rows expose registry, software version,
  capacity, instance limit, persistence, active instances, and mounted volumes;
  clicking a row suggests its join command.
- vSLS wording is retained verbatim where the behavior is equivalent.
  `SLS-LITE` replaces `vSLS` branding, and daemon-only metrics or actions are
  replaced with truthful local equivalents.

The source contract is encoded in `VSLSCommandContract` and tested against the
pinned release and commit. Commands advertised by the upstream root tree but
not implemented in SLS-LITE return a styled compatibility response instead of
falling through as an unknown command.

## Compatibility Rules

1. Keep the `/sls` root and upstream top-level names stable.
2. Match upstream argument order, literals, case sensitivity, sender
   restrictions, and tab completion.
3. Preserve `sls.command.admin` as the umbrella administrative permission.
4. Keep self-service operations public where vSLS does.
5. Require admin permission for `all`, `local`, another player, force actions,
   destructive actions, and server administration.
6. Test the complete tree against a versioned command fixture before release.
7. Review the upstream command implementation and documentation whenever the
   pinned SLS target changes.

SLS-LITE granular lifecycle permissions are additive:

- `sls.command.stop` permits normal graceful stops.
- `sls.command.stop.force` permits the protected managed-lobby override.
- `sls.command.restart` and `sls.command.reset` permit ordinary persistent
  cycles.
- `sls.command.restart.force` and `sls.command.reset.force` permit protected
  managed-lobby cycles.
- `sls.command.admin` and built-in SLS-LITE administrators permit all of these
  operations.
