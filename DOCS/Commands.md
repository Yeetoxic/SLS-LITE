# Commands And Permissions

SLS-LITE uses `/sls` and mirrors the pinned vSLS command tree where the local
operation exists. Composite instance IDs use `<blueprint>.<short-id>`. For
server-targeting commands, player senders may use `this` for their current
managed backend.

The Velocity console and built-in SLS-LITE administrators can run all
administrative commands. A permission provider may grant:

- `sls.command.admin` for all administrative operations.
- `sls.command.<operation>` for one operation.
- `sls.command.<operation>.others` for targeting other players.
- force nodes such as `sls.command.stop.force` and `sls.command.kill.force`.

## Public Commands

| Command | Purpose |
| --- | --- |
| `/sls info` | Network, lobby, queue, and managed-resource summary. |
| `/sls list` | Running managed instances and players. |
| `/sls registries` | Dynamic blueprint registries. |
| `/sls find <player>` | Find an online player on a managed backend. |
| `/sls join <registry> <blueprint>` | Queue yourself and start/select an instance. |
| `/sls join <registry> <blueprint> <player\|all\|local>` | Target others; requires join-other permission. |
| `/sls join player <player>` | Join the target player's managed instance. |
| `/sls dequeue` | Cancel your queued join. |
| `/sls version` | SLS-LITE version and authors. |
| `/sls admin claim <code>` | Claim built-in administration as a player. |

`local` means players on the sender's current backend. Console senders must
name a player and cannot use player-only selectors.

## Administrative Commands

| Command | Permission suffix | Purpose |
| --- | --- | --- |
| `/sls admin add <online-player>` | `admin` | Add a built-in administrator. |
| `/sls admin remove <player>` | `admin` | Remove one by last known name. |
| `/sls admin list` | `admin` | List built-in administrators. |
| `/sls admin code` | console only | Issue a short-lived one-time claim code. |
| `/sls blueprint <id>` | `blueprint` | Show one blueprint's registry, software, limits, persistence, active instances, volumes, copies, and environment-variable names. |
| `/sls blueprints [registry]` | `blueprints` | List blueprint details; rows suggest join commands. |
| `/sls create <registry> <blueprint> [flags...]` | `create` | Provision and start a fresh managed instance. Supported local overrides are persisted across restart and reset. |
| `/sls debug` | `debug` | Player-only toggle for bounded SLS-LITE command-dispatch diagnostics in chat. |
| `/sls start <registry> <blueprint>` | `start` | Start a managed instance without joining it. The additive `/sls start <blueprint>` form also works for a globally unique ID. |
| `/sls info <server\|this>` | `info` | Detailed instance information. |
| `/sls status <server\|this>` | `status` | Lifecycle state. |
| `/sls stats [server\|this]` | `stats` | Uptime, CPU time, configured/current memory, Linux process I/O where measurable, and log retention. |
| `/sls console <server\|this> <command...>` | `console` | Write one command to the child process input. |
| `/sls logs <server\|this> [page] [lines]` | `logs` | Read retained child output; up to 100 lines per page. |
| `/sls delete <server\|this>` | `delete` | Evacuate an active server, stop it cleanly, and transactionally remove its owned instance storage. |
| `/sls delete all` | `delete` | Sequentially delete every ordinary managed instance with per-server results. The managed lobby is always skipped. |
| `/sls kill [server\|this] [force]` | `kill` | Evacuate players, immediately terminate the process without a graceful save, and perform normal owned-resource cleanup. A player may omit the target to select their current server. |
| `/sls kill all [force]` | `kill` | Sequentially force-terminate active ordinary servers before any explicitly forced managed lobby. |
| `/sls stop <server\|this>` | `stop` | Evacuate and gracefully stop an instance. |
| `/sls restart <server\|this>` | `restart` | Restart a persistent instance with the same data. |
| `/sls reset <server\|this>` | `reset` | Rebuild a persistent instance from current sources. |
| `/sls dequeue <player\|all\|local>` | `dequeue`, `dequeue.others`, or admin | Cancel matching queued joins. |
| `/sls reload [all\|blueprints\|software]` | `reload` | Atomically reload definition catalogs. |
| `/sls install info` | `install` | Show software installation state. |
| `/sls install logs <software> <version>` | `install` | Show recent provider-install output. |
| `/sls system` | `system` | Host resources, filesystem/process capabilities, native COW probes, and selected local strategy. |

`/sls stats` without a server resolves to `this` and therefore requires a
player currently connected to a managed backend.

Create accepts this confined subset of vSLS `--name=value` overrides:

```text
--memory=<positive MiB>
--save=<true|false>
--seed=<server.properties level-seed>
--view-distance=<2-32>
--enable-command-block=<true|false>
```

Duplicate, malformed, empty, or out-of-range values are rejected before any
instance resources are allocated. The effective definition is recorded in
instance metadata, so persistent instances retain the same overrides through
proxy restart, `/sls restart`, and `/sls reset`. Distributed placement,
container, CPU, swap, disk, thread, image, and software/version overrides are
intentionally unavailable in local mode.

Debug mode follows the pinned vSLS player-only toggle and gray enabled/disabled
feedback. While enabled, the player receives timestamp-hovered SLS-LITE debug
lines containing the invoked `/sls` operation and sender. Command arguments,
child-console content, host paths, credentials, and other unbounded details are
not copied into debug chat. The subscription is memory-only and is removed on
disable, disconnect, or proxy shutdown.

## Force Operations

```text
/sls join player <player> --force
/sls kill <server|all> force
/sls stop <protected-lobby> --force
/sls restart <protected-lobby> --force
/sls reset <protected-lobby> --force
```

- Force join requires administrative `join` access and bypasses capacity for a
  direct player-to-player instance join.
- Kill always means immediate process termination. Its pinned upstream `force`
  modifier requests Velocity unregistration even if the termination request
  itself fails; it never releases process, port, memory, or storage ownership
  while the operating-system process may still be alive. `--force` is accepted
  as a SLS-LITE alias.
- Killing the protected managed lobby additionally requires
  `sls.command.kill.force`. Without it, `kill all` skips the lobby.
- Protected-lobby stop requires `sls.command.stop.force`.
- Protected-lobby restart requires `sls.command.restart.force`.
- Protected-lobby reset requires `sls.command.reset.force`.
- `sls.command.admin` and built-in administrators include all force access.

Forced lobby operations divert new arrivals, evacuate players to SLS-Limbo,
and write an operator audit message. A failed evacuation cancels the operation.

## Present But Unavailable

The pinned vSLS root includes commands that are not locally implemented yet:

```text
pause resume
```

They return a styled `not available in this SLS-LITE build yet` response.
`/sls node` is distributed-only and returns `not available in local mode`.
These placeholders preserve command familiarity without pretending a local
equivalent exists.

The detailed pinned upstream comparison is maintained in
[SLS Command Compatibility](SLS_Command_Compatibility.md).
