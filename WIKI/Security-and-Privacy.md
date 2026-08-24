# Security and Privacy

SLS-LITE does not expose a public HTTP control plane or transmit telemetry,
worlds, configuration, player activity, logs, or crash reports to SlimeLabs.
Operational data and logs remain on the host unless the operator or an installed
extension sends them elsewhere.

Use online-mode Velocity, modern forwarding with a private unique secret,
granular permissions, and only trusted Velocity extensions. SLS-LITE admission
limits are not container or hosting-panel isolation.

Canonical guidance: [Security and Privacy](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/operations/Security_and_Privacy.md).
