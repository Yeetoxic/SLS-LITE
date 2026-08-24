# SLS-LITE Protocol Compatibility

[Documentation home](../README.md)

This matrix defines the supported SLS-Limbo login and transfer paths. A protocol
is not considered compatible merely because Velocity starts, a status ping
succeeds, or a library claims to understand its packet IDs.

`/sls join-test <server|this>` intentionally performs only that narrower status
ping. Use it to diagnose backend reachability and advertised protocol. A native
SLS-Limbo row requires a client to reach PLAY state and receive the expected
brand; a full-network row additionally requires the connected transfer path
through Velocity.

## Reference Stack

| Component | Version |
| --- | --- |
| SLS-LITE | `0.1.0-rc.2.3` |
| Velocity | `4.0.0` build `6` |
| Java | Temurin `25.0.3` |
| NanoLimbo runtime | `1.13.0` at `d192d57d` |
| ViaVersion fixture | `5.11.0` |
| Forward-compatible ViaVersion client floor | Minecraft `26.2`, protocol `776` |
| Fixed translation baseline | Minecraft `1.21.5`, protocol `770` |

Minecraft `26.2` is a minimum, not a frozen maximum, for the forward-compatible
ViaVersion client policy. SLS-LITE does not reject a newer client merely because
it postdates this document: when the installed ViaVersion build reports support
for that client and the configured backend baseline, the proxy may translate
it without an SLS-LITE update. The exact rows below remain the regression-tested
release paths; a successful translation does not certify unrelated gameplay,
world, mod, or backend-plugin behavior.

Development snapshots are not individually release-qualified. Operators may
test them through a ViaVersion build that explicitly supports them, but snapshot
behavior cannot block or expand the stable candidate matrix.

ViaVersion test artifacts must come from its
[official releases](https://github.com/ViaVersion/ViaVersion/releases). The
local fixture pins the selected release and verifies its published SHA-256
before installation.

## Native SLS-Limbo Matrix

Native operation uses NanoLimbo's per-client protocol advertisement and does
not require ViaVersion. These clients can complete login, reach PLAY state, and
receive the SLS-Limbo backend brand:

| Client | Supported path |
| --- | --- |
| `1.13.2` | Native SLS-Limbo login |
| `1.16.5` | Native SLS-Limbo login |
| `1.20.4` | Native SLS-Limbo login |
| `1.21.4` | Native SLS-Limbo login |
| `1.21.5` | Native SLS-Limbo login |
| `1.21.11` | Native SLS-Limbo login |
| `26.1` | Native SLS-Limbo login |

Run the automated matrix with:

```powershell
.\scripts\test-sls-limbo-native-protocols.ps1
```

The test-only Node dependency is pinned by
`tools/protocol-smoke/package-lock.json`, runs in offline authentication mode,
and is not packaged in the SLS-LITE plugin.

## ViaVersion Translation

`lobby.limbo.advertised_protocol` controls only the protocol NanoLimbo reports
during status and backend probing:

- `-1` keeps NanoLimbo's native per-client advertisement and is the default.
- A positive number advertises a fixed backend protocol that a
  proxy-installed ViaVersion can translate to.

SLS-LITE verifies that a fixed number exists in the bundled runtime before
launch. For the current fixture, protocol `770` is the explicitly selected
Minecraft `1.21.5` baseline:

```yaml
lobby:
  limbo:
    advertised_protocol: 770
```

This setting does not install ViaVersion and does not prove that a new client is
compatible. A translated release passes only after:

1. The installed Velocity accepts the client.
2. ViaVersion reports support for the client and baseline.
3. The player reaches SLS-Limbo through Velocity.
4. NanoLimbo logs the baseline protocol rather than the original client
   protocol.
5. The player can remain in SLS-Limbo and transfer to the primary lobby.

| Client | Backend baseline | Supported path |
| --- | --- | --- |
| `1.21.5` | `1.21.5` (`770`) | Full managed-lobby/SLS-Limbo handoff |
| `1.21.11` | `1.21.5` (`770`) | Full managed-lobby/SLS-Limbo handoff |
| `26.2` | `1.21.5` (`770`) | Full managed-lobby/SLS-Limbo handoff |
| Newer than `26.2` | `1.21.5` (`770`) | Forward-compatible when the installed ViaVersion build reports both protocols as supported; exact newer versions remain operator-qualified until added as regression-tested rows. |

When ViaVersion is present, SLS-LITE synchronizes every dynamic backend through
ViaVersion's public `ProtocolDetectorService` before publishing it as ready.
SLS-Limbo supplies its configured fixed protocol directly. Managed Paper
instances are pinged after their readiness marker and before their ready future
completes. The mapping is removed when the backend is unregistered, so recovered
composite IDs never wait for ViaVersion's periodic probe.

Host policy `compatibility.viaversion_backend_sync` controls this integration.
`auto` is the recommended default and uses ViaVersion when installed, `on`
makes a compatible ViaVersion installation a startup requirement, and `off`
leaves ViaVersion's backend protocol map entirely alone. The setting does not
install a translation plugin or expand the tested compatibility matrix.

NanoLimbo 1.13.0 wraps pre-1.21.5 NBT heightmaps in an extra `root` compound.
ViaVersion reports that malformed key while translating the chunk into the
1.21.5+ heightmap format. SLS-LITE therefore rejects fixed translation
baselines below protocol `770`; native `advertised_protocol: -1` operation is
unchanged. Protocol `770` uses NanoLimbo's modern heightmap encoding and avoids
maintaining a private patched runtime.

### Optional Older-Client Translation

ViaBackwards and ViaRewind are optional operator choices, not SLS-LITE
dependencies:

- [ViaBackwards](https://github.com/ViaVersion/ViaBackwards) allows older
  clients to join newer server protocols and requires ViaVersion.
- [ViaRewind](https://github.com/ViaVersion/ViaRewind) extends that older-client
  path to the legacy versions it supports. Consult its current release notes
  before making a compatibility claim.
- Install the Via plugins on Velocity **or** on the backend servers, never both.
  SLS-LITE networks should normally keep the matching set on Velocity because
  managed backends are dynamic. Follow ViaVersion's
  [official installation guidance](https://github.com/ViaVersion/ViaVersion/wiki/Installation)
  and fully restart the proxy after changing the set.

SLS-LITE does not download, update, configure, or guarantee these plugins.
Native SLS-Limbo operation with `advertised_protocol: -1` does not require any
Via plugin. An older-client version belongs in a release matrix only after that
exact plugin set completes the same Velocity -> SLS-Limbo -> managed-backend
transfer path; a Via project support table alone is not evidence.

## Proxy Smoke Client

With the test proxy deliberately routing initial joins to SLS-Limbo, run:

```powershell
.\scripts\test-sls-limbo-protocols.ps1 `
  -Versions 1.21.5,1.21.11 `
  -ExpectedBrand SLS-Limbo
```

The command fails unless every client reaches PLAY state and receives the
expected backend brand. Use a unique local/offline test proxy; do not run
automated offline clients against a production online-mode network.

## Managed-Lobby Handoff Smoke

The local Pterodactyl fixture can exercise the complete recovery path while a
protocol client remains connected. The test discovers the managed lobby,
forces its protected restart through the Velocity console, and requires the
client to observe `Paper (Velocity) -> SLS-Limbo -> Paper (Velocity)`:

```powershell
.\scripts\test-pterodactyl-lobby-handoff.ps1 `
  -Versions 1.21.5,1.21.11
```

This intentionally mutates the disposable local fixture by restarting its
managed lobby. It does not delete its persistent instance. A pass proves the
tested protocol's connected handoff and return path, not general game-mechanic
compatibility or support for untested client versions.

The pinned Node client cannot encode stable `26.2`; qualify that path with a
real client. A supported full-path run must keep the same connection while it
moves from the managed lobby to SLS-Limbo, waits through a protected lobby
restart, and returns after backend readiness and ViaVersion synchronization.
