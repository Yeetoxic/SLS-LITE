# SLS-LITE

SLS-LITE is a Velocity plugin for running a small Minecraft network inside one
hosting allocation. It launches and supervises local Java server processes,
registers ready backends with Velocity, and moves players between them without
requiring full SLS infrastructure, Docker, or a second purchased game server.

SLS-LITE follows modern SLS terminology and compatible configuration shapes
where they make sense on one host. It remains a separate, self-contained
product: full SLS is not a runtime dependency or operating mode.

## Features

- Dynamic blueprint registries and `registry/server` command targeting.
- Composite instance IDs such as `biome_run.vued73`.
- On-demand Paper and vanilla installation for exact Minecraft versions.
- Manual custom Java server software.
- Isolated local instance directories and portable `cow` volume copies.
- Capacity-aware matchmaking, queues, direct transfers, and action-bar status.
- Managed or external primary lobbies.
- Bundled SLS-Limbo fallback when no normal backend is safe.
- Optional proxy-installed ViaVersion integration for SLS-Limbo translation and
  dynamic managed-backend protocol synchronization.
- Persistent managed instances, restart/reset, crash reconciliation, and idle
  cleanup for ephemeral servers.
- Bounded in-game logs, temporary log files, lifecycle logging, resource
  admission, forwarding configuration, and host capability checks.
- Built-in administrator claiming plus standard Velocity permissions.
- Versioned Java extension API 1.2 for capability discovery, immutable
  inspection, safe asynchronous lifecycle/administrative and routing requests,
  lifecycle subscriptions, and namespaced blueprint-readiness/status
  contributions.
- Default-off, source-verified Paper backend messaging for player-bound NPC,
  menu, matchmaking, and narrowly allowlisted SLS command integrations.

The exact current scope and intentional limitations are listed in
[Compatibility](DOCS/Compatibility.md).

ViaVersion is detected when the operator installs it on Velocity; it is not
bundled or required by SLS-LITE. Detection alone does not make an untested
Minecraft client version supported. See
[Protocol Compatibility](DOCS/Protocol_Compatibility.md) for supported native
and translated paths.

## Requirements

- A current compatible Velocity 4.x server.
- JDK 25 to build and run the pinned Velocity generation used by this release.
  SLS-LITE itself is emitted as Java 21 bytecode for plugin compatibility.
- Maven 3.9.6 or newer when building from source.
- Any additional Java majors required by the Minecraft versions you launch.
- Permission to create files, bind loopback ports, and launch child Java
  processes inside the hosting allocation.
- Enough real host memory for Velocity, SLS-Limbo, and every active backend.
- Outbound HTTPS only when using the Paper or vanilla auto-install providers.

The declared managed-memory budget is admission accounting, not a way to bypass
or discover a hosting-panel limit. SLS-LITE does not bypass provider
restrictions.

## Install

1. Build with `mvn clean verify` or obtain a reviewed release artifact.
2. Place `sls-lite-<version>.jar` in Velocity's `plugins/` directory.
3. Start Velocity once to generate `plugins/sls-lite/`.
4. Follow the canonical
   [forwarding and first-connection setup](DOCS/Getting_Started.md#forwarding-and-first-connection),
   then review the remaining `config.yml` memory, ports, lobby, and security
   choices.
5. After reviewing the Minecraft EULA, accept it either per automatic software
   profile with `software.accept_eula: true` or host-wide with
   `software.auto_accept_eula: true` in `config.yml`.
6. Add worlds and blueprints using the copyable
   [Blueprint Recipe Book](DOCS/Blueprint_Recipes.md), then restart Velocity or
   run `/sls reload blueprints`.

See [Getting Started](DOCS/Getting_Started.md) for the complete clean install,
real-network and isolated-development paths, first transfer, updates, backup,
and removal.

## Basic Use

```text
/sls registries
/sls blueprints minigame
/sls join minigame biome_run
/sls list
/sls info
```

The first operator can issue `/sls admin code` from the Velocity console and
claim it in game with `/sls admin claim <code>`. Production proxies must use
online mode for persistent in-game administrators.

## Data Model

```text
plugins/sls-lite/
|-- config.yml
|-- administrators.properties
|-- blueprints/
|-- volumes/
|   |-- worlds/
|   `-- plugins/
|-- software-profiles/
|-- software/
|-- runtimes/
|-- instances/
`-- sls-limbo/
```

Blueprint folders are organizational. The dynamic registry used by commands is
the blueprint's `blueprint.type`.

## Documentation

- [Documentation index](DOCS/README.md)
- [Getting started and installation](DOCS/Getting_Started.md)
- [Configuration reference](DOCS/Configuration.md)
- [Blueprint reference](DOCS/Blueprints.md)
- [Commands and permissions](DOCS/Commands.md)
- [Operations and recovery](DOCS/Operations.md)
- [Troubleshooting](DOCS/Troubleshooting.md)
- [Migration](DOCS/Migration.md)
- [Architecture and contributor map](DOCS/ARCHITECTURE.md)
- [Java extension API](DOCS/Java_API.md)
- [Java API scope and compatibility policy](DOCS/Java_API_Compatibility.md)
- [Paper backend messaging](DOCS/Backend_Messaging.md)
- [Security and privacy](DOCS/Security_and_Privacy.md)
- [Current release notes](RELEASE_NOTES.md)
- [Reviewable GitHub Wiki source](WIKI/README.md)

## Build

```shell
mvn clean verify
```

The shaded plugin is written to `target/sls-lite-<version>.jar`; the compile-only
public extension contract is written to `target/sls-lite-<version>-api.jar`. The plugin build embeds
SLS-LITE's AGPL license, third-party notices, SnakeYAML, and the pinned
NanoLimbo runtime used by SLS-Limbo.

Always begin a distributable build with `clean`. The shading phase relocates
packaged dependency references, so a second package/install invocation against
the same `target/` directory is not a canonical release build.

Only the shaded plugin JAR is installed on Velocity. It already contains the
public API classes. The smaller `-api.jar` is an SDK/classifier for extension
projects and must not be installed as another plugin.

## License

SLS-LITE is licensed under the
[GNU Affero General Public License v3.0](LICENSE). Bundled dependency notices
and corresponding source information are under [THIRD_PARTY](THIRD_PARTY).
