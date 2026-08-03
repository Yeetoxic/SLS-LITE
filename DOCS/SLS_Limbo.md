# SLS-Limbo

## Status

SLS-Limbo is SLS-LITE's default virtual lobby: a literal liminal space for a
network that is not fully configured yet, a destination that is still starting,
or a player moving between places. Players remain connected and can use
Velocity-level `/sls` commands without requiring a Paper lobby to be ready.
The project as a whole is not yet production-ready. SLS-Limbo has automated
native protocol coverage and local recovery coverage; translated real-client
coverage remains a release gate.

SLS-Limbo complements the configured lobby rather than becoming a third primary
lobby mode. `lobby.mode` remains either `external` or `managed`.

## Routing Rule

SLS-Limbo is used only when SLS-LITE cannot safely keep a player on their
current backend or connect them to a usable primary lobby. It is not the normal
matchmaking queue.

When a player runs `/sls join` from a healthy server, they remain there and can
move and play normally while SLS-LITE queues the request and starts the
destination. Existing action-bar status updates continue on that server. The
player transfers directly to the destination when it is ready.

SLS-Limbo is appropriate when:

- A player has no usable initial backend.
- Their current backend stops or disconnects them and the primary lobby is not
  ready.
- SLS-LITE must preserve their proxy connection while a required safe
  destination recovers.

Automatic handoff from SLS-Limbo applies only to players who had to enter it for
one of these reasons.

SLS-LITE tracks those players only until they reach the primary lobby or
disconnect. Managed primaries publish lifecycle readiness after startup or
recovery. Failed external primaries are checked with a lightweight Velocity
server ping; SLS-LITE does not attempt the handoff until that probe succeeds.
If the connection still fails, the player remains in SLS-Limbo and health
checking resumes.

Per-player handoff failures use exponential retry delays of 10, 20, 40, and
then 60 seconds. The player receives one chat explanation per SLS-Limbo waiting
episode. When Velocity provides a backend disconnect reason, such as an outdated
server protocol, SLS-LITE includes that reason in the explanation. Connection
refusals and timeouts use safe, human-readable fallback text. Later failures use
a static action-bar status. Only the first failure is logged as a warning, with
repeated failures available at debug level. This also prevents an online but
protocol-incompatible backend from creating a connection and notification loop.

## How It Works

SLS-LITE includes an unmodified, pinned NanoLimbo runtime inside the SLS-LITE
plugin JAR. On startup SLS-LITE:

1. Verifies and extracts the runtime into
   `plugins/sls-lite/sls-limbo/`.
2. Reserves its configured heap from the same managed-memory budget used by
   Paper instances.
3. Allocates a loopback port from the configured SLS-LITE port range.
4. Starts the runtime as a supervised child Java process.
5. Registers the ready backend in Velocity as `sls-limbo`.
6. Starts or selects the configured primary lobby.

The runtime is a child process instead of an in-process Velocity library. This
keeps its Netty, Adventure, logging, and configuration dependencies out of
Velocity's plugin classloader. Operators still install only the SLS-LITE JAR.

For a managed primary lobby, players can enter SLS-Limbo while Paper starts or
recovers. For an external primary lobby, Velocity attempts the external backend
first and redirects a failed connection to SLS-Limbo. It also provides the
baseline in-game environment before an operator has completed the primary lobby
setup. Once the primary is ready, new initial connections prefer it.

## Configuration

```yaml
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
```

- `enabled`: starts the bundled fallback when `true`.
- `memory_mib`: SLS-Limbo runtime heap reservation. Minimum: 64 MiB.
- `startup_timeout_seconds`: maximum time allowed for its readiness message.
- `recovery.max_attempts`: bounded retries after an unexpected failure. Set to
  `0` to leave SLS-Limbo offline after the first failure.
- `recovery.initial_backoff_seconds`: delay before the first retry.
- `recovery.max_backoff_seconds`: cap for exponential retry delays.
- `recovery.stable_after_seconds`: healthy period required to reset the used
  retry budget.

The SLS-Limbo reservation is included in `resources.total_memory_mib`. It also
uses one port from `network.ports` and one slot from
`resources.max_managed_processes`. In managed-lobby mode, SLS-LITE rejects
startup unless the declared budget and process limit can accommodate both
SLS-Limbo and the primary lobby. Its actual host usage includes JVM native
memory in addition to the configured heap, just like every Java child process.

## Player Experience

The fallback provides a minimal static Minecraft environment, a status title and
boss bar, and access to commands handled by Velocity. It does not run Bukkit,
Paper, worlds, plugins, mobs, inventories, redstone, or normal server mechanics.
Commands that are implemented by SLS-LITE at the proxy remain available,
including the built-in administrator claim and management commands.

## Forwarding and Security

SLS-Limbo binds only to `127.0.0.1`. With `forwarding.mode: modern`, SLS-LITE
copies the configured Velocity forwarding secret into the isolated SLS-Limbo
runtime directory and references that file from generated settings. The secret
is not embedded in generated YAML, command arguments, or normal logs.

The generated SLS-Limbo player capacity follows Velocity's
`show-max-players` value instead of using a separate hard-coded network size.

Do not expose a managed port publicly. In offline-mode test environments,
built-in administrator claims remain disabled unless
`security.allow_insecure_offline_administrators` is explicitly enabled.

Development builds that used `lobby.emergency` are still accepted as a
deprecated alias. New configurations and documentation use `lobby.limbo`.

## Compatibility

The bundled runtime is NanoLimbo 1.13.0 at commit
`d192d57d1d4a5fdc7b87643f453d82cb7b9b4242`. Its binary is checked before each
launch. See [the third-party notice](../THIRD_PARTY/NanoLimbo.md) for source,
license, and checksum details.

SLS-LITE can advertise an explicitly configured, validated NanoLimbo protocol
baseline for a proxy-installed ViaVersion. Native per-client advertisement
remains the default. See
[the protocol compatibility matrix](Protocol_Compatibility.md) for tested
versions and the translated-path acceptance criteria. Neither ViaVersion
detection nor a newly released client is treated as automatic compatibility.

## Recovery

SLS-Limbo keeps its memory reservation and loopback port while replacing a
failed child process. Recovery does not stop or restart Velocity, the primary
lobby, or managed game servers. The backend is unregistered while offline and
registered again only after the replacement runtime reports readiness. When the
configured retry budget is exhausted, SLS-LITE releases the reservation and
port after the final child exits.

`/sls info` reports SLS-Limbo state, memory, port, and retry usage. `/sls system`
adds the last failure and whether any safe lobby is currently available. If both
the primary lobby and SLS-Limbo reach terminal `OFFLINE` states, SLS-LITE logs
one actionable error and disconnects players who have no usable backend.

## Current Limitations

- External-primary failure is detected reactively after a failed player
  connection; once detected, recovery is checked with background pings.
- Native client compatibility has an automated representative matrix.
  ViaVersion-translated proxy coverage remains a release gate.
- If both the primary lobby and SLS-Limbo fail, SLS-LITE enters a degraded
  no-lobby state. Players without a usable backend are disconnected with a clear
  lobby-unavailable message, while Velocity, console administration, diagnostics,
  and bounded recovery continue running.

## Manual Pterodactyl Test

1. Build and deploy the current SLS-LITE JAR, then restart Velocity.
2. Confirm the console reports `SLS-Limbo is ready`.
3. Connect normally and verify the configured primary lobby is preferred.
4. Stop the external lobby, or stop the managed lobby process during recovery.
5. Reconnect. Velocity should redirect you to `sls-limbo`.
6. Run `/sls info`, `/sls list`, and an authorized administrative command.
7. Restart the primary lobby and remain connected to SLS-Limbo.
8. Confirm SLS-LITE transfers you automatically after the primary responds.
9. From a healthy backend, run a join command that starts another server and
   confirm you remain on the current backend until the direct transfer.
10. Shut down Velocity and confirm the SLS-Limbo child exits cleanly.

For a recovery fault test, terminate only the NanoLimbo child process. Confirm
the primary lobby remains usable, the console schedules a bounded retry, and
SLS-Limbo returns on the same loopback port without restarting Velocity.

For the local fixture, see
[Pterodactyl_Local_Testing.md](Pterodactyl_Local_Testing.md).
