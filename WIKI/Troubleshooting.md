# Troubleshooting

Start with `/sls system`, then inspect the affected instance, installation, and
detail logs. Preserve correlation IDs when asking for help.

| Symptom | First checks |
| --- | --- |
| Instance never becomes ready | Exact software/version, selected Java, memory/port admission, readiness pattern, child log |
| Player remains queued | Destination startup, capacity and queued slots, queue timeout, current safe backend |
| Player reaches SLS-Limbo but not the lobby | Primary lobby identity, readiness/ping, forwarding, ViaVersion mapping, lobby recovery budget |
| Explicit COW strategy rejects startup | `/sls system` path probe, filesystem, privileges, `/dev/fuse`, helper handshake |
| `auto` reports portable copy | Expected safe fallback when no faster strategy passes the exact-path isolation probe |
| Persistent restart is rejected | Blueprint/software definition fingerprint drift; review changes before reset |
| Software installation fails | EULA setting, outbound HTTPS, exact provider version, checksum/cache, selected Java |
| Console is too noisy | Keep proxy mirroring disabled and inspect `logs/sls-lite-detail.log` on demand |

Do not manually delete instance directories, transaction backups, storage
manifests, or mounts while Velocity is running.

Canonical diagnosis and reporting procedures: [Troubleshooting](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Troubleshooting.md). Lifecycle recovery is documented in [Operations](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Operations.md), and installation-specific guidance is in [Software Installation](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Software_Installation.md).
