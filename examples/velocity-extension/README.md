# SLS-LITE Example Velocity Extension

This deliberately small plugin compiles against the public API classifier,
not the SLS-LITE runtime plugin. It demonstrates provider discovery, readiness,
immutable catalog and instance inspection, diagnostics, ordered lifecycle and
player events, namespaced actions, and owned cleanup.

Build and install the current SLS-LITE artifacts locally, then compile the
same source tree with Maven or Gradle:

```text
mvn clean install
mvn -f examples/velocity-extension/pom.xml clean verify
gradle -p examples/velocity-extension clean build
```

Both builds declare the `api` classifier as a provided/compile-only dependency.
Neither build bundles SLS-LITE or Velocity API classes in the example plugin.

The resulting example JAR is
`examples/velocity-extension/target/sls-lite-example-extension-1.0.0-SNAPSHOT.jar`.
Place it beside `sls-lite.jar` in Velocity's `plugins` directory. The example
is observational until one of its explicitly documented operation commands is
invoked.

Its extension namespace is `example-extension`. A blueprint can provide its
owned values without exposing another extension's configuration:

```yaml
annotations:
  example-extension:
    mode: observe
```

The plugin closes its `ExtensionContext` and unregisters its command during
proxy shutdown, releasing all event, action, and command registrations.

The live-operation example command is:

```text
/sls-api-example status
/sls-api-example start <blueprint>
/sls-api-example stop <instance>
/sls-api-example delete <instance>
/sls-api-example queue <registry> <blueprint>
/sls-api-example dequeue
```

`status`, `start`, `stop`, and `delete` require
`slslite.example.admin` when invoked by a player; the Velocity console is
treated as an administrator. `queue` and `dequeue` only operate on the player
who invoked them. The command reports public failure categories and never
prints internal exception messages.
