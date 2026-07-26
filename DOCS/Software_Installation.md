# Software Installation

SLS-LITE separates how software is launched, configured, and obtained. Paper is
the recommended default, but it is not the only software SLS-LITE can run.

## Profile Model

```yaml
software:
  id: paper
  runtime: java-jar
  configurator: paper
  source: paper
  channel: stable
  accept_eula: false
  base_directory: software/paper/{version}
  server_jar: paper.jar
```

- `runtime` is currently `java-jar`. SLS-LITE launches argument lists directly
  and never invokes a shell.
- `configurator` is `paper`, `vanilla`, or `generic`. Paper forwarding files
  are only edited for `paper`.
- `source` is `manual`, `paper`, or `vanilla`.
- `channel` is `stable`, `beta`, or `alpha` for Paper. It defaults to
  `stable`; non-stable channels must be selected explicitly.
- `accept_eula` must be set to `true` before a provider download can begin.
  This records the operator's choice; SLS-LITE does not accept the Minecraft
  EULA by default.
- `base_directory` is the reusable software cache. It supports `{version}`,
  `{source}`, and `{channel}` placeholders. Include `{channel}` when one profile
  may switch channels while retaining older caches.
- `server_jar` is relative to that directory.

`launch.java` is the fallback executable. Optional `launch.java_versions`
entries select an executable by required Java major:

```yaml
launch:
  java: java
  java_versions:
    "21": runtimes/java-21/bin/java
    "25": runtimes/java-25/bin/java
```

SLS-LITE derives the required major from the Minecraft version and
configurator. Every configured executable is included in startup capability
checks.

Profiles created before provider support remain `manual` unless their
`software.source` is changed explicitly. Existing software is never replaced.

## Providers

The Paper provider requests the newest build in the selected channel for the
exact blueprint Minecraft version from PaperMC's Downloads Service. It never
substitutes another game version. It identifies SLS-LITE,
accepts only HTTPS PaperMC hosts, enforces a 256 MiB limit, and verifies artifact
size and SHA-256 metadata. Missing version/channel combinations fail explicitly.

The vanilla provider resolves the exact release through Mojang's version
manifest, accepts only HTTPS Mojang hosts, enforces the same size limit, and
verifies the published size and SHA-1 digest.

Paper supports Velocity modern forwarding. Vanilla does not. Vanilla therefore
requires a compatible proxy configuration and has reduced identity forwarding
behavior. Keep every managed backend bound to loopback.

Custom Java servers use `source: manual` and normally
`configurator: generic`. Place prepared files at the expanded
`base_directory`; SLS-LITE requires the configured server JAR plus readiness
and shutdown behavior.

## Lifecycle

The first start or join request for missing provider-backed software starts an
installation. Requests for the same software and version share one operation.
Downloads use a temporary sibling directory and are published only after
verification. Existing incomplete directories are preserved and reported.
Failed staging directories are removed.

Provider-backed caches include an SLS-LITE metadata file. On every proxy
startup, SLS-LITE checks the configured source, channel, version, JAR path,
size, and digest before reusing the download. Manual caches intentionally
remain under operator control and only require the configured JAR.

```text
/sls install info
/sls install logs <software> <version>
```

The in-memory log retains 200 lines per installation; the command displays the
latest ten to avoid chat spam. At most 100 completed installation records are
retained. A later start or join request retries a failed installation. Provider
access requires outbound HTTPS; manual profiles remain available when a host
blocks it.

## Upstream Terms

Downloaded software is not bundled in SLS-LITE. Operators are responsible for
the Minecraft EULA and provider terms. PaperMC recommends stable builds and
warns against blindly auto-updating production servers.
