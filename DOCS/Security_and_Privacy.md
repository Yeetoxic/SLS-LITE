# Security and Privacy

[Documentation home](README.md)

SLS-LITE runs inside Velocity's trust boundary and launches local child Java
processes. It does not provide container isolation, elevate host privileges, or
weaken a hosting provider's security profile.

## Security Boundary

- Managed backends bind allocated loopback addresses and are registered only
  after readiness and ownership checks.
- Velocity gates every connection to a managed backend against readiness and
  the blueprint's public player limit, including native server-selection
  routes. The backend may have bounded technical headroom for an explicitly
  authorized administrator force join; that headroom is not public capacity
  and is not a substitute for secure forwarding.
- Production Paper networks should use Velocity online mode and modern
  forwarding with a unique secret readable only by the service account.
- The console is an administrator. In-game administrator claims use
  short-lived one-time codes and stable Velocity UUIDs; insecure offline claims
  remain an explicit unsafe opt-in.
- Blueprint, profile, recovery, archive, log, and copy paths are size-bounded,
  confined, and checked for unsafe symbolic-link traversal.
- Child commands are assembled without a shell. Operator snapshot helpers are
  disabled unless explicitly selected and must satisfy the confined protocol.
- Backend messaging is disabled by default. When enabled, it binds every
  request to the actual carrier player's active authorized backend, applies
  payload/rate/replay bounds, and never grants console execution or bypasses
  normal permissions.
- The Java extension API is an in-process trusted-plugin boundary, not a
  sandbox. Install extensions only from sources trusted with Velocity and
  SLS-LITE-managed data.

SLS-LITE memory and public player limits are proxy admission controls. They
cannot enforce panel, cgroup, CPU, disk, network, or process isolation. Keep
managed backend ports private to the allocation and configure secure Velocity
forwarding so clients cannot bypass the proxy's identity and admission checks.

## Network Access

SLS-LITE opens no public HTTP API or telemetry endpoint. Managed game servers
and SLS-Limbo use local listeners selected from the configured port range.
Outbound HTTPS is used only for operator-enabled provider installation, such as
retrieving Paper or vanilla metadata and artifacts.

Extensions may add their own listeners or external services. Their
authentication, encryption, retention, and privacy behavior is outside the
SLS-LITE core boundary and must be documented by the extension.

## Data and Logs

SLS-LITE does not send analytics, player activity, configuration, worlds, logs,
or crash reports to SlimeLabs. Local state can contain:

- player UUIDs in administrator and queue/lifecycle records;
- commands and player/server identifiers in detailed operational logs;
- blueprint, software, host-capability, process, and failure diagnostics;
- source worlds, plugin assets, persistent instances, caches, and child logs.

Console tails and failure summaries have configurable bounded in-memory
retention. Persistent logs are controlled separately; operators are responsible
for filesystem access, retention, backups, and redaction before sharing
diagnostics. Secrets are redacted from normal diagnostics, but configuration
files and imported server content must still be treated as sensitive.

## Reporting

For a suspected vulnerability, avoid posting credentials, forwarding secrets,
private worlds, complete logs, or exploitable details in a public issue. Use the
repository's private vulnerability-reporting channel when available. Rotate any
secret that may have been exposed and preserve only the minimum redacted logs
needed to reproduce the issue.
