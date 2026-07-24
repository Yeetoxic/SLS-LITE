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

## Compatibility Status

| vSLS command | Permission | SLS-LITE status | Remaining compatibility work |
| --- | --- | --- | --- |
| `info [server]` | Details: `sls.command.admin` | Adapted | Match active-server list and resource/player details. |
| `list` | Public | Adapted | Match vSLS display and hover information. |
| `create <type> <id> [flags...]` | Admin | Planned | Add safe local equivalents for supported override flags. |
| `start <type> <id>` | Admin | Adapted | Preserve saved-instance semantics when persistence lands. |
| `join <type> <id> [target]` | Self public; others admin | Adapted | Add capacity enforcement and the full-server Join Anyway interaction. |
| `find <player>` | Public | Supported | Match vSLS messages and display details. |
| `system` | Admin | Planned | Report the local Velocity host and SLS-LITE version. |
| `node <id> [drained [value]]` | Admin | Local-mode response planned | Node administration is distributed-only. |
| `console <server> <command>` | Admin | Planned | Write safely to the supervised child process input. |
| `blueprint <id>` | Admin | Planned | Pretty-print one blueprint; keep `blueprints` as an additive alias. |
| `debug` | Public, player-only | Planned | Add per-player local lifecycle diagnostics. |
| `delete <server\|all>` | Admin | Planned | Define safe persistent and ephemeral deletion behavior. |
| `logs <server> [lines]` | Admin | Planned | Read bounded recent local process logs. |
| `reload [all\|config\|blueprints\|software]` | Admin | Adapted | Add the upstream `config` mode. |
| `stop <server\|all> [force]` | Admin | Adapted | Add `all` and compatible `force` behavior. |
| `kill <server\|all> [force]` | Admin | Planned | Add explicit force-termination behavior. |
| `dequeue [all\|local\|player]` | Self public; others admin | Adapted | Match final vSLS feedback and queue context. |
| `status <server> [remote]` | Admin | Adapted | Return a local-mode explanation for `remote`. |
| `stats <server>` | Admin | Planned | Add locally measurable CPU, memory, disk, and uptime values. |
| `version` | Public | Adapted | Match vSLS author metadata formatting where practical. |

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
