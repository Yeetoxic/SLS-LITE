# Security Policy

## Supported versions

During release-candidate testing, security fixes are applied to the latest
published SLS-LITE release candidate. Once a stable release exists, this policy
will be updated to identify the stable versions receiving security fixes.

Older development snapshots and superseded release candidates are not
maintained. Reproduce a suspected issue on the latest published version when it
is safe to do so.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting feature from the repository's
**Security** tab. Do not open a public issue for a vulnerability that could
enable command execution, unauthorized access, path traversal, data loss,
privilege escalation, secret exposure, or denial of service.

Include the affected SLS-LITE version, environment, impact, reproduction steps,
and the smallest relevant configuration or log excerpt. Remove access tokens,
Velocity forwarding secrets, player addresses, credentials, and unrelated user
data.

Please allow maintainers time to reproduce and address the report before
publishing technical details. Reports outside the currently supported behavior
or requiring unsafe testing may be closed with an explanation.
