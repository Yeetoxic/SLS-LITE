# Configuration

SLS-LITE generates a commented `plugins/sls-lite/config.yml`. Every host
configuration change requires a Velocity restart; definition reload commands
apply only to blueprints and software profiles.

Review these areas on every host:

- Managed memory and process admission budgets
- Loopback port range
- Storage strategy and optional snapshot-helper settings
- Java runtime mapping
- Forwarding mode and secret
- External or managed lobby identity
- SLS-Limbo memory and recovery limits
- Queue, lifecycle, readiness, and shutdown timeouts
- Detailed log level and proxy-console mirroring
- Administrator bootstrap and offline-mode restrictions

Defaults are portable safety baselines, not automatic panel sizing. Unknown
structural keys and unsafe combinations fail validation instead of being
silently ignored.

Canonical reference: [Configuration](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Configuration.md). The shipped source of defaults is
[`config.yml`](https://github.com/Yeetoxic/SLS-LITE/blob/main/src/main/resources/defaults/host/config.yml).
