# Configuration

SLS-LITE generates a commented `plugins/sls-lite/config.yml`. Every host
configuration change requires a Velocity restart; definition reload commands
apply only to blueprints and software profiles.

Review these areas on every host:

- Managed memory and process admission budgets
- Loopback port range
- Storage strategy and optional snapshot-helper settings
- Matchmaking defaults, per-blueprint queue expiry, and bounded diagnostic retention
- Java runtime mapping
- Forwarding mode and secret
- Velocity-native, external, or managed lobby policy
- SLS-Limbo memory and recovery limits
- Queue, lifecycle, readiness, and shutdown timeouts
- Detailed log level and proxy-console mirroring
- Built-in transfer action-bar templates, animation, and enablement
- Administrator bootstrap and offline-mode restrictions

Defaults are portable safety baselines, not automatic panel sizing. Unknown
structural keys and unsafe combinations fail validation instead of being
silently ignored.

Set `presentation.transfer_action_bar.enabled: false` when an extension should
own that surface. Otherwise its joining, force-joining, dequeue, and animation
entries accept bounded MiniMessage; `<server>` is substituted safely.

Canonical reference: [Configuration](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Configuration.md). The shipped source of defaults is
[`config.yml`](https://github.com/Yeetoxic/SLS-LITE/blob/main/src/main/resources/defaults/host/config.yml).
