# Backend Integrations

SLS-LITE has a dedicated, default-off `slslite:request` channel for trusted
Paper NPC, menu, and backend plugins. It accepts player-bound matchmaking and,
with a separate opt-in, narrowly allowlisted SLS commands.

Velocity derives the player from the real backend connection. The payload
cannot choose another identity or console execution. Every source must be
authorized by exact server name or managed blueprint and receives its own
action allowlist; relayed commands still use the carrier player's ordinary
Velocity permissions. Requests are size bounded, rate limited, validated, and
deduplicated.

SLS-LITE does not consume legacy or general command-forwarder channels. A
general forwarder can still dispatch `/sls` through Velocity normally and must
be secured according to that plugin's documentation.

For an NPC, prefer the dedicated `matchmake` action: authorize the exact Paper
server or managed lobby blueprint, install or embed a protocol-v1 sender, and
make the NPC execute `slsbridge join <registry> <blueprint>` as the clicking
player. Console execution cannot supply the trusted carrier player. Use command
relay only when direct matchmaking is insufficient, and allowlist the narrowest
command root.

A third-party command forwarder is an alternative path, not an implementation
of SLS-LITE backend messaging. Install and secure it according to its own
documentation, preserve the clicking player as command source, allowlist only
the needed `sls join` root, retain normal Velocity permissions, and make the NPC
run the forwarder's wrapper as the player rather than console. SLS-LITE's
`backend_messaging` allowlists do not protect another plugin's channel.

Choose the matching canonical guide:

- [Messaging decision hub](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/integrations/backend-messaging/README.md)
- [Built-in backend messaging](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/integrations/backend-messaging/Built_In.md)
- [Third-party command forwarding](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/integrations/backend-messaging/Third_Party.md)

Working Paper source:
[Backend Sender Example](https://github.com/Yeetoxic/SLS-LITE/tree/main/examples/paper-backend-sender).
