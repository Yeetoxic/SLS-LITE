# SLS-LITE

SLS-LITE is a standalone Velocity plugin for running a small Minecraft network
inside one game-server hosting allocation. It is based on SLS concepts, but it
does not require Protocube, a daemon, Docker, S4J, or another SLS installation.

## Project Status

SLS-LITE is undergoing a controlled modernization of its original 2020
implementation. The current development baseline provides:

- A Java 21-compatible Velocity 4.1 plugin foundation.
- Modern SLS-style blueprint metadata.
- Validated YAML loading and reload support.
- Explicit server-instance lifecycle states.
- Local memory reservation accounting.
- A shaded release JAR with isolated runtime dependencies.
- JUnit coverage for the implemented foundation.

It does not launch or connect players to Paper servers yet. Do not deploy this
development version to a production network.

## Goal

The intended deployment is:

```text
One hosting allocation
|-- Velocity
|-- SLS-LITE
|-- Optional managed Paper lobby
`-- Paper servers launched on demand
```

SLS-LITE will continue to support an external lobby and conventional separately
hosted backends. A host must permit child Java processes, writable instance
directories, and loopback ports for local management to work.

## Compatibility

Modern SLS is the terminology and configuration reference where equivalent
features exist. SLS-LITE implements those features locally and remains
operationally independent from full SLS.

Distributed SLS features are adapted to a single host:

- Daemon provisioning becomes supervised local Java processes.
- Node allocation becomes local memory, directory, and port admission.
- Container limits become JVM limits and local resource accounting.
- Remote event streams become in-process lifecycle events.
- Overlay volumes use a portable copy baseline with optional local
  copy-on-write optimizations planned later.

## Requirements

- JDK 21 or newer to build and run the plugin.
- Java 25 for current Paper releases that require it.
- A current compatible Velocity build.
- Maven 3.9 or newer.

The plugin is compiled to Java 21 bytecode and is tested on JDK 25.

## Build

```shell
mvn clean package
```

The shaded plugin JAR is written to:

```text
target/sls-lite-0.1.0-SNAPSHOT.jar
```

## Blueprint

On first initialization, SLS-LITE installs a template under its `blueprints`
data directory:

```yaml
blueprint:
  id: template
  name: Template Server
  type: game

server:
  software: paper
  version: "26.1"
  limits:
    memory_limit: 2048

save: false

annotations:
  sls-lite:
    start-on-proxy-start: false
    stop-when-empty: true
```

Only the fields represented by the current blueprint model are active. More
modern SLS-compatible fields will be added as their local implementations are
built.

## Roadmap

See [todo.md](todo.md) for the implementation roadmap, compatibility contract,
lobby modes, and first-release criteria.

## License

SLS-LITE is licensed under the GNU Affero General Public License v3.0. See
[LICENSE](LICENSE).
