# Lobby and Matchmaking

Lobby routing has three modes:

- `velocity`: preserve Velocity's native `try` and forced-host choice;
- `external`: target one operator-managed backend already registered with
  Velocity; or
- `managed`: own one persistent SLS-LITE blueprint instance.

SLS-Limbo is the lightweight fallback for players who otherwise have no safe
backend. It is not a primary mode and is not the normal queue destination.
In `velocity` mode, existing native or plugin redirects remain authoritative;
SLS-LITE changes only a final disconnect into an SLS-Limbo redirect. Failed
initial and forced-host connections are retried against their exact selected
destination, and players rescued from a backend outage return when that backend
is available. Deliberately selecting `/server sls-limbo` creates no fallback
tracking record, so that player remains there until they choose another route.
Its ping text, virtual player sample, brand, join message, boss bar, title,
header/footer, and supported dimension are operator-configurable under
`lobby.limbo.presentation`; each visual surface can be disabled independently.
Text uses bounded MiniMessage and is serialized safely. SLS-LITE still owns the
bind address, port, forwarding, protocol, capacity, traffic, and lifecycle
settings.
Players who request a cold game from a healthy backend stay there while the
destination starts, then transfer directly when it becomes ready.

Matchmaking prefers a ready compatible instance with capacity, accounts for
queued slots, and creates a new instance only within blueprint and host limits.
Queue entries terminate on success, timeout, cancellation, disconnect,
startup failure, instance failure, or shutdown.

Canonical references: [SLS-Limbo](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/SLS_Limbo.md), [Configuration](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Configuration.md), and [Operations](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Operations.md).
