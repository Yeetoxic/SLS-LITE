# SLS-LITE Documentation

SLS-LITE is a single-host Velocity plugin; full SLS remains a separate
distributed product. Start with the journey that matches your role, then use
the canonical subject map to find detail without relying on duplicated
summaries.

## Suggested Journeys

### New operator

1. [Getting Started](Getting_Started.md)
2. [Blueprint Recipe Book](Blueprint_Recipes.md)
3. [Configuration](Configuration.md) and the [complete copyable config](Copyable_Config.md)
4. [Commands and Permissions](Commands.md)
5. [Full Blueprint Schema](Blueprints.md)
6. [Applying Changes Safely](Change_Application.md)
7. [Operations and Recovery](Operations.md)
8. [Current Release Notes](../RELEASE_NOTES.md)

### Existing SLS or SLS-LITE operator

1. [Compatibility](Compatibility.md)
2. [Migration](Migration.md)
3. [Data Layout](Data_Layout.md)
4. [Operations and Recovery](Operations.md)

### Extension developer

1. [Java Extension API](Java_API.md)
2. [API Scope and Compatibility Policy](Java_API_Compatibility.md)
3. [Example Velocity Extension](../examples/velocity-extension/README.md)
4. [Backend Messaging](Backend_Messaging.md)

### Contributor or tester

1. [Contributing](Contributing.md)
2. [Contributor Architecture Guide](Contributor_Architecture.md)
3. [Internal Invariants](Internal_Invariants.md)
4. [Testing](Testing.md)

## Canonical Subject Map

| Subject | Canonical page | Supporting detail |
| --- | --- | --- |
| Installation, first run, update, backup, removal | [Getting Started](Getting_Started.md) | [Data Layout](Data_Layout.md) |
| Host configuration and defaults | [Configuration](Configuration.md) | [Complete copyable config](Copyable_Config.md), generated `config.yml` |
| Blueprint recipes, schema, and behavior | [Blueprint Recipe Book](Blueprint_Recipes.md) | [Blueprints](Blueprints.md), [Blueprint Volumes](Blueprint_Volumes.md), [Resource Packs](Resource_Packs.md) |
| Commands and permissions | [Commands](Commands.md) | [vSLS command comparison](SLS_Command_Compatibility.md) |
| Daily operation, logs, lifecycle, recovery | [Operations](Operations.md) | [Lifecycle concurrency](Lifecycle_Concurrency.md) |
| Reload, restart, reset, and change application | [Applying Changes Safely](Change_Application.md) | [Blueprints](Blueprints.md), [Configuration](Configuration.md) |
| Failure diagnosis and problem reports | [Troubleshooting](Troubleshooting.md) | [Software Installation](Software_Installation.md), [Resource Packs](Resource_Packs.md) |
| Security and privacy | [Security and Privacy](Security_and_Privacy.md) | [Backend Messaging](Backend_Messaging.md) |
| Software profiles and installation | [Software Installation](Software_Installation.md) | Generated Paper and vanilla profiles |
| Lobby and player fallback behavior | [SLS-Limbo](SLS_Limbo.md) | [Protocol Compatibility](Protocol_Compatibility.md) |
| SLS and platform compatibility | [Compatibility](Compatibility.md) | [SLS v0.2.0 matrix](SLS_v0.2.0_Compatibility.md) |
| Migration | [Migration](Migration.md) | Historical fixtures under `DOCS/HISTORICAL/` |
| Java extension development | [Java Extension API](Java_API.md) | [API scope policy](Java_API_Compatibility.md), [example extension](../examples/velocity-extension/README.md) |
| Paper backend, NPC, and menu integrations | [Backend Messaging](Backend_Messaging.md) | [Paper sender example](../examples/paper-backend-sender/README.md) |
| Architecture and internal contracts | [Architecture](ARCHITECTURE.md) | [Contributor architecture](Contributor_Architecture.md), [internal invariants](Internal_Invariants.md) |
| Test selection and fixtures | [Testing](Testing.md) | [Velocity fixture](Velocity_Testing.md), [Pterodactyl fixture](Pterodactyl_Local_Testing.md) |
| Candidate and release procedure | [Release Process](Release_Process.md) | [Current release notes](../RELEASE_NOTES.md) |

The generated commented files are canonical schema examples:

- `src/main/resources/defaults/host/config.yml`
- `src/main/resources/defaults/blueprints/template.yml.example`
- `src/main/resources/defaults/software/paper-software.yml`
- `src/main/resources/defaults/software/vanilla-software.yml`

Compatibility claims cover only the documented subset. They do not imply that
every full-SLS definition, command, node feature, or API is available locally.

## Project References

Contributor and legal material is deliberately separate from operator
instructions:

- [Reviewable GitHub Wiki source](../WIKI/README.md)
- [Contributing](Contributing.md)
- [License](../LICENSE)
- [Third-party notices](../THIRD_PARTY/THIRD-PARTY-NOTICES.txt)
- [NanoLimbo provenance](../THIRD_PARTY/NanoLimbo.md)
- [SlimeLabs.net domain notice](../LEGAL/Domain_Usage_and_Protection_Notice_SlimeLabs_Net.txt)

Local credentials, imported worlds, generated Pterodactyl state, server logs,
and runtime caches are test data and must not be committed.
