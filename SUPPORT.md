# Support

## Start Here

1. Use the task index in [DOCS/README.md](DOCS/README.md).
2. Follow the [troubleshooting guide](DOCS/operations/Troubleshooting.md).
3. Search existing GitHub issues for the same symptom.
4. If the problem is reproducible and appears to be an SLS-LITE defect, open a
   bug report using the repository template.

Include the exact SLS-LITE version, Velocity and Java versions, host/container
environment, reproduction steps, expected result, and the smallest relevant
sanitized diagnostic excerpt. SLS-LITE cannot override hosting-panel process,
memory, filesystem, port, or network restrictions, so verify those limits with
the hosting provider when `/sls system` identifies a host capability failure.

## Use the Right Channel

- **Bug:** use the structured GitHub bug-report form.
- **Feature idea:** use the feature-request form.
- **Security vulnerability:** follow [SECURITY.md](SECURITY.md); never file it
  publicly.
- **Contribution question:** read [CONTRIBUTING.md](CONTRIBUTING.md) and the
  detailed contributor guide.
- **Minecraft, Velocity, Paper, Java, plugin, or hosting support:** use that
  project's or provider's support channel unless the issue is caused by
  SLS-LITE behavior.

Never attach forwarding secrets, credentials, administrator claim codes,
private player data, or full unrelated logs.
