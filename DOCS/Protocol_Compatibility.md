# SLS-LITE Protocol Compatibility

This matrix records complete SLS-Limbo login tests. A protocol is not considered
compatible merely because Velocity starts, a status ping succeeds, or a library
claims to understand its packet IDs.

## Current Test Stack

| Component | Version |
| --- | --- |
| SLS-LITE | `0.1.0-SNAPSHOT` |
| Velocity | `4.0.0` build `6` |
| Java | Temurin `25.0.3` |
| NanoLimbo runtime | `1.13.0` at `d192d57d` |
| ViaVersion fixture | `5.11.0` |
| Fixed translation baseline | Minecraft `1.21.4`, protocol `769` |

ViaVersion test artifacts must come from its
[official releases](https://github.com/ViaVersion/ViaVersion/releases). The
local fixture pins the selected release and verifies its published SHA-256
before installation.

## Native SLS-Limbo Matrix

The automated native test launches the exact bundled NanoLimbo JAR, completes
an offline-mode login, reaches PLAY state, and verifies the backend brand.

| Client | Result | Test date |
| --- | --- | --- |
| `1.13.2` | Pass | 2026-07-25 |
| `1.16.5` | Pass | 2026-07-25 |
| `1.20.4` | Pass | 2026-07-25 |
| `1.21.4` | Pass | 2026-07-25 |
| `1.21.11` | Pass | 2026-07-25 |
| `26.1` | Pass, manual real-client test | 2026-07-25 |

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
launch. For the current fixture, protocol `769` is the explicitly selected
Minecraft `1.21.4` baseline:

```yaml
lobby:
  limbo:
    advertised_protocol: 769
```

This setting does not install ViaVersion and does not prove that a new client is
compatible. A translated release passes only after:

1. The installed Velocity accepts the client.
2. ViaVersion reports support for the client and baseline.
3. The player reaches SLS-Limbo through Velocity.
4. NanoLimbo logs the baseline protocol rather than the original client
   protocol.
5. The player can remain in SLS-Limbo and transfer to the primary lobby.

| Client | Backend baseline | Result | Test date |
| --- | --- | --- | --- |
| `1.21.11` | `1.21.4` (`769`) | Pass, automated login through Velocity | 2026-07-25 |
| `26.1` | `1.21.4` (`769`) | Pending real-client test | - |

ViaVersion discovers dynamically registered Velocity backends on its configured
`velocity-ping-interval`, which defaults to 60 seconds. On the first test boot,
the backend existed before that first probe completed and ViaVersion temporarily
used its fallback protocol. The mapping was persisted as `sls-limbo: 769` after
probing and loaded on restart. Until SLS-LITE adds a public-API synchronization
hook, operators using a fixed baseline must allow the probe to complete or
configure that mapping explicitly.

The current `1.21.11` translation emits ViaVersion warnings for NanoLimbo's
minimal heightmap. Login, PLAY state, and brand verification still complete,
but the warning must be evaluated before this path is declared release-ready.

ViaBackwards and ViaRewind remain optional operator choices. They are not
required by SLS-LITE and are not included in this matrix.

## Proxy Smoke Client

With the test proxy deliberately routing initial joins to SLS-Limbo, run:

```powershell
.\scripts\test-sls-limbo-protocols.ps1 `
  -Versions 1.21.4,1.21.11 `
  -ExpectedBrand SLS-Limbo
```

The command fails unless every client reaches PLAY state and receives the
expected backend brand. Use a unique local/offline test proxy; do not run
automated offline clients against a production online-mode network.
