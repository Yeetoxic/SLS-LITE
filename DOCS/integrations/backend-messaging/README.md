# Backend Messaging

[Documentation home](../../README.md)

Paper-side NPCs, menus, and plugins need an explicit bridge when they initiate
an action inside a backend rather than from a command packet handled directly by
Velocity. SLS-LITE supports two integration paths.

## Choose An Integration

| Path | Use it when | Security owner |
| --- | --- | --- |
| [Built-in backend messaging](Built_In.md) | A Paper plugin can send SLS-LITE protocol messages, or you can use/embed the maintained sender example. Recommended for direct player-bound matchmaking. | SLS-LITE verifies the source, carrier player, action, rate, request ID, and optional command root. |
| [Third-party command forwarding](Third_Party.md) | An existing maintained forwarder already carries Paper commands to Velocity and fits the network's plugin stack. | The selected forwarder owns its channel, source validation, allowlist, execution context, and Paper-side permission. SLS-LITE still enforces its ordinary Velocity permissions. |

These paths are alternatives and may coexist only when different integrations
actually need them. Enabling SLS-LITE's `security.backend_messaging` does not
configure or secure another plugin's forwarding channel.

## Recommended Choice

For an NPC whose only job is sending the clicking player to a managed server,
use the built-in `matchmake` action. It exposes no general command relay and
retains normal SLS-LITE capacity, queue, startup, feedback, and transfer logic.

Use a third-party forwarder when it is already part of the server stack or the
NPC/menu system is designed around its wrapper command. Preserve the clicking
player as the Velocity command source, retain ordinary permissions, and
allowlist only the narrowest required root—normally `sls join`.

## Shared Requirement

Both paths require a real connected player. Configure the NPC or menu to execute
as the clicking player, not as console. A console action cannot safely identify
which player should be transferred and should never be elevated into unrestricted
proxy-console execution.

Start with the appropriate guide:

- [Configure SLS-LITE built-in backend messaging](Built_In.md)
- [Configure a generic third-party command forwarder](Third_Party.md)
