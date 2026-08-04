# Commands and Permissions

SLS-LITE uses `/sls`. Public commands cover browsing registries and joining
servers; administrative commands cover creation, lifecycle, logs, software,
reloads, diagnostics, and administrator bootstrap.

Authorization can come from the Velocity console, a claimed built-in
administrator, the umbrella `sls.command.admin` permission, or granular
`sls.command.<operation>` nodes. Operations targeting other players and forced
lobby lifecycle actions require their documented additional permissions.

Useful starting commands:

```text
/sls registries
/sls blueprints <registry>
/sls join <registry> <blueprint>
/sls list
/sls info [server]
/sls system
```

Use tab completion rather than guessing selectors or force syntax. Commands
that have no safe single-host equivalent return an explicit explanation.

Canonical command and permission inventory: [Commands](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Commands.md). Upstream naming differences are recorded in [vSLS Command Compatibility](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/SLS_Command_Compatibility.md).
