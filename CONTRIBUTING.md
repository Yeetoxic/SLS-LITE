# Contributing to SLS-LITE

Thanks for helping improve SLS-LITE. Keep changes focused, preserve existing
operator data, and add verification proportional to the lifecycle, storage, or
security risk of the change.

## Before You Start

1. Search existing issues and the [project plan](todo.md).
2. For a bug, include a minimal reproduction and sanitized diagnostics.
3. For a larger feature or architectural change, open a feature request before
   investing in an implementation.
4. Read the canonical [contributor guide](DOCS/development/README.md) and
   [architecture map](DOCS/development/Architecture.md).

Do not post credentials, forwarding secrets, administrator claim codes, player
addresses, private user data, or unrelated logs. Report security issues through
the process in [SECURITY.md](SECURITY.md), not through a public issue.

## Build and Verify

This project requires JDK 25 and Maven 3.9 or newer. Apply formatting and run the
complete local gate:

```shell
mvn spotless:apply
mvn clean verify
```

Add focused tests for changed behavior. Update affected defaults, examples,
operator documentation, compatibility notes, and `todo.md` status in the same
change when applicable.

## Pull Requests

- Explain the problem and the chosen behavior.
- Keep unrelated refactors separate.
- List automated and manual verification.
- Call out migrations, destructive behavior, compatibility changes, and known
  limitations.
- Do not commit `target/`, runtime data, generated server files, caches, logs,
  credentials, or local test allocations.

The detailed design rules, test selection, documentation standards, and release
requirements live in [DOCS/development](DOCS/development/README.md). By
participating, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
