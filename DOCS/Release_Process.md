# Release Process

[Documentation home](README.md)

The `Build Release` GitHub Actions workflow is the only supported publication
path. Every invocation builds from its selected source revision and verifies
the downloaded result on a clean runner; an existing tag or release is never
reused or promoted.

## Modes

| Mode | Version | Publication |
| --- | --- | --- |
| `distribution-smoke` | Any current project version | Unique private draft and temporary tag; deleted by default after verification. |
| `release-candidate` | `x.y.z-rc.N` | New public prerelease after approval by the protected `release-candidate` environment. |
| `release` | `x.y.z` | New stable release after approval by the protected `release` environment. |

Candidate and stable modes require `release_tag` to equal the exact
`v<project-version>` value. Configure required reviewers on both protected
GitHub environments before enabling publication. A rerun receives the same
requested mode but is rejected if its tag or release already exists.

## Before Running A Candidate

1. Set the candidate version consistently in the POM, `BuildInfo`, Velocity
   metadata, release notes, compatibility documents, and Wiki source.
2. Review stable SLS, Velocity, Paper, Minecraft, and ViaVersion lines. Update
   only after security/compatibility review and regression testing; record
   intentional pinned boundaries.
3. Run `mvn clean verify` and the relevant native-Linux,
   Windows/Pterodactyl/Velocity, protocol, forwarding, storage, lifecycle, and
   real-client scenarios.
4. Review the commented configuration and public documentation for
   availability, security, privacy, migration, and compatibility drift.
5. Confirm the bundled NanoLimbo checksum, license, notice, and source revision.
6. Run `distribution-smoke` and require its consumer checks to pass.

Then run `release-candidate` with the exact candidate tag. The workflow stages
one plugin JAR plus SDK artifacts and the canonical configuration, mirrors the
pinned NanoLimbo source archive, generates SHA-256 checksums, waits for
environment approval, creates a verification draft, downloads it on a clean
runner, verifies every checksum and the API boundary, compiles Maven and Gradle
consumers against the downloaded API JAR, and only then publishes the unchanged
draft after the final protected-environment gate.

## Stable Release

Do not convert or edit a candidate release into a stable release. Resolve
candidate blockers, change to a stable project version, update the current
release notes/docs, rerun affected tests, and invoke `release` from the approved
source revision with a new stable tag.

Repository `DOCS/` remains the canonical latest-release documentation. Publish
the reviewed `WIKI/` source to the separate GitHub Wiki repository only after
the matching artifact is approved. Old manuals remain available through Git
history and tags; do not maintain or advertise a historical version selector.
