# SLS-LITE

SLS-LITE is a Velocity plugin for running a small Minecraft network inside one
hosting allocation. It launches and supervises local Java server processes,
registers ready backends with Velocity, and moves players between them without
requiring full SLS infrastructure, Docker, or a second purchased game server.

SLS-LITE follows modern SLS terminology and compatible configuration shapes
where they make sense on one host. It remains a separate, self-contained
product: full SLS is not a runtime dependency or operating mode.

> **Development status:** The core historical-world network and pinned modern
> SLS `v0.2.0` subset are implemented. The project is not a production release;
> release qualification and candidate hardening remain.

## What Works

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
- Versioned Java extension API for capability discovery, immutable inspection,
  asynchronous lifecycle/matchmaking requests, and lifecycle subscriptions.

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
- Any additional Java majors required by the Minecraft versions you launch.
- Permission to create files, bind loopback ports, and launch child Java
  processes inside the hosting allocation.
- Enough real host memory for Velocity, SLS-Limbo, and every active backend.
- Outbound HTTPS only when using the Paper or vanilla auto-install providers.

The declared managed-memory budget is admission accounting, not a way to bypass
or discover a hosting-panel limit. SLS-LITE does not bypass provider
restrictions.

## Install

1. Build with `mvn verify` or obtain a reviewed release artifact.
2. Place `sls-lite-<version>.jar` in Velocity's `plugins/` directory.
3. Start Velocity once to generate `plugins/sls-lite/`.
4. Review `config.yml`, especially memory, ports, forwarding, lobby mode, and
   administrator security.
5. Set `software.accept_eula: true` only after reviewing the Minecraft EULA.
6. Add worlds and blueprints, then restart Velocity or run
   `/sls reload blueprints`.

The project is currently a snapshot, so clean-install and update instructions
are development guidance rather than a release guarantee. See
[Getting Started](DOCS/Getting_Started.md) before operating a test network.

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
- [Migration](DOCS/Migration.md)
- [Architecture and contributor map](DOCS/ARCHITECTURE.md)
- [Java extension API](DOCS/Java_API.md)
- [Roadmap](todo.md)

## Build

```shell
mvn verify
```

The shaded plugin is written to
`target/sls-lite-0.1.0-SNAPSHOT.jar`; the compile-only public extension contract
is written to `target/sls-lite-0.1.0-SNAPSHOT-api.jar`. The plugin build embeds
SLS-LITE's AGPL license, third-party notices, SnakeYAML, and the pinned
NanoLimbo runtime used by SLS-Limbo.

## License

SLS-LITE is licensed under the
[GNU Affero General Public License v3.0](LICENSE). Bundled dependency notices
and corresponding source information are under [THIRD_PARTY](THIRD_PARTY).
