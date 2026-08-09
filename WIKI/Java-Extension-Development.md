# Java Extension Development

Trusted Velocity plugins can use the additive, internally frozen Java API 1.2 for
capability discovery, immutable inspection, bounded diagnostics, lifecycle and
administrative requests, exact-instance routing, ordered events, namespaced
annotations and readiness/status contributions, and owned action callbacks.

Operators install only the full SLS-LITE plugin JAR. Extension builds use the
smaller `-api.jar` as a provided or compile-only dependency and must not bundle
it or import `api.internal` and other implementation packages.

Extensions must:

- declare SLS-LITE as a required Velocity plugin dependency;
- discover the API through `SLSLiteApiProvider`;
- check API version and advertised capabilities;
- keep callbacks non-blocking;
- own registrations through an `ExtensionContext` and close it on shutdown;
- authenticate and authorize any command, message, or network surface they add.

The API does not expose distributed nodes, containers, arbitrary storage or
process control, replacement matchmaking/lobby/install/storage providers, or
an authenticated HTTP administration endpoint.

Canonical guide: [Java Extension API](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Java_API.md). Contract boundaries: [API Scope and Compatibility Policy](https://github.com/Yeetoxic/SLS-LITE/blob/main/DOCS/Java_API_Compatibility.md). Working source: [Example Velocity Extension](https://github.com/Yeetoxic/SLS-LITE/tree/main/examples/velocity-extension).
