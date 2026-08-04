# Operations

Use SLS-LITE commands or normal Velocity shutdown so backend registration,
players, processes, ports, memory reservations, storage layers, and logs are
released through one lifecycle owner.

Daily operator tools include:

```text
/sls list
/sls info [server]
/sls status <server>
/sls stats <server>
/sls logs <server> [page] [lines]
/sls install info
/sls system
```

Persistent instances reuse verified prepared storage across normal restarts.
Reset is the explicit reconstruction operation. Back up persistent content
before reset, update, or manual recovery work.

The proxy console shows concise milestones and actionable failures. Detailed
provisioning, timing, storage, and reconciliation records go to the bounded
SLS-LITE detail log unless console mirroring is explicitly enabled.

Canonical lifecycle, logging, backup, shutdown, and recovery guidance:
[Operations and Recovery](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Operations.md) and [Getting Started](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Getting_Started.md).
