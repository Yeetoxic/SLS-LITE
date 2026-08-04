# Installation and First Run

## Before Installing

Confirm that the Velocity allocation permits child Java processes, loopback
ports, and the disk/memory required by every simultaneous backend. SLS-LITE
cannot bypass hosting-panel limits or security restrictions.

## First Run

1. Stop Velocity and remove unreachable example server entries from
   `velocity.toml`.
2. Place the shaded SLS-LITE plugin JAR in `plugins/`.
3. Start Velocity once and wait for SLS-LITE initialization.
4. Stop Velocity and review `plugins/sls-lite/config.yml`.
5. Configure secure forwarding and choose an external or managed primary lobby.
6. Review the generated software profiles and accept the Minecraft EULA only
   when you intend to use automatic installation.
7. Put source worlds under `volumes/worlds/`, shared plugin groups under
   `volumes/plugins/`, and create a blueprint from the generated template.
8. Restart Velocity, inspect `/sls system`, reload definitions, and start one
   disposable test instance before importing important data.

Back up configuration, administrators, blueprints, volumes, software profiles,
manually supplied runtimes/software, and persistent instances before updates.

Canonical instructions: [Getting Started](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Getting_Started.md) and [Data Layout](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Data_Layout.md).
