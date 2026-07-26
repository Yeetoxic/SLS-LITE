# SLS-LITE

SLS-LITE is a standalone Velocity plugin for running a small Minecraft network
inside one game-server hosting allocation. It is based on SLS concepts, but it
does not require Protocube, a daemon, Docker, S4J, or another SLS installation.

## Project Status

SLS-LITE is undergoing a controlled modernization of its original 2020
implementation. The current development baseline provides:

- A Java 21-compatible Velocity 4.1 plugin foundation.
- Modern SLS-style blueprint metadata.
- Validated host configuration and software launch profiles.
- Validated YAML loading and reload support.
- Explicit server-instance lifecycle states.
- Configurable idle shutdown for empty ephemeral instances.
- Local memory reservation accounting.
- Loopback port reservation and isolated instance-directory copying.
- Shell-free Paper command construction and managed process supervision.
- Startup probes for child Java execution, writable instance storage, and
  loopback port binding.
- A bundled, supervised SLS-Limbo virtual space for network setup, destination
  startup, and safe placement between servers.
- Bounded in-game and temporary-file process logs with optional proxy mirroring.
- A shaded release JAR with isolated runtime dependencies.
- JUnit coverage for the implemented foundation.

The `/sls start`, `/sls join`, `/sls list`, `/sls status`, and `/sls stop`
commands now manage local Paper processes, dynamically register ready backends
with Velocity, and connect requested players after an instance becomes ready.
Matchmaking queues, capacity enforcement, persistent-instance restart/reset,
and external or managed lobby routing are implemented. Production hardening is
not complete. Do not deploy this development version to a production network.

## Goal

The intended deployment is:

```text
One hosting allocation
|-- Velocity
|-- SLS-LITE
|-- Bundled SLS-Limbo
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

The in-game command compatibility target and implementation status are tracked
in [DOCS/SLS_Command_Compatibility.md](DOCS/SLS_Command_Compatibility.md).
Upstream vSLS command names and argument order are primary; local conveniences
are additive aliases.

The proven single-host workflows from the original Velocity-only implementation
are recorded separately in
[DOCS/Historical_Single_Host_Baseline.md](DOCS/Historical_Single_Host_Baseline.md).

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
- Permission for Velocity to launch child Java processes.
- Permission to bind managed backends to additional loopback ports.
- Writable plugin storage for software, instances, worlds, and temporary logs.
- Enough provider-assigned memory for Velocity plus every admitted backend.

The plugin is compiled to Java 21 bytecode and is tested on JDK 25.
At startup, SLS-LITE verifies storage, loopback binding, and every distinct Java
runtime referenced by a software profile. Managed initialization fails with a
specific diagnostic if a required probe fails. Provider memory limits cannot be
discovered portably, so `resources.total_memory_mib` remains an operator-declared
admission budget.

## Configuration

On first initialization, SLS-LITE creates `config.yml` in its Velocity plugin
data directory:

```yaml
resources:
  total_memory_mib: 4096
  max_managed_processes: 101

network:
  ports:
    start: 25570
    end: 25670

matchmaking:
  queue_timeout_seconds: 180

lifecycle:
  idle_shutdown_seconds: 180

managed_output:
  mirror_to_proxy_console: false
  write_temporary_file: true
  temporary_file_max_kib: 4096

forwarding:
  mode: none
  online_mode: true
  secret_file: forwarding.secret

lobby:
  mode: external
  registry: lobby
  server: lobby
  limbo:
    enabled: true
    memory_mib: 96
    startup_timeout_seconds: 30
    recovery:
      max_attempts: 5
      initial_backoff_seconds: 2
      max_backoff_seconds: 30
      stable_after_seconds: 120

paths:
  instances: instances
```

The memory value is the shared budget for managed backend processes and excludes
Velocity itself. SLS-Limbo reserves its configured heap from this same budget
and consumes one managed process slot. In managed-lobby mode, startup validation
requires enough memory and process slots for both SLS-Limbo and the primary
lobby. `max_managed_processes` cannot exceed the configured port count and
defaults to that count when omitted. Managed paths must remain relative to the
SLS-LITE data directory. Managed instances reserve an available loopback port
from this range and release it during cleanup. Matchmaking requests fail and
clean themselves up after the configured timeout.

Unknown structural keys in host configuration, blueprints, and software profiles
are rejected with their full path and a suggestion when one is available.
Blueprint `annotations` remain open-ended so integrations can preserve their
own metadata.

SLS-Limbo is currently experimental. Its architecture, operational
limits, compatibility policy, and manual test are documented in
[DOCS/SLS_Limbo.md](DOCS/SLS_Limbo.md). The bundled component's
source, pinned revision, checksum, and GPL notice are recorded in
[THIRD_PARTY/NanoLimbo.md](THIRD_PARTY/NanoLimbo.md).
The packaged plugin also includes its AGPL license and the licenses and notices
for bundled NanoLimbo and SnakeYAML components under `META-INF`.
Native and translated client test results are tracked in
[DOCS/Protocol_Compatibility.md](DOCS/Protocol_Compatibility.md).

Normal matchmaking does not pass through SLS-Limbo. Players remain on their
current healthy backend while queued and transfer directly when the requested
destination is ready. SLS-Limbo is reserved for cases where no safe normal
backend is available.

Managed process output always feeds the bounded `/sls logs` viewer. Proxy
mirroring is disabled by default so Paper output does not flood the Velocity
console. When temporary files are enabled, each instance writes
`logs/sls-lite-console.log` inside its isolated directory and stops writing at
the configured hard cap; no archive files are created. Ephemeral instance logs
are removed with the instance directory. These host-wide settings currently
apply after a proxy restart.

For a production Paper network, set `forwarding.mode: modern`,
`forwarding.online_mode` to the same value as Velocity's `online-mode`, and
`forwarding.secret_file` to Velocity's configured forwarding secret file.
SLS-LITE rejects startup when modern forwarding and Velocity disagree about
online mode.
SLS-LITE then patches each managed instance's `spigot.yml` and
`config/paper-global.yml` without exposing the secret in process arguments or
logs. The default `none` mode is intended for isolated smoke testing.

Empty `READY` ephemeral instances stop after `lifecycle.idle_shutdown_seconds`.
Set the value to `0` to disable idle shutdown globally. Persistent `save: true`
instances and the active lobby are excluded. A blueprint can override the delay
or opt out:

```yaml
annotations:
  sls-lite:
    idle-shutdown-seconds: 300
    keep-alive: true
```

`stop-when-empty: false` is also treated as keep-alive. Idle shutdown rechecks
players and queued joins before draining an instance, preventing a new
matchmaking request from being assigned while shutdown begins.

Each managed instance contains an internal `.sls-lite-instance.properties`
ownership record. On startup, SLS-LITE uses that record to remove confirmed
stale ephemeral directories left by an unclean shutdown. A live child is stopped
only when both its PID and recorded start time match; its ephemeral directory is
then removed, or its persistent directory is normalized to `STOPPED`. Live
processes without enough identity data, malformed records, and directories from
older versions without metadata are preserved and reported. Persistent
instances are not automatically resumed yet.

Administrators can recover or cycle a persistent instance using its original
composite ID:

```text
/sls restart <instance-id>
/sls reset <instance-id>
```

Restart preserves the instance directory and world. Reset evacuates players,
stops the process, replaces the directory from its current software template,
and starts the same instance ID. Directory replacement keeps a rollback copy
until the new files and ownership metadata are valid. Both commands reject
ephemeral instances and recorded child processes that are still running outside
the current manager.

`lobby.mode: external` routes players to an existing Velocity registration named
by `lobby.server`. `lobby.mode: managed` starts the blueprint identified by
`lobby.registry` and `lobby.server` in the local allocation and reserves that
instance as the initial and fallback lobby.

Managed lobbies restart after unexpected exits using bounded exponential
backoff. Retry limits are configured under `lobby.recovery`:

```yaml
lobby:
  recovery:
    max_attempts: 5
    initial_backoff_seconds: 5
    max_backoff_seconds: 60
    stable_after_seconds: 120
```

Set `max_attempts` to `0` to disable recovery. A lobby that remains healthy for
`stable_after_seconds` receives a fresh retry budget. While no lobby is ready,
new connections and backend kick redirects fail closed with a temporary
unavailability message instead of routing players to an arbitrary server.
`/sls info` reports `STARTING`, `READY`, `RECOVERING`, `OFFLINE`, or
`SHUTTING_DOWN`.

SLS-LITE also creates `software-profiles/paper.yml`:

```yaml
software:
  id: paper
  base_directory: software/paper/{version}
  server_jar: paper.jar

launch:
  java: java
  jvm_arguments:
    - "-Xms{memory_mib}M"
    - "-Xmx{memory_mib}M"
  server_arguments:
    - "--nogui"

readiness:
  pattern: 'Done \([^)]+\)! For help'
  timeout_seconds: 180

shutdown:
  command: stop
  timeout_seconds: 30
```

Launch arguments are represented as YAML lists so future process creation can
pass them directly to Java without invoking a command shell. The first process
supervisor will support manually prepared Paper installations at
`software/paper/{version}/paper.jar`.

## Build

```shell
mvn clean package
```

The shaded plugin JAR is written to:

```text
target/sls-lite-0.1.0-SNAPSHOT.jar
```

## Velocity Test

A reproducible local Velocity and Paper smoke environment is documented in
[DOCS/Velocity_Testing.md](DOCS/Velocity_Testing.md). Set it up with:

```powershell
.\scripts\setup-velocity-test.ps1
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
    max_players: 20
    max_instances: 1
    memory_limit: 2048

save: false

annotations:
  sls-lite:
    start-on-proxy-start: false
    stop-when-empty: true
```

`max_players` applies to each instance and is written to `server.properties`.
Queued joins reserve slots before the backend is ready. When all instances are
full, SLS-LITE may start another instance up to `max_instances`; afterward it
rejects the join with a capacity error. The same limit applies to direct
administrative starts and internal callers. Memory admission can still reject a
permitted instance when the host-wide managed-memory budget is exhausted.

## Roadmap

See [todo.md](todo.md) for the implementation roadmap, compatibility contract,
lobby modes, and first-release criteria.

## License

SLS-LITE is licensed under the GNU Affero General Public License v3.0. See
[LICENSE](LICENSE).
