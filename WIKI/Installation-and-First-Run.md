# Installation and First Run

## Quick Start

1. Confirm that the host permits child Java processes, loopback ports, writable
   storage, and enough memory for Velocity plus managed servers.
2. Stop Velocity, remove unreachable example server entries, and place the
   shaded SLS-LITE JAR in `plugins/`.
3. Start Velocity once, wait for initialization, then stop it and review
   `plugins/sls-lite/config.yml`.
4. Follow the canonical
   [forwarding and first-connection setup](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/setup/README.md#forwarding-and-first-connection),
   then accept the Minecraft EULA only for software you intend SLS-LITE to
   install automatically. Velocity or the hosting panel normally generates the
   configured `forwarding.secret`; reuse it instead of replacing it.
5. Copy the canonical
   [disposable-world recipe](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/blueprints/README.md#1-disposable-world),
   provide its source world, restart Velocity, and inspect `/sls system`.
6. Reload blueprints and start that disposable instance before importing
   important data.

If startup reports `ACTION NEEDED`, fix the first reported item and retry. Use
the full setup guide only when the quick path links to a choice you need to
understand.

Back up configuration, administrators, blueprints, volumes, software profiles,
manually supplied runtimes/software, and persistent instances before updates.

Canonical instructions:
[Getting Started](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/setup/README.md)
and [Data Layout](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/setup/Data_Layout.md).
