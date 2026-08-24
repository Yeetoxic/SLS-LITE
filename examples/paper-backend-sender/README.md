# Paper Backend Sender Example

This minimal Paper plugin demonstrates SLS-LITE backend messaging protocol v1.
It has no dependency on the SLS-LITE plugin or Java API. It registers the
`slslite:request` outgoing channel and sends requests through the player who
invoked its example command.

Build it with Java 21 or newer:

```text
mvn clean package
```

Place the resulting JAR on an authorized Paper backend. Enable and authorize
that exact server, or its managed blueprint, under
`security.backend_messaging` in SLS-LITE's `config.yml`, then restart Velocity.

The test commands are:

```text
/slsbridge join <registry> <target>
/slsbridge command sls <arguments...>
```

An NPC or menu plugin may invoke the first form as the clicking player. For
example, an NPC targeting the `practice/practice` blueprint would run:

```text
slsbridge join practice practice
```

Do not configure the NPC to run it as console: plugin messages require the real
connected player as their carrier, and this example intentionally rejects
non-player command senders. See the canonical guide's
[NPC Quick Start](../../DOCS/integrations/backend-messaging/Built_In.md#npc-quick-start) for
the full Velocity configuration and troubleshooting sequence.

The second form succeeds only when command relay is enabled, the configured
source permits `command`, the command matches one of that source's
`command_roots`, and the carrier player passes the normal Velocity command
permission checks.

This is protocol sample code, not a universal command forwarder. Production
plugins should expose only their intended UI/NPC actions, generate a fresh
non-nil request UUID per user action, and never accept a caller-supplied player
identity. See
[Built-In Backend Messaging](../../DOCS/integrations/backend-messaging/Built_In.md) for the
full trust model and wire format, or the
[messaging hub](../../DOCS/integrations/backend-messaging/README.md) to compare integration paths.
