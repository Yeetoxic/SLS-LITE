# Lobby and Matchmaking

The primary lobby is either:

- `external`: an operator-managed backend already registered with Velocity; or
- `managed`: a persistent SLS-LITE blueprint instance.

SLS-Limbo is the lightweight fallback for players who otherwise have no safe
backend. It is not a third primary mode and is not the normal queue destination.
Players who request a cold game from a healthy backend stay there while the
destination starts, then transfer directly when it becomes ready.

Matchmaking prefers a ready compatible instance with capacity, accounts for
queued slots, and creates a new instance only within blueprint and host limits.
Queue entries terminate on success, timeout, cancellation, disconnect,
startup failure, instance failure, or shutdown.

Canonical references: [SLS-Limbo](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/SLS_Limbo.md), [Configuration](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Configuration.md), and [Operations](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Operations.md).
