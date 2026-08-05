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

Canonical protocol, configuration, and security guide:
[Backend Messaging](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Backend_Messaging.md).
Working Paper source:
[Backend Sender Example](https://github.com/Yeetoxic/SLS-LITE/tree/main/examples/paper-backend-sender).
