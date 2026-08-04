# Commands And Permissions

[Documentation home](README.md)

<!-- sls-command-contract-sha256:7e2d50bf0b261b2bf563d63296ce93f08236f82713a486708e975f07c1c9e947 -->

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

### Permission Reference

The versioned command contract and build-time documentation checks keep this
complete permission inventory synchronized with the runtime:

| Permission | Grants |
| --- | --- |
| `sls.command.admin` | Every administrative command, force operation, and local administrator-management action. |
| `sls.command.blueprint` | Inspect one blueprint. |
| `sls.command.blueprints` | Browse blueprint registries. |
| `sls.command.console` | Send child-console commands and manage console follow. |
| `sls.command.create` | Provision a fresh instance. |
| `sls.command.debug` | Toggle the player-only bounded debug stream. |
| `sls.command.delete` | Delete ordinary managed instances. |
| `sls.command.dequeue` | Administratively dequeue targets. |
| `sls.command.dequeue.others` | Dequeue other players or selector groups. |
| `sls.command.info` | Inspect one managed instance. |
| `sls.command.install` | Inspect software installation state and logs. |
| `sls.command.join` | Use administrative join forms. |
| `sls.command.join.others` | Send other players or selector groups through matchmaking. |
| `sls.command.join-test` | Probe a registered backend with bounded status negotiation. |
| `sls.command.kill` | Immediately terminate ordinary managed instances. |
| `sls.command.kill.force` | Include the protected managed lobby in a forced kill. |
| `sls.command.logs` | Read bounded retained child output. |
| `sls.command.maintenance` | Enable, disable, or inspect new-instance drain mode. |
| `sls.command.node` | Receive the explicit local-mode node compatibility response. |
| `sls.command.pause` | Receive the explicit unavailable pause response. |
| `sls.command.reload` | Reload blueprint/software catalogs or inspect restart-only config behavior. |
| `sls.command.reset` | Reset ordinary persistent instances. |
| `sls.command.reset.force` | Reset the protected managed lobby. |
| `sls.command.restart` | Restart ordinary persistent instances. |
| `sls.command.restart.force` | Restart the protected managed lobby. |
| `sls.command.resume` | Receive the explicit unavailable resume response. |
| `sls.command.start` | Start a managed instance without joining it. |
| `sls.command.stats` | Inspect managed process metrics. |
| `sls.command.status` | Inspect managed lifecycle status. |
| `sls.command.stop` | Gracefully stop ordinary managed instances. |
| `sls.command.stop.force` | Stop the protected managed lobby. |
| `sls.command.system` | Inspect host resources and capabilities. |

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
| `/sls join-test <server\|this>` | `join-test` | Run a bounded Minecraft status negotiation against a ready registered backend. This is a reachability diagnostic, not a synthetic player login. |
| `/sls start <registry> <blueprint>` | `start` | Start a managed instance without joining it. The additive `/sls start <blueprint>` form also works for a globally unique ID. |
| `/sls info <server\|this>` | `info` | Detailed instance information. |
| `/sls status [server\|this] [remote]` | `status` | Lifecycle state. Players may omit the target for their current server. `remote` is retained as an explicit local-mode boundary response because no daemon exists. |
| `/sls stats [server\|this]` | `stats` | Uptime, CPU time, configured/current memory, Linux process I/O where measurable, and log retention. |
| `/sls console <server\|this> <command...>` | `console` | Write one command to the child process input, then asynchronously show up to eight new output lines captured during the bounded two-second response window. |
| `/sls console <server\|this> --follow` | `console` | Opt into a bounded nonblocking live output stream. Starting another follow moves the same source to the new instance. |
| `/sls console <server\|this> --unfollow` | `console` | Stop the source's active live output stream. |
| `/sls logs <server\|this> [page] [lines]` | `logs` | Read retained child output; up to 100 lines per page. |
| `/sls delete <server\|this>` | `delete` | Evacuate an active server, stop it cleanly, and transactionally remove its owned instance storage. |
| `/sls delete all` | `delete` | Sequentially delete every ordinary managed instance with per-server results. The managed lobby is always skipped. |
| `/sls kill [server\|this] [force]` | `kill` | Evacuate players, immediately terminate the process without a graceful save, and perform normal owned-resource cleanup. A player may omit the target to select their current server. |
| `/sls kill all [force]` | `kill` | Sequentially force-terminate active ordinary servers before any explicitly forced managed lobby. |
| `/sls stop [server\|this] [force]` | `stop` | Evacuate and gracefully stop an instance. Players may omit the target. The pinned `force` spelling and additive `--force` alias are accepted. |
| `/sls stop all [force]` | `stop` | Sequentially stop every ordinary server. The protected managed lobby is skipped unless force and `stop.force` permission are both present, and is always processed last. |
| `/sls restart [server\|this]` | `restart` | Restart a persistent instance with the same data; players may omit the current target. |
| `/sls reset [server\|this]` | `reset` | Rebuild a persistent instance from current sources; players may omit the current target. |
| `/sls dequeue <player\|all\|local>` | `dequeue`, `dequeue.others`, or admin | Cancel matching queued joins. |
| `/sls reload [all\|blueprints\|software\|config]` | `reload` | Atomically reload definition catalogs. `config` explains that host-wide settings require a Velocity restart. |
| `/sls maintenance <on\|off\|status> [reason]` | `maintenance` | Block or restore brand-new instance creation without stopping active instances. Existing capacity, shutdown, cleanup, and persistent identity restarts remain available while draining. The optional bounded reason is shown when creation is rejected. |
| `/sls install info` | `install` | Show software installation state. |
| `/sls install logs <software> <version>` | `install` | Show recent provider-install output. |
| `/sls install warmup <software> <version>` | `install` | Resolve, download, verify, and atomically publish a reusable provider cache without starting an instance. |
| `/sls install cleanup <minimum-age-hours> [--confirm]` | `install` | Dry-run verified automatic-cache cleanup by default. `--confirm` removes only old unreferenced entries while protecting loaded definitions, active/persistent instances, and current installs. |
| `/sls system` | `system` | Host resources, filesystem/process capabilities, native COW probes, and selected local strategy. |

`/sls stats` without a server resolves to `this` and therefore requires a
player currently connected to a managed backend.

`/sls join-test` is an additive operator diagnostic. It runs asynchronously
with a two-second timeout, permits at most four concurrent probes globally, and
deduplicates probes for the same backend. A successful result reports elapsed
time plus the backend's Minecraft version and protocol number. It proves only
that Velocity can reach the registered backend and complete Minecraft status
negotiation. It does not consume a player slot or verify authentication,
player-information forwarding, permissions, configuration-channel behavior,
login, or transfer. Those still require a real client test.

Create accepts this confined subset of vSLS `--name=value` overrides:

```text
--memory=<positive MiB>
--save=<true|false>
--seed=<server.properties level-seed>
--view-distance=<2-32>
--simulation-distance=<2-32; Minecraft 1.18+>
--enable-command-block=<true|false>
```

Duplicate, malformed, empty, or out-of-range values are rejected before any
instance resources are allocated. The effective definition is recorded in
instance metadata, so persistent instances retain the same overrides through
proxy restart, `/sls restart`, and `/sls reset`.

The complete pinned daemon/container-only create modifier set is:

```text
--node= --cpu= --swap= --io_weight= --disk_space= --threads=
--oom_disabled= --software= --version= --image= --env=
```

Each is recognized and rejected with an explicit local-mode explanation.
Unknown flags are reported separately; they are not mislabeled as known
daemon behavior.

Debug mode follows the pinned vSLS player-only toggle and gray enabled/disabled
feedback. While enabled, the player receives timestamp-hovered SLS-LITE debug
lines containing the invoked `/sls` operation and sender. Command arguments,
child-console content, host paths, credentials, and other unbounded details are
not copied into debug chat. The subscription is memory-only and is removed on
disable, disconnect, or proxy shutdown.

Console response capture reads only output appended after the command is sent;
it never replays old retained lines. Capture waits away from Velocity's command
thread, stops after two seconds or eight lines, and renders at most 320
characters per line. A quiet command receives a concise no-output response.

`--follow` is an additive operator mode and does not alter the pinned
`console <server> <command...>` form. It uses the same cursor-backed buffer in
bounded 16-line batches without blocking the managed process's output reader.
If output outruns the 1,000-line retention buffer, the operator is told how many
expired lines were skipped. Each source can follow one instance at a time.
Follow ends on `--unfollow`, instance stop, player disconnect, replacement by a
different follow, or proxy shutdown.

## Force Operations

```text
/sls join player <player> --force
/sls kill <server|all> force
/sls stop <server> force
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
- On an ordinary server, stop `force` is a compatibility no-op because local
  backend routing is already removed before the supervised graceful-stop
  request. Process and resource ownership remains until verified process exit.
  The additive `--force` spelling behaves identically.
- Protected-lobby stop requires `sls.command.stop.force`.
- Protected-lobby restart requires `sls.command.restart.force`.
- Protected-lobby reset requires `sls.command.reset.force`.
- Force completion is hidden for ordinary restart/reset targets because those
  modifiers are valid only for the protected managed lobby.
- `sls.command.admin` and built-in administrators include all force access.

Forced lobby operations divert new arrivals, evacuate players to SLS-Limbo,
and write an operator audit message. A failed evacuation cancels the operation.

## Present But Unavailable

The pinned vSLS root includes commands that are not locally implemented yet:

```text
pause resume
```

They return a styled explanation that portable process suspension has no safe
implementation. Pause recommends leaving the instance running or stopping a
persistent instance; resume recommends `/sls restart <server>` for a stopped
persistent instance. `/sls node` explains that local mode has no daemon/node
control plane and points to `/sls system` plus local lifecycle commands. These
placeholders preserve command familiarity without pretending a local
equivalent exists.

The detailed pinned upstream comparison is maintained in
[SLS Command Compatibility](SLS_Command_Compatibility.md).
