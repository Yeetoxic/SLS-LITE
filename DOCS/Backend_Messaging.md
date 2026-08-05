# Backend Messaging

[Documentation home](README.md)

SLS-LITE provides a narrow backend-to-proxy channel for operator-authored NPC,
menu, and server plugins. It supports two player-bound actions: request normal
SLS-LITE matchmaking, or relay an explicitly allowlisted `/sls` command through
Velocity as the carrier player.

The channel is disabled by default. It is not a remote API, a console bridge,
or a general command forwarder.

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
   accepted anywhere within the bounded expiry window.

The payload contains no player identity. A sender cannot select another player,
request console execution, bypass matchmaking admission, or bypass Velocity
permissions. A compromised authorized backend can request only the actions and
command roots granted to that source, so authorize the narrowest possible
source and action set.

SLS-LITE does not listen to `slimelabs:network`, `sls:vsls`,
`bungeecord:main`, or another plugin's general forwarding channel. General
command forwarders remain compatible when they dispatch `/sls` normally
through Velocity; operators must secure those plugins separately.

## Configuration

Host configuration changes require a Velocity restart. This example authorizes
one external lobby for matchmaking and two read/admission command roots:

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
        command_roots: [sls join, sls list]
```

Use `blueprint: registry/id` instead of `server` to authorize every current and
future managed instance of one blueprint:

```yaml
      managed-lobbies:
        blueprint: production/lobby
        actions: [matchmake]
```

Each source must set exactly one selector. Command relay requires two opt-ins:
`command_relay_enabled: true` globally and `command` in that source's actions.
Every command-enabled source must also declare one or more roots beginning with
`sls`. Root matching is token-boundary aware: `sls join` permits
`sls join minigame example`, but does not permit `sls joining`.

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
retransmission and ignored globally while the bounded deduplication entry
remains active.

There is no response message in v1. Matchmaking uses the normal SLS-LITE player
feedback, queue, preparation, capacity, and transfer paths. Relayed commands use
the normal Velocity command response and permission contract.

## Sender Implementation

The maintained [Paper sender example](../examples/paper-backend-sender/README.md)
contains a dependency-free encoder and a small test command. Other plugins may
copy the protocol encoder or implement the wire format directly; they do not
need to depend on that example or on the SLS-LITE Java API.

A managed Paper lobby is still a separate backend process. A player typing a
registered Velocity command is already handled by Velocity, but server-side NPC
or menu code needs this channel or a separately configured general command
forwarder just as an external lobby does.
