# SLS-LITE Documentation

SLS-LITE is a single-host Velocity plugin; full SLS remains a separate
distributed product. Documentation is organized as a topic tree: choose a
branch here, then follow that branch's local navigation.

## What Are You Trying To Do?

- **Install SLS-LITE:** follow [Getting Started](setup/README.md).
- **Create your first server:** copy a working
  [blueprint recipe](blueprints/README.md#1-disposable-world).
- **Fix a problem:** start with [Troubleshooting](operations/Troubleshooting.md).
- **Connect an NPC or menu:** choose a
  [backend-messaging path](integrations/backend-messaging/README.md).

You do not need to read the whole tree. Each beginner guide gives you a short
working path first and links to reference material when a choice needs more
explanation.

## Documentation Tree

```text
DOCS/
|-- README.md                         <- start here
|-- setup/                            <- install, configure, data, software
|-- blueprints/                       <- recipes, schema, volumes, packs
|-- operations/                       <- commands, lifecycle, safety, recovery
|-- networking/                       <- SLS-Limbo and protocol support
|-- integrations/
|   `-- backend-messaging/            <- built-in or third-party NPC bridges
|-- compatibility/                    <- SLS comparison and migration from SLS
|-- extensions/                       <- public Java extension API
|-- development/                      <- internals, tests, contribution, release
`-- HISTORICAL/                       <- non-runtime research fixtures
```

## Operator Branches

### [Setup and configuration](setup/README.md)

Install SLS-LITE, complete the first run, configure the host, understand its
data layout, and install server software.

- [Host configuration](setup/Configuration.md)
- [Complete copyable configuration](setup/Copyable_Config.md)
- [Data layout](setup/Data_Layout.md)
- [Software installation](setup/Software_Installation.md)

### [Blueprints](blueprints/README.md)

Start with working recipes, then use the full schema and storage references for
custom definitions.

- [Blueprint schema](blueprints/Schema.md)
- [Volumes and storage modes](blueprints/Volumes.md)
- [Resource packs](blueprints/Resource_Packs.md)

### [Operations and recovery](operations/README.md)

Run the network, use commands, apply changes safely, diagnose failures, and
understand lifecycle concurrency and security boundaries.

- [Commands and permissions](operations/Commands.md)
- [Applying changes safely](operations/Applying_Changes.md)
- [Troubleshooting](operations/Troubleshooting.md)
- [Lifecycle concurrency](operations/Lifecycle_Concurrency.md)
- [Security and privacy](operations/Security_and_Privacy.md)

### [Networking and fallback](networking/README.md)

Configure and operate SLS-Limbo, then verify the exact supported protocol and
ViaVersion paths.

- [Protocol compatibility](networking/Protocol_Compatibility.md)

### [Backend and NPC integrations](integrations/backend-messaging/README.md)

Choose between SLS-LITE's source-verified backend channel and a separately
secured third-party command forwarder.

- [Built-in backend messaging](integrations/backend-messaging/Built_In.md)
- [Third-party command forwarding](integrations/backend-messaging/Third_Party.md)
- [Paper sender example](../examples/paper-backend-sender/README.md)

## Compatibility and Development Branches

### [Compatibility](compatibility/README.md)

Review current SLS/SLS-LITE boundaries, full-SLS and vSLS surface decisions, and
the supported process for migrating a network from SLS.

- [Full-SLS compatibility](compatibility/SLS_Main.md)
- [SLS command compatibility](compatibility/SLS_Commands.md)
- [Migrating from SLS](compatibility/Migration_From_SLS.md)

### [Java extension API](extensions/README.md)

Build trusted Velocity extensions against the public API and its documented
compatibility policy.

- [API scope and compatibility policy](extensions/Compatibility.md)
- [Example Velocity extension](../examples/velocity-extension/README.md)

### [Development and contribution](development/README.md)

Understand the codebase, internal invariants, test fixtures, and release gates.

- [Architecture](development/Architecture.md)
- [Contributor architecture](development/Contributor_Architecture.md)
- [Internal invariants](development/Internal_Invariants.md)
- [Testing](development/Testing.md)
- [Velocity fixture](development/Velocity_Testing.md)
- [Pterodactyl fixture](development/Pterodactyl_Local_Testing.md)
- [Release process](development/Release_Process.md)

## Suggested Journeys

### New operator

1. [Install and complete the first run](setup/README.md)
2. [Create a server from a recipe](blueprints/README.md)
3. [Learn commands and permissions](operations/Commands.md)
4. [Operate and recover the network](operations/README.md)
5. Read the [current release notes](../RELEASE_NOTES.md)

### Existing SLS operator

1. [Review compatibility](compatibility/README.md)
2. [Migrate from SLS](compatibility/Migration_From_SLS.md)
3. [Verify the current data layout](setup/Data_Layout.md)
4. [Run the migrated network](operations/README.md)

### NPC or menu integrator

1. [Choose a backend-messaging path](integrations/backend-messaging/README.md)
2. Follow either the [built-in](integrations/backend-messaging/Built_In.md) or
   [third-party](integrations/backend-messaging/Third_Party.md) guide
3. Test the player-context action before attaching it to an NPC

### Extension developer

1. [Read the Java API guide](extensions/README.md)
2. [Review the API compatibility policy](extensions/Compatibility.md)
3. [Build the example extension](../examples/velocity-extension/README.md)

### Contributor or tester

1. [Read the contribution guide](development/README.md)
2. [Understand the architecture](development/Architecture.md)
3. [Review internal invariants](development/Internal_Invariants.md)
4. [Select the correct tests](development/Testing.md)

## Canonical Generated References

The generated commented files remain the canonical schema examples:

- `src/main/resources/defaults/host/config.yml`
- `src/main/resources/defaults/blueprints/template.yml.example`
- `src/main/resources/defaults/software/paper-software.yml`
- `src/main/resources/defaults/software/vanilla-software.yml`

Compatibility claims cover only the documented subset. They do not imply that
every full-SLS definition, command, node feature, or API is available locally.

## Project References

- [Reviewable GitHub Wiki source](../WIKI/README.md)
- [Current release notes](../RELEASE_NOTES.md)
- [Contributing](../CONTRIBUTING.md)
- [Support](../SUPPORT.md)
- [Security policy](../SECURITY.md)
- [Code of Conduct](../CODE_OF_CONDUCT.md)
- [License](../LICENSE)
- [Third-party notices](../THIRD_PARTY/THIRD-PARTY-NOTICES.txt)
- [NanoLimbo provenance](../THIRD_PARTY/NanoLimbo.md)
- [SlimeLabs.net domain notice](../LEGAL/Domain_Usage_and_Protection_Notice_SlimeLabs_Net.txt)

Local credentials, imported worlds, generated Pterodactyl state, server logs,
and runtime caches are test data and must not be committed.
