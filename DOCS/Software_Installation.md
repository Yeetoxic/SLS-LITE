# Software Installation

[Documentation home](README.md)

Status: adapted for local mode.

SLS-LITE separates how software is launched, configured, and obtained. Paper is
the recommended default, but it is not the only software SLS-LITE can run.
Profiles are loaded recursively from `.yml` or `.yaml` files in
`software-profiles/`; IDs must be globally unique.

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

- `runtime` is `java-jar`. SLS-LITE launches argument lists directly
  and never invokes a shell.
- `configurator` is `paper`, `vanilla`, or `generic`. Paper forwarding files
  are only edited for `paper`.
- `source` is `manual`, `paper`, or `vanilla`.
- `channel` is `stable`, `beta`, or `alpha` for Paper. It is the maximum
  allowed instability: `beta` accepts stable or beta builds, and `alpha`
  accepts stable, beta, or alpha builds. It defaults to `stable`; non-stable
  channels must be selected explicitly.
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
configurator. Startup capability checks fail only when a runtime selected by an
active resolved blueprint is unavailable. Other configured runtimes are probed
and reported as optional warnings, so an unused missing Java version does not
disable the proxy.

Profiles created before provider support remain `manual` unless their
`software.source` is changed explicitly. Existing software is never replaced.

`runtime` accepts only `java-jar`. Unsupported enum values and unknown
structural keys fail profile loading.

## Modern SLS Definitions

SLS-LITE directly recognizes the modern SLS `software:` shape documented by
SLS and pinned in the compatibility contract at `v0.2.0`:

```yaml
software:
  id: paper
  name: Paper
  images:
    java_21: ghcr.io/protoxon/images:java_21
  mappings:
    - java_21: ">=1.20.5 <=1.21.11"
    - default: java_21
  limits:
    memory_limit: 4096
  invocation: "java -Xms128M -XX:MaxRAMPercentage=95.0 -jar server.jar"
  stop-command: stop
  online-signal: ")! For help, type"
```

The local adapter:

- preserves `id` and `name`;
- validates Docker `images` and version `mappings`, then uses the first matching
  mapping (or `default`) as the local Java selector when a blueprint omits
  `server.image`; it does not pull or run containers;
- supplies `limits.memory_limit` to blueprints that omit their own memory
  reservation;
- accepts only a directly tokenizable `java ... -jar <relative-file>`
  invocation and rejects shell operators;
- replaces container-relative `MaxRAMPercentage` or fixed `-Xmx` arguments
  with `-Xmx{memory_mib}M`;
- treats `online-signal` as a literal readiness substring;
- maps the known `paper` and `vanilla` IDs to verified SLS-LITE providers;
- treats other software IDs as manually installed Java servers;
- merges supported software-level `server.properties` defaults before
  blueprint patches and proxy-owned values;
- validates but does not enforce container limits; and
- never executes `install-script` or remote `update` behavior.

Modern image values remain container references and are never executed.
Mapping keys such as `java_17`, `java_21`, and `java_25` select an
operator-configured local Java runtime. Use the SLS-LITE profile shape with
`launch.java_versions` when multiple local Java installations are required;
launch fails clearly when the selected Java major is unavailable.

An unmodified modern Paper or vanilla definition does not record local EULA
acceptance. Existing verified caches may be reused, but a new provider download
remains blocked until acceptance is configured through an SLS-LITE profile.
Importing an upstream definition is not treated as accepting the Minecraft
EULA.

## Providers

The Paper provider requests the newest build allowed by the selected channel
for the exact blueprint Minecraft version from PaperMC's Downloads Service. A
more stable build is preferred when it is the newest compatible result. It
never substitutes another game version. It identifies SLS-LITE,
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
verification. An existing automatic-install directory that fails verification
is preserved in a sibling `.VERSION.incomplete-*` quarantine while a verified
replacement is installed. A failed replacement restores the quarantined
directory to its original path; a successful replacement retains it for
operator inspection. Cache cleanup ignores these unverified quarantines.
Failed staging directories are removed.

Provider-backed caches include an SLS-LITE metadata file. On every proxy
startup, SLS-LITE checks the configured source, channel, version, JAR path,
size, and digest before reusing the download. Manual caches intentionally
remain under operator control and only require the configured JAR.

```text
/sls install info
/sls install logs <software> <version>
/sls install warmup <software> <version>
/sls install cleanup <minimum-age-hours> [--confirm]
```

The in-memory log retains 200 lines per installation; the command displays the
latest ten to avoid chat spam. At most 100 completed installation records are
retained. A later start or join request retries a failed installation. Provider
access requires outbound HTTPS; manual profiles remain available when a host
blocks it.

Cache cleanup runs on a bounded background maintenance worker, scans at most
10,000 metadata files per request, and considers at most 1,000 candidates.
Command output lists at most 20 candidates while retaining exact aggregate
counts. Its default invocation is a dry run and requires a minimum age of at
least one hour. Deletion additionally requires the exact `--confirm` modifier.

Only provider caches carrying valid SLS-LITE ownership metadata are considered.
The scan validates each candidate against the currently loaded profile and its
exact resolved `base_directory`, including custom directories below the
SLS-LITE data root. Versions referenced by loaded blueprints, active or
persistent instances, or an installation currently in progress are protected
by both logical installation key and resolved directory. Cleanup is serialized
with installation admission and protects staging through publication. It uses
atomic sibling renames before recursive deletion, restoring the renamed
directory when deletion fails.

Aged verified caches, retained incomplete-cache quarantines, and staging
directories carrying SLS-LITE staging ownership metadata are eligible.
Manual directories, unrecognized metadata, symbolic links, and paths outside
the SLS-LITE data root are never cleanup candidates. Interrupted cleanup stops
before beginning another candidate.

Warmup uses the same EULA gate, exact provider/channel resolution, staging
directory, size/digest verification, cancellation, and atomic publication as a
normal first start. It never starts the downloaded server and never publishes
an incomplete template.

## Upstream Terms

Downloaded software is not bundled in SLS-LITE. Operators are responsible for
the Minecraft EULA and provider terms. PaperMC recommends stable builds and
warns against blindly auto-updating production servers.
