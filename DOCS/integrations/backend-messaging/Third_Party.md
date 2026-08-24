# Third-Party Command Forwarding

[Backend messaging hub](README.md) | [Documentation home](../../README.md)

A maintained command-forwarding plugin can carry an NPC or menu action from
Paper to Velocity instead of using SLS-LITE's dedicated protocol. This is a
separate integration path: SLS-LITE does not consume that plugin's messages,
configuration, permissions, or allowlist, and `security.backend_messaging` does
not secure it.

Forwarders use different architectures. Some install a companion on both
Velocity and Paper; some register only on the backend and use a channel
understood by a proxy component; some expose a Paper wrapper command. Follow the
selected project's own installation and version instructions, then apply these
SLS-LITE-specific steps.

## 1. Verify The Execution Contract

Before installing a forwarder, confirm it can:

- forward a command from Paper to Velocity;
- preserve the clicking player as the Velocity command source;
- run the command through Velocity's normal permission system; and
- restrict which backend commands or proxy command roots may be forwarded.

Do not use console-only or unrestricted proxy-console forwarding for an NPC.
`/sls join` is player-bound, and console execution removes the identity and
permission boundary that should constrain the click.

## 2. Install Every Required Component

Install the exact matching forwarder components on Velocity and/or the Paper
server as required by that project. Restart both sides and confirm its channel
registered before involving an NPC.

Do not enable SLS-LITE's `security.backend_messaging` merely for this path.
Enable it only when another integration actually sends `slslite:request`
messages through the [built-in channel](Built_In.md).

## 3. Allowlist Only The Required Command

Configure the forwarder to permit the narrowest useful Velocity root, normally:

```text
sls join
```

Avoid a blanket `sls` or arbitrary-command rule when the NPC only joins a
server. If the forwarder offers per-backend source restrictions, authorize only
the lobby or menu server that owns the NPCs.

## 4. Configure Permissions On Both Sides

Grant the player the forwarder's Paper-side permission needed to invoke its
wrapper command and the normal Velocity permission for the resulting SLS-LITE
operation. Forwarding a command does not grant an SLS-LITE permission. Do not
configure the forwarder to elevate, impersonate console, or bypass Velocity's
permission result.

## 5. Test Without The NPC

While connected to the intended Paper source:

1. Run `/sls join <registry> <blueprint>` normally to verify the destination and
   Velocity permission.
2. Run the forwarder's Paper-side wrapper syntax manually as the same player.
   Its wrapper name, quoting rules, permission, and configuration keys come from
   that forwarder's current documentation.
3. Confirm SLS-LITE gives its ordinary queue, preparation, or transfer feedback.

Only after both commands work should the NPC invoke the identical wrapper
command as the clicking player. NPC systems describe this mode differently—such
as player command, player execution, or execute as clicker—but it must not be a
console command.

## 6. Diagnose The Forwarding Path

If manual `/sls join` works but the wrapper does not, inspect the forwarder's:

- configuration and logs;
- Velocity/Paper component compatibility;
- plugin-channel registration;
- backend source and command allowlists; and
- Paper-side invocation permission.

If the wrapper works manually but the NPC does not, the NPC is usually using the
wrong execution context or altering the arguments. SLS-LITE diagnostics can
explain a command that reached Velocity; they cannot observe a request that the
third-party forwarder rejected or never sent.

## Security Boundary

Treat the forwarder as part of the network's security boundary. Review its
maintenance status and documentation, keep its components updated together,
and reassess its permissions after configuration changes. Prefer a project that
authenticates or constrains backend sources, bounds payloads, preserves player
identity, and provides a command allowlist.

SLS-LITE's built-in source checks, rate limits, deduplication, action allowlists,
and `command_roots` do not apply to a third-party channel. If that separation is
undesirable, use the [built-in backend-messaging guide](Built_In.md)
instead.
