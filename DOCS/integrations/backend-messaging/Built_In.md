# Built-In Backend Messaging

[Backend messaging hub](README.md) | [Documentation home](../../README.md)

SLS-LITE provides a narrow, default-off `slslite:request` channel for
operator-authored Paper NPC, menu, and server plugins. It supports two
player-bound actions: request normal matchmaking, or relay an explicitly
allowlisted `/sls` command through Velocity as the carrier player.

It is not a remote API, console bridge, or general command forwarder. Prefer the
direct `matchmake` action when an NPC only needs to send its clicking player to
a blueprint.

## NPC Quick Start

### 1. Identify the source and destination

Record:

- the Paper server on which the NPC exists;
- whether that source is an exact external Velocity server name, such as
  `lobby`, or a managed `registry/blueprint`, such as `production/lobby`;
- the destination registry and blueprint used by `/sls join`, such as
  `practice` and `practice`.

The source is the server sending the request, not the destination being joined.
An exact `server` selector must match its name in Velocity's `[servers]` table.
A `blueprint` selector authorizes every managed instance of that one blueprint.

### 2. Install a Paper sender

The NPC plugin must call code that sends an `slslite:request` plugin message
through the clicking player. For testing, build and install the maintained
[Paper sender example](../../../examples/paper-backend-sender/README.md) on the NPC's
Paper server. A production NPC/menu plugin can embed its small protocol encoder.

Installing only an NPC plugin is not enough unless it implements this channel.
Making the backend console run `/sls` also cannot work: the channel deliberately
requires the real player connection as its carrier.

### 3. Authorize the Paper source

For an NPC on an external Velocity server named `lobby`, add this to SLS-LITE's
`config.yml`:

```yaml
security:
  backend_messaging:
    enabled: true
    command_relay_enabled: false
    requests_per_window: 10
    window_seconds: 10
    sources:
      lobby-npcs:
        server: lobby
        actions: [matchmake]
        command_roots: []
```

For an NPC inside an SLS-LITE-managed lobby, replace `server: lobby` with its
blueprint identity:

```yaml
      managed-lobby-npcs:
        blueprint: production/lobby
        actions: [matchmake]
        command_roots: []
```

Set exactly one of `server` or `blueprint` for each source. Restart Velocity
after editing `config.yml`; `/sls reload` does not reload host security policy.

### 4. Configure the NPC click

With the example sender installed, configure the NPC plugin to run this Paper
command as the clicking player:

```text
slsbridge join practice practice
```

Replace the final two values with the destination registry and blueprint.
Whether the NPC editor expects a leading `/` depends on that NPC plugin. The
important requirement is execution as the player, not as console. Grant only
the sender permission required by the integration; the example uses
`slslite.bridge.example`.

First run the command manually as a player on that backend. Once it works,
attach the identical player command to the NPC. A successful request uses the
normal SLS-LITE feedback and either joins an available instance, queues the
player, or starts an eligible instance.

### 5. Use command relay only when needed

If the integration genuinely needs an allowlisted `/sls` command rather than
direct matchmaking, enable both command gates:

```yaml
security:
  backend_messaging:
    enabled: true
    command_relay_enabled: true
    requests_per_window: 10
    window_seconds: 10
    sources:
      lobby-command-npcs:
        server: lobby
        actions: [command]
        command_roots: ["sls join"]
```

The example player's command is then:

```text
slsbridge command sls join practice practice
```

The forwarded command still runs as that player and requires the normal
Velocity `/sls` permission. Keep `command_roots` narrow; do not allow `sls` when
the NPC needs only `sls join`.

### 6. Diagnose a click that does nothing

Check these in order:

1. Run the sender command manually as the same player on the same Paper server.
2. Confirm Velocity was fully restarted after the host-config change.
3. Confirm the source uses the exact Velocity server name or exact managed
   `registry/blueprint` identity.
4. Confirm `matchmake` or `command` appears in that source's `actions`.
5. For command relay, confirm the global relay switch, token-boundary command
   root, and player's ordinary Velocity permission.
6. Confirm the NPC executes its click command as the player rather than console.
7. Inspect the Velocity console and `logs/sls-lite-detail.log` for rejected,
   malformed, rate-limited, duplicate, or unauthorized requests.

## Trust Model

A request is accepted only when all of these checks pass:

1. The identifier is exactly `slslite:request`. SLS-LITE marks every message on
   this identifier handled before inspecting its source or data.
2. Velocity identifies the source as a backend `ServerConnection`, not a
   client-originated message.
3. The carrier is the connection's actual active player and that connection is
   still the player's current backend.
4. The exact Velocity server name, or the server's managed `registry/blueprint`,
   is explicitly listed under `security.backend_messaging.sources`.
5. The source permits the requested action and remains within its per-player
   rate window.
6. The payload is valid protocol v1 and its request UUID has not already been
   accepted within the bounded expiry window.

The payload contains no player identity. A sender cannot select another player,
request console execution, bypass matchmaking admission, or bypass Velocity
permissions. A compromised authorized backend can request only the actions and
command roots granted to that source, so authorize the narrowest possible
source and action set.

SLS-LITE does not listen to `slimelabs:network`, `sls:vsls`,
`bungeecord:main`, or another plugin's forwarding channel. See the
[third-party guide](Third_Party.md) when another plugin owns
the forwarding path.

## Configuration Reference

Host configuration changes require a Velocity restart. This example authorizes
one external lobby for matchmaking and two command roots:

```yaml
security:
  backend_messaging:
    enabled: true
    command_relay_enabled: true
    requests_per_window: 10
    window_seconds: 10
    sources:
      external-lobby:
        server: lobby
        actions: [matchmake, command]
        command_roots: ["sls join", "sls list"]
```

Use `blueprint: registry/id` instead of `server` to authorize every current and
future managed instance of one blueprint:

```yaml
      managed-lobbies:
        blueprint: production/lobby
        actions: [matchmake]
```

Each source must set exactly one selector. Command relay requires
`command_relay_enabled: true` globally and `command` in that source's actions.
Every command-enabled source must declare one or more roots beginning with
`sls`. Root matching is token-boundary aware: `sls join` permits
`sls join minigame example`, but not `sls joining`.

## Protocol v1

All integers are network-order/big-endian. Strings are strict UTF-8 prefixed by
an unsigned 16-bit byte length. The entire payload is at most 4096 bytes and no
trailing data is permitted.

| Field | Size | Value |
| --- | ---: | --- |
| Version | 1 byte | `1` |
| Action | 1 byte | `1` matchmaking; `2` command |
| Request ID | 16 bytes | Non-nil UUID: most-significant then least-significant 64 bits |
| Matchmaking registry | 2-byte length + bytes | 1-128 bytes; letters, digits, `.`, `_`, `-` |
| Matchmaking target | 2-byte length + bytes | 1-128 bytes; same identifier rules |
| Command | 2-byte length + bytes | 1-512 bytes; no control characters |

Action 1 contains registry then target. Action 2 contains only command. Generate
a new random request ID for each user action. Reusing an ID is treated as a
retransmission and ignored globally while its bounded deduplication entry
remains active.

There is no response message in v1. Matchmaking uses normal SLS-LITE feedback,
queue, preparation, capacity, and transfer paths. Relayed commands use the
normal Velocity command response and permission contract.

## Sender Implementation

The maintained [Paper sender example](../../../examples/paper-backend-sender/README.md)
contains a dependency-free encoder and a small test command. Other plugins may
copy the protocol encoder or implement the wire format directly; they do not
need to depend on that example or on the SLS-LITE Java API.

A managed Paper lobby is still a separate backend process. A player typing a
registered Velocity command is already handled by Velocity, but server-side NPC
or menu code needs this channel or a separately configured
[third-party forwarder](Third_Party.md).
