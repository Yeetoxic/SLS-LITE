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
| `create <type> <id> [flags...]` | Admin | Adapted | Provisions and starts a fresh local instance with dedicated permission and completion behavior. `--memory`, `--save`, `--seed`, `--view-distance`, and `--enable-command-block` are validated and persisted across restart/reset. Every pinned daemon/container-only flag is recognized and explicitly rejected: `--node`, `--cpu`, `--swap`, `--io_weight`, `--disk_space`, `--threads`, `--oom_disabled`, `--software`, `--version`, `--image`, and `--env`. |
| `start <type> <id>` | Admin | Adapted | Starts local ephemeral or persistent instances within blueprint and host limits. |
| `join <type> <id> [target]` | Self public; others admin | Adapted | Capacity-aware allocation is supported; admin `/sls join player <player> --force` can bypass a full target instance. |
| `find <player>` | Public | Supported | vSLS messages, hover details, and action-bar feedback matched. |
| `system` | Admin | Adapted | Reports local runtime, JVM memory, managed memory allocation, supervised process usage and limit, CPU threads, Java, OS, lobby state, output policy, and startup capability probes. |
| `node <id> [drained [value]]` | Admin | Local-mode response planned | Node administration is distributed-only. |
| `console <server> <command>` | Admin | Adapted | Safe local process input is supported; add bounded in-game output capture. |
| `blueprint <id>` | Admin | Supported | Pretty-prints one globally unique blueprint ID with its local launch/storage details. The additive `blueprints [registry]` form remains available for catalog browsing. |
| `debug` | Admin, player-only | Adapted | Matches the pinned player-only toggle and gray `Debug mode enabled.` / `Debug mode disabled.` feedback under `sls.command.debug` or umbrella administration. The local opt-in stream emits bounded timestamp-hovered command-dispatch context only; arguments and child-console content are excluded, and subscriptions are removed on disconnect or shutdown. |
| `delete <server\|all>` | Admin | Adapted | Exact IDs and the additive `this` selector evacuate active players, stop cleanly, verify SLS-LITE metadata ownership, atomically rename storage to a delete tombstone, and remove it with mount/snapshot-aware cleanup. Interrupted cleanup is retried during startup reconciliation. `all` processes ordinary instances sequentially with per-server results and always skips the protected managed lobby. |
| `logs <server> [page] [lines]` | Admin | Adapted | vSLS pagination is backed by a bounded 1,000-line local process-output buffer. |
| `reload [all\|config\|blueprints\|software]` | Admin | Adapted | Blueprint/software candidates are cross-validated and installed as one immutable catalog revision. `config` is retained as an explicit response directing the operator to restart Velocity because host-wide services cannot be rebuilt safely in place. |
| `stop [server\|all] [force]` | Admin | Adapted | Players may omit the target for their current server. Local servers stop gracefully after evacuation. `all` processes ordinary targets sequentially, skips the managed lobby by default, and includes it last only with force permission. Both pinned `force` and additive `--force` are accepted. On ordinary servers the modifier is a safe compatibility no-op because routing is already removed before graceful shutdown and ownership remains until verified exit. On the managed lobby it requires `sls.command.stop.force`, diverts new arrivals, evacuates players to SLS-Limbo, restores primary routing if evacuation fails, suppresses recovery after a successful drain, and writes an audit log. |
| `kill <server\|all> [force]` | Admin | Adapted | Matches the pinned immediate, non-graceful termination and gray `Killed <id>` feedback, plus player-current selection. Players are evacuated first, ordinary `all` targets are processed sequentially, persistent storage is preserved, and ephemeral storage is removed only through normal owned cleanup. The upstream `force` modifier (plus additive `--force`) requests backend unregistration if the termination request fails, but never releases process/resource ownership while the child may still be alive. A protected managed lobby is skipped unless `force` is present and the sender also has `sls.command.kill.force`; it is drained to SLS-Limbo and processed after ordinary targets. |
| `dequeue [all\|local\|player]` | Self public; others admin | Supported | Preserves pinned self, target, local, and all feedback; selector failures do not emit a false success message. |
| `status [server] [remote]` | Admin | Adapted | Players may omit the target for their current server. Local status output matched. `remote` is completed as an explicit response explaining that no daemon exists and the supervised local process state is authoritative. |
| `stats [server]` | Admin | Adapted | Reports lifecycle state, CPU time, configured memory, current Linux RSS and process I/O where `/proc` is accessible, uptime, and retained logs. Shared-namespace network use and synchronous recursive disk use remain explicitly unavailable. |
| `version` | Public | Adapted | vSLS label, emphasis, author metadata, and colors matched. |
| `pause <server>` | Admin | Compatibility response | Local process suspension is not implemented yet. |
| `resume <server>` | Admin | Compatibility response | Local process resumption is not implemented yet. |
| `restart [server] [--force]` | Admin | Adapted | Players may omit the target for their current server. Active persistent servers are evacuated and restarted with the same ID and directory; stopped instances can be recovered after a proxy restart. `--force` requires `sls.command.restart.force` and is suggested only for the protected managed lobby, where it drains to SLS-Limbo, restarts through the lobby provider, restores routing after readiness, and returns holding players. |
| `reset [server] [--force]` | Admin | Adapted | Players may omit the target for their current server. Reset uses rollback-protected template restoration, retains the same ID, and starts the server. `--force` requires `sls.command.reset.force`, is suggested only for the protected lobby, and applies the same drain, ownership, and handoff rules. |
| `install <info\|logs>` | Admin | Adapted | `/sls install info` lists local installation state and `/sls install logs <software> <version>` shows the bounded provider log. Start and join requests trigger missing provider-backed software automatically. |

## Presentation Contract

The implemented command output follows the pinned vSLS component structure:

- Blue gradient `[SLS]` prefix. SLS-LITE intentionally omits vSLS's repeated
  project/author hover from this prefix; command-specific player, server, and
  blueprint hovers remain available where they convey operational details.
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

Those responses also name the safe local workflow. `/sls node` directs
operators to `/sls system` and local lifecycle commands because no daemon/node
control plane exists. Pause explains that portable process suspension is
unsafe and recommends leaving the instance running or stopping a persistent
instance; resume directs operators to restart a stopped persistent instance.

## Versioned Branch Registry

`VSLSCommandContract.BRANCHES` is the machine-readable inventory for the whole
implemented command tree. Each semantic argument branch records:

- its stable ID and displayed syntax;
- whether it is pinned upstream or additive in SLS-LITE;
- whether it is supported, locally adapted, or an explicit unavailable
  response;
- its public, administrative, self/other, bootstrap, or built-in access model;
- player-only, console-only, or unrestricted sender rules;
- every relevant granular permission node, literal selector, modifier, and
  argument-completion source.

The registry also owns the runtime's public and permission-filtered root
completion lists. `VSLSCommandContractTest` rejects duplicate branches,
unregistered runtime roots, missing administrative permission declarations,
sender-rule drift, create-modifier omissions, force spelling drift, and loss of
the intentional `node`, `pause`, or `resume` compatibility responses. Runtime
tests exercise public and granular permissions, built-in administrators,
console/player restrictions, other-player access, force permissions, invalid
usage, and hidden suggestions.

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

- `sls.command.create` permits fresh local provisioning and the supported,
  instance-confined override subset.
- `sls.command.stop` permits normal graceful stops.
- `sls.command.stop.force` permits the protected managed-lobby override.
- `sls.command.restart` and `sls.command.reset` permit ordinary persistent
  cycles.
- `sls.command.restart.force` and `sls.command.reset.force` permit protected
  managed-lobby cycles.
- `sls.command.admin` and built-in SLS-LITE administrators permit all of these
  operations.
