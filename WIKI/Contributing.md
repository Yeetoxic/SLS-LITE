# Contributing

Build the current tree with JDK 25 and Maven 3.9 or newer:

```text
mvn verify
```

The build emits Java 21-compatible plugin bytecode while compiling against the
pinned Velocity API. Verification includes tests, formatting, dependency
analysis, high-priority SpotBugs checks, packaging, public API boundaries, and
documentation contracts.

Choose tests according to risk. Lifecycle, storage, protocol, player routing,
or local-host changes also require their documented Docker, WSL/native-Linux,
Pterodactyl/Velocity, or real-client fixture.

Update canonical documentation with behavior changes. Commands, permissions,
configuration defaults, accepted/rejected fields, restart boundaries, security
effects, destructive behavior, and compatibility classifications must remain
explicit. Keep release/test records separate from operator instructions.

Canonical contributor material: [Contributing](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Contributing.md), [Architecture](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/ARCHITECTURE.md), [Contributor Architecture](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Contributor_Architecture.md), and [Testing](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Testing.md).
