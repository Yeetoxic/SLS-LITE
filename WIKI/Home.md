# SLS-LITE Wiki

Current candidate: **SLS-LITE 0.1.0-rc.2.3**. See the
[current release notes](Release-Notes).

SLS-LITE runs a small, dynamic Minecraft network inside one Velocity hosting
allocation. It launches local Java server processes, registers ready backends,
queues and transfers players, and provides a lightweight SLS-Limbo fallback.

SLS-LITE is a separate single-host product. It does not require or operate as a
node of full SLS, and it does not provide the distributed SLS controller,
daemon, container, or HTTP APIs.

## Start Here

- New installation: [Installation and First Run](Installation-and-First-Run)
- Existing installation: [Operations](Operations)
- Moving from full SLS: [Compatibility](Compatibility)
- Updating an older SLS-LITE installation: [Current Release](Release-Notes)
- Writing a trusted Velocity extension: [Java Extension Development](Java-Extension-Development)
- Connecting a Paper NPC or menu: [Backend Integrations](Backend-Integrations)
- Working on SLS-LITE itself: [Contributing](Contributing)
- Getting help: [Support](https://github.com/Yeetoxic/SLS-LITE/blob/main/SUPPORT.md)

The complete, version-controlled documentation index is the canonical source:
[SLS-LITE documentation](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/README.md).

## Verify Downloads

Install SLS-LITE only from a published project release. Verify the artifact
checksum and review the release notes and compatibility matrix before updating
an existing network. Wiki pages describe the product but are not a download
channel.

Project participation is governed by the repository's
[contribution guide](https://github.com/Yeetoxic/SLS-LITE/blob/main/CONTRIBUTING.md),
[security policy](https://github.com/Yeetoxic/SLS-LITE/blob/main/SECURITY.md), and
[Code of Conduct](https://github.com/Yeetoxic/SLS-LITE/blob/main/CODE_OF_CONDUCT.md).
